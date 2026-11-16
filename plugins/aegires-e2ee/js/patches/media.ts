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
import { storage } from "../storage.ts";
import { unscoped } from "../host.ts";
import { base64Encode } from "../crypto/base64.ts";
import { MAX_FILE_BYTES, extOf, imageMimeFor, isEncryptedFileName, mimeFor, originalFileName, rawCdnUrl } from "../crypto/files.ts";
import { getReceiveKeysV2 } from "../state.ts";
import { attachmentsEnabled } from "../features.ts";
import { cacheDir, nativeMediaInfo, nativeMediaThumb, nativeOpenFile, nativeReady, removePath } from "../engine/engine.ts";
import { diag } from "../diag.ts";

const log = (...a: any[]) => { if (storage.debug) console.log("[aegires-e2ee:media]", ...a); };

type Kind = "image" | "video" | "audio";
/** Something the native player can play: the absolute path of the decrypted file and a title for audio. */
export interface PlayableMedia { path: string; title: string }
interface Decrypted { uri: string; name: string; mime: string; size: number; w: number; h: number; kind: Kind; thumb?: string; title: string }

const done = new Map<string, Decrypted>(); // attachment id -> decrypted file
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

/** What the audio card and the player call a file: its name without the extension, unless that is just random hex. */
function titleFor(name: string, kind: Kind): string {
  const stem = name.replace(/\.[^./]+$/, "");
  return !stem || /^[0-9a-f]{16,}$/i.test(stem) ? (kind === "video" ? "Video" : "Audio") : stem;
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

// ---------------------------------------------------------------- entry points
export function handleAttachments(m: any, channelId: string | undefined, authorId: string | undefined, tag: { w: number; h: number } | null = null): void {
  if (!attachmentsEnabled(storage.attachmentsExp) || !nativeReady() || !Array.isArray(m.attachments)) return;
  if (m.id) msgAtts.set(String(m.id), m.attachments.map((a: any) => idOf(a)));
  const pending: Array<Promise<void>> = [];
  let tagUsed = false;
  for (const a of m.attachments) {
    if (!isEncAttachment(a)) continue;
    const orig = originalFileName(String(a.filename));
    const mime = mimeFor(orig);
    if (!imageMimeFor(orig) && !mime.startsWith("video/") && !mime.startsWith("audio/")) continue;
    const id = idOf(a);
    if (!id) continue;
    const hit = done.get(id);
    if (hit) { apply(a, hit); continue; }
    // The sender's [sz:W,H] tag (first picture/video only) lets Discord reserve the right space
    // before the file is downloaded; the decrypted file's real size replaces it afterwards.
    if (tag && !tagUsed) { a.width = tag.w; a.height = tag.h; tagUsed = true; }
    if (failed.has(id) || queued.has(id)) continue;
    const keys = getReceiveKeysV2(channelId);
    if (!channelId || !authorId || !keys.length) continue;
    diag.attSeen++;
    queued.add(id);
    const snapshot = { ...a };
    pending.push(enqueue(async () => {
      try {
        done.set(id, await decryptOne(snapshot, id, keys, channelId, authorId));
        diag.attDecrypted++;
      } catch (e) {
        failed.add(id);
        diag.attFailed++;
        diag.lastAttError = String((e as any)?.message ?? e).slice(0, 80);
        log("decrypt failed", id, e);
      } finally {
        queued.delete(id);
      }
    }));
  }
  if (!pending.length) return;
  // When this message's jobs are done, push the rewritten attachments into the message store.
  const msgId = m.id;
  const chan = m.channel_id ?? channelId;
  const atts: any[] = m.attachments.map((a: any) => ({ ...a }));
  Promise.all(pending).then(() => {
    let changed = false;
    for (const a of atts) {
      const d = isEncAttachment(a) ? done.get(idOf(a)) : undefined;
      if (d) { apply(a, d); changed = true; }
    }
    if (changed && msgId && chan) dispatch({ type: "MESSAGE_UPDATE", message: { id: msgId, channel_id: chan, attachments: atts } });
  });
}

/** Forgets everything and deletes the decrypted files (plugin start and stop). */
export function clearMedia(): void {
  jobs.length = 0;
  done.clear();
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
