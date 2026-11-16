// INCOMING attachments: "<20 hex>.enc.png" files sent by Aegires or by this plugin.
//
// Discord draws the chat list natively, so no image component can be swapped from JS. Instead the
// attachment objects are rewritten in place before the message store sees them:
//   1. A finished file is shown at once: url/proxy_url point at the decrypted copy in Discord's
//      cache dir (file://), and filename/content_type/size/width/height are the real ones.
//   2. A new one is queued: the native half (Java FileOps) downloads it from Discord's CDN and
//      decrypts it straight into the cache dir, so the bytes never cross the JS bridge. Android
//      then reports the pixel size.
//   3. A partial MESSAGE_UPDATE is dispatched so the message store picks up the rewritten attachment.
// Images, GIFs, videos and audio. Decrypted files are deleted when the plugin starts and when it stops.
//
// Videos and audio cannot be played by Discord from a file:// path, so the chat shows a picture
// made by the native half (a video's first frame with a play button and its length; for audio a
// small card with title and length). Tapping that picture opens the native player (player.ts).
//
// While a file is not decrypted yet the chat shows a placeholder picture instead of the raw
// "xyz.enc.mp4" file card:
//   - a file that takes longer than a moment: "Decrypting video... 42%" with a progress ring (the
//     percentage is the download + decryption progress, which are one stream);
//   - a file above the size limit (settings, default 25 MB): "Tap to decrypt - 120 MB". Tapping it
//     (player.ts calls approveDecrypt) starts the work. Nothing is downloaded before that.
import { storage } from "../storage.ts";
import { unscoped } from "../host.ts";
import { base64Encode } from "../crypto/base64.ts";
import { MAX_FILE_BYTES, extOf, imageMimeFor, isEncryptedFileName, mimeFor, originalFileName, rawCdnUrl } from "../crypto/files.ts";
import { getReceiveKeysV2 } from "../state.ts";
import { attachmentsEnabled } from "../features.ts";
import { cacheDir, nativeMediaInfo, nativeMediaThumb, nativeOpenFile, nativePlaceholder, nativeProgress, nativeReady, removePath } from "../engine/engine.ts";
import { diag } from "../diag.ts";

const log = (...a: any[]) => { if (storage.debug) console.log("[aegires-e2ee:media]", ...a); };

type Kind = "image" | "video" | "audio";
/** Something the native player can play: the absolute path of the decrypted file and a title for audio. */
export interface PlayableMedia { path: string; title: string }
interface Decrypted { uri: string; name: string; mime: string; size: number; w: number; h: number; kind: Kind; thumb?: string; title: string }

interface Placeholder { state: "tap" | "work"; w: number; h: number; path: string; kind: Kind }
/** A message's attachments as the server sent them, so the chat view can be rebuilt after each step. */
interface MsgCtx { id: string; chan: string; author: string; atts: any[] }

const done = new Map<string, Decrypted>(); // attachment id -> decrypted file
const ph = new Map<string, Placeholder>(); // attachment id -> the placeholder picture shown for it now
const phPaths = new Map<string, string>(); // placeholder picture path -> attachment id (for taps)
const ctxs = new Map<string, MsgCtx>(); // message id -> its context
const ctxOfAtt = new Map<string, string>(); // attachment id -> message id
const approved = new Set<string>(); // large files the user chose to decrypt
let phSeq = 0;
const drawingTap = new Set<string>(); // "Tap to decrypt" pictures being drawn right now
const failed = new Set<string>(); // ids that did not decrypt (no retry until the plugin restarts)
const queued = new Set<string>();
const msgAtts = new Map<string, string[]>(); // message id -> its attachment ids (for tap events)
const thumbs = new Map<string, PlayableMedia>(); // absolute path of a chat picture -> the media it stands for

const MEDIA_EXT = /\.(mp4|m4v|mov|webm|mkv|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac|amr|mka)$/i;

const playable = (d?: Decrypted): PlayableMedia | null =>
  d && d.kind !== "image" ? { path: d.uri.replace(/^file:\/\//, ""), title: d.title } : null;

/** The decrypted video or audio a tap refers to: by attachment ids, or message id + index. */
export function decryptedMedia(ids: string[], messageId?: string, index?: number): PlayableMedia | null {
  for (const id of ids) {
    const p = playable(done.get(id));
    if (p) return p;
  }
  const list = messageId ? msgAtts.get(messageId) : undefined;
  if (!list) return null;
  if (index != null) return playable(done.get(list[index] ?? ""));
  const all = list.map((id) => playable(done.get(id))).filter((p): p is PlayableMedia => !!p);
  return all.length === 1 ? all[0] : null;
}

/** The media behind a file:// uri Discord reports: one of our chat pictures, or a decrypted media file itself. */
export function mediaForUri(uri: unknown): PlayableMedia | null {
  if (typeof uri !== "string" || !uri.startsWith("file://") || !uri.includes("/aegires/")) return null;
  let path = uri.split("?")[0].replace(/^file:\/\//, "");
  try { path = decodeURIComponent(path); } catch {}
  const hit = thumbs.get(path);
  if (hit) return hit;
  return MEDIA_EXT.test(path) ? { path, title: "" } : null;
}

/** Settles when the folder of decrypted files left by an earlier session has been emptied. */
let purged: Promise<void> = Promise.resolve();

// ---------------------------------------------------------------- work queue
const MAX_PARALLEL = 2;
let running = 0;
const jobs: Array<() => Promise<void>> = [];

function pump(): void {
  while (running < MAX_PARALLEL && jobs.length) {
    const job = jobs.shift()!;
    running++;
    job().finally(() => { running--; pump(); });
  }
}

/** Runs fn through the queue; the promise settles (never rejects) when fn has finished. */
function enqueue(fn: () => Promise<void>): Promise<void> {
  return new Promise((resolve) => {
    jobs.push(() => fn().catch(() => {}).finally(resolve));
    pump();
  });
}

function dispatch(e: any): void {
  try {
    const d = unscoped().discord.common.flux.Dispatcher;
    if (typeof d?.dispatch === "function") void Promise.resolve(d.dispatch(e)).catch((err: unknown) => log("dispatch failed", err));
  } catch (err) {
    log("dispatch failed", err);
  }
}

// ---------------------------------------------------------------- helpers
/** Attachment ids come from the server; keep them safe to use in a file name. */
const idOf = (a: any): string => String(a?.id ?? "").replace(/[^0-9A-Za-z_-]/g, "");

/** An "x.enc.png" attachment that still points at Discord's CDN. */
function isEncAttachment(a: any): boolean {
  if (!a || typeof a !== "object" || !isEncryptedFileName(a.filename)) return false;
  return String(a.url ?? a.proxy_url ?? "").startsWith("https://");
}

function apply(a: any, d: Decrypted): void {
  a.url = d.uri;
  a.proxy_url = d.uri;
  a.filename = d.name;
  a.content_type = d.mime;
  a.size = d.size;
  // Discord only draws images inline when it knows their size.
  a.width = d.w;
  a.height = d.h;
  if (d.kind !== "image") {
    // Discord's inline player streams only over http(s) and fails on file://. Declared as an
    // image, the chat draws our picture instead: the native half's thumbnail (play button on a
    // video's first frame, or an audio card). If no thumbnail could be made, a video file is shown
    // as is (Discord's image loader reads its first frame). Playing it on tap is player.ts.
    if (d.thumb) {
      a.url = "file://" + d.thumb;
      a.proxy_url = a.url;
    }
    a.content_type = "image/jpeg";
    a.filename = d.name + ".jpg";
  }
}

/** Shows a placeholder picture where the attachment would be. */
function applyPh(a: any, p: Placeholder): void {
  a.url = "file://" + p.path;
  a.proxy_url = a.url;
  a.filename = "encrypted-" + p.kind + ".jpg";
  a.content_type = "image/jpeg";
  a.width = p.w;
  a.height = p.h;
}

/** An attachment as the chat should show it now: decrypted, placeholder, or untouched. */
function viewOf(orig: any): any {
  const a = { ...orig };
  if (!isEncAttachment(a)) return a;
  const id = idOf(a);
  const d = done.get(id);
  if (d) apply(a, d);
  else {
    const p = ph.get(id);
    if (p) applyPh(a, p);
  }
  return a;
}

/** Pushes the current view of a message's attachments into the message store. */
function refresh(ctx: MsgCtx): void {
  dispatch({ type: "MESSAGE_UPDATE", message: { id: ctx.id, channel_id: ctx.chan, attachments: ctx.atts.map(viewOf) } });
}

/** What the audio card and the player call a file: its name without the extension, unless that is just random hex. */
function titleFor(name: string, kind: Kind): string {
  const stem = name.replace(/\.[^./]+$/, "");
  return !stem || /^[0-9a-f]{16,}$/i.test(stem) ? (kind === "video" ? "Video" : "Audio") : stem;
}

/** Files bigger than this (encrypted size, bytes) wait for a tap. Settings value in MB; 0 = always ask. */
export function autoDecryptBytes(): number {
  const raw = storage.autoDecryptMB;
  const n = raw === undefined || raw === null || raw === "" ? 25 : Number(raw);
  return (Number.isFinite(n) && n >= 0 ? Math.min(n, 1024) : 25) * 1048576;
}

function kindOfName(a: any): Kind {
  const mime = mimeFor(originalFileName(String(a.filename)));
  return mime.startsWith("video/") ? "video" : mime.startsWith("audio/") ? "audio" : "image";
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** Draws a placeholder (native) and shows it, unless alive() says the file is no longer waiting. */
async function showPlaceholder(ctx: MsgCtx, a: any, state: "tap" | "work", pct: number | null, alive: () => boolean): Promise<void> {
  const id = idOf(a);
  const kind = kindOfName(a);
  const path = `${await cacheDir()}/aegires/in/${id}.ph${++phSeq}.jpg`;
  const dims = await nativePlaceholder(kind, state, pct, Number(a.size) || 0, path);
  if (!alive()) return;
  ph.set(id, { state, w: dims.w, h: dims.h, path, kind });
  phPaths.set(path, id);
  refresh(ctx);
}

/**
 * Queues the download + decryption of one attachment. If it takes longer than delayMs, a
 * "Decrypting..." placeholder is shown and its percentage is kept up to date until it is done.
 */
function startDecrypt(ctx: MsgCtx, a: any, id: string, keys: Uint8Array[], delayMs: number): void {
  queued.add(id);
  const snapshot = { ...a };
  let started = false;
  let finished = false;
  const alive = () => !finished;

  const track = async () => {
    try {
      const out = `${await cacheDir()}/aegires/in/${id}.${extOf(originalFileName(String(snapshot.filename)))}`;
      let shown = -2; // last percentage drawn (-1 = unknown)
      while (!finished) {
        const pct = started ? await nativeProgress(out).catch(() => null) : 0;
        const now = pct ?? -1;
        if (!finished && (shown === -2 || (now >= 0 && now >= shown + 3))) {
          shown = now;
          await showPlaceholder(ctx, snapshot, "work", pct, alive).catch((e) => log("placeholder failed", id, e));
        }
        await sleep(600);
      }
    } catch (e) {
      log("progress loop failed", id, e);
    }
  };
  const timer = setTimeout(() => { if (!finished) void track(); }, delayMs);

  void enqueue(async () => {
    started = true;
    try {
      done.set(id, await decryptOne(snapshot, id, keys, ctx.chan, ctx.author));
      diag.attDecrypted++;
    } catch (e) {
      failed.add(id);
      diag.attFailed++;
      diag.lastAttError = String((e as any)?.message ?? e).slice(0, 80);
      log("decrypt failed", id, e);
    } finally {
      finished = true;
      clearTimeout(timer);
      queued.delete(id);
      ph.delete(id); // done: the real picture replaces it; failed: the original file card comes back
      refresh(ctx);
    }
  });
}

async function decryptOne(a: any, id: string, keys: Uint8Array[], channelId: string, authorId: string): Promise<Decrypted> {
  await purged;
  const name = originalFileName(String(a.filename));
  const out = `${await cacheDir()}/aegires/in/${id}.${extOf(name)}`;
  const url = rawCdnUrl(String(a.url ?? a.proxy_url));
  let size = -1;
  let last: unknown = null;
  for (const key of keys) {
    try {
      size = await nativeOpenFile(base64Encode(key), channelId, authorId, url, out, MAX_FILE_BYTES);
      break;
    } catch (e) {
      last = e;
      // Only a failed decryption means "try the next key"; network and disk errors stop here.
      if (!String((e as any)?.message ?? e).includes("decrypt failed")) throw e;
    }
  }
  if (size < 0) throw last ?? new Error("wrong key or corrupted file");
  const mime = mimeFor(name);
  const kind: Kind = mime.startsWith("video/") ? "video" : mime.startsWith("audio/") ? "audio" : "image";
  const title = titleFor(name, kind);
  let w = 0;
  let h = 0;
  let thumb: string | undefined;
  if (kind !== "audio") {
    const info = await nativeMediaInfo(out, kind).catch(() => null);
    w = info?.w ?? (Number(a.width) || (kind === "video" ? 640 : 480));
    h = info?.h ?? (Number(a.height) || 360);
  }
  if (kind !== "image") {
    const thumbPath = `${await cacheDir()}/aegires/in/${id}.thumb.jpg`;
    try {
      const t = await nativeMediaThumb(kind, out, thumbPath, title);
      thumb = thumbPath;
      w = t.w;
      h = t.h;
      thumbs.set(thumbPath, { path: out, title });
    } catch (e) {
      if (kind === "audio") throw e; // an audio file has no picture of its own to fall back to
      log("no thumbnail for the video", id, e);
    }
  }
  return { uri: "file://" + out, name, mime, size, w, h, kind, thumb, title };
}

// ---------------------------------------------------------------- tap to decrypt
/** The attachment a placeholder picture uri belongs to. */
export function placeholderIdForUri(uri: unknown): string | null {
  if (typeof uri !== "string" || !uri.startsWith("file://") || !uri.includes("/aegires/")) return null;
  let path = uri.split("?")[0].replace(/^file:\/\//, "");
  try { path = decodeURIComponent(path); } catch {}
  return phPaths.get(path) ?? null;
}

/** The placeholder a tap refers to (by attachment ids, or message id + index), if it is one of ours. */
export function placeholderIdFor(ids: string[], messageId?: string, index?: number): string | null {
  for (const id of ids) if (ph.has(id)) return id;
  const list = messageId ? ctxs.get(messageId)?.atts : undefined;
  if (!list) return null;
  if (index != null) {
    const id = idOf(list[index] ?? {});
    return ph.has(id) ? id : null;
  }
  const hits = list.map((x) => idOf(x)).filter((id) => ph.has(id));
  return hits.length === 1 ? hits[0] : null;
}

/**
 * A tap on a placeholder: starts decrypting a "Tap to decrypt" file; a tap on one that is already
 * working does nothing. Returns true when the tap belongs to a placeholder (so Discord's viewer is skipped).
 */
export function approveDecrypt(id: string): boolean {
  const p = ph.get(id);
  if (!p) return false;
  if (p.state !== "tap" || queued.has(id)) return true;
  const ctx = ctxs.get(ctxOfAtt.get(id) ?? "");
  const a = ctx?.atts.find((x) => idOf(x) === id);
  const keys = ctx ? getReceiveKeysV2(ctx.chan) : [];
  if (!ctx || !a || !keys.length) return true;
  approved.add(id);
  startDecrypt(ctx, a, id, keys, 0);
  return true;
}

// ---------------------------------------------------------------- entry points
export function handleAttachments(m: any, channelId: string | undefined, authorId: string | undefined, tag: { w: number; h: number } | null = null): void {
  if (!attachmentsEnabled(storage.attachmentsExp) || !nativeReady() || !Array.isArray(m.attachments)) return;
  const msgId = m.id != null ? String(m.id) : "";
  const chan = String(m.channel_id ?? channelId ?? "");
  if (msgId) msgAtts.set(msgId, m.attachments.map((a: any) => idOf(a)));
  let ctx: MsgCtx | undefined = msgId ? ctxs.get(msgId) : undefined;
  let tagUsed = false;
  const limit = autoDecryptBytes();
  for (const a of m.attachments) {
    if (!isEncAttachment(a)) continue;
    const orig = originalFileName(String(a.filename));
    const mime = mimeFor(orig);
    if (!imageMimeFor(orig) && !mime.startsWith("video/") && !mime.startsWith("audio/")) continue;
    const id = idOf(a);
    if (!id) continue;
    if (msgId && chan && authorId) {
      // Remember the message as the server sent it (the first sight has the CDN urls).
      if (!ctx) {
        ctx = { id: msgId, chan, author: authorId, atts: m.attachments.map((x: any) => ({ ...x })) };
        ctxs.set(msgId, ctx);
      }
      ctxOfAtt.set(id, msgId);
    }
    const hit = done.get(id);
    if (hit) { apply(a, hit); continue; }
    const wait = ph.get(id);
    if (wait) { applyPh(a, wait); continue; }
    // The sender's [sz:W,H] tag (first picture/video only) lets Discord reserve the right space
    // before the file is downloaded; the decrypted file's real size replaces it afterwards.
    if (tag && !tagUsed) { a.width = tag.w; a.height = tag.h; tagUsed = true; }
    if (failed.has(id) || queued.has(id)) continue;
    const keys = getReceiveKeysV2(channelId);
    if (!ctx || !keys.length) continue;
    diag.attSeen++;
    const size = Number(a.size) || 0;
    if (size > limit && !approved.has(id)) {
      // Too big to decrypt on its own: show "Tap to decrypt" and wait.
      if (drawingTap.has(id)) continue;
      drawingTap.add(id);
      void showPlaceholder(ctx, { ...a }, "tap", null, () => !ph.has(id) && !done.has(id) && !queued.has(id))
        .catch((e) => log("placeholder failed", id, e))
        .finally(() => drawingTap.delete(id));
      continue;
    }
    startDecrypt(ctx, a, id, keys, 450);
  }
}

/** Forgets everything and deletes the decrypted files (plugin start and stop). */
export function clearMedia(): void {
  jobs.length = 0;
  done.clear();
  ph.clear();
  phPaths.clear();
  ctxs.clear();
  ctxOfAtt.clear();
  approved.clear();
  drawingTap.clear();
  failed.clear();
  queued.clear();
  msgAtts.clear();
  thumbs.clear();
  purged = (async () => {
    try {
      await removePath(`${await cacheDir()}/aegires/in`);
    } catch {
      // The native bridge may not be up yet; nothing to delete then.
    }
  })();
}
