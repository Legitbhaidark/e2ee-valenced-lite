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
// Images, GIFs and videos. Decrypted files are deleted when the plugin starts and when it stops.
import { storage } from "../storage.ts";
import { unscoped } from "../host.ts";
import { base64Encode } from "../crypto/base64.ts";
import { MAX_FILE_BYTES, extOf, imageMimeFor, isEncryptedFileName, mimeFor, originalFileName, rawCdnUrl } from "../crypto/files.ts";
import { getReceiveKeysV2 } from "../state.ts";
import { attachmentsEnabled } from "../features.ts";
import { cacheDir, nativeMediaInfo, nativeOpenFile, nativeReady, removePath } from "../engine/engine.ts";
import { diag } from "../diag.ts";

const log = (...a: any[]) => { if (storage.debug) console.log("[aegires-e2ee:media]", ...a); };

interface Decrypted { uri: string; name: string; mime: string; size: number; w: number; h: number }

const done = new Map<string, Decrypted>(); // attachment id -> decrypted file
const failed = new Set<string>(); // ids that did not decrypt (no retry until the plugin restarts)
const queued = new Set<string>();
const msgAtts = new Map<string, string[]>(); // message id -> its attachment ids (for tap events)

/** Absolute path of the decrypted video a tap refers to: by attachment ids, or message id + index. */
export function decryptedVideoPath(ids: string[], messageId?: string, index?: number): string | null {
  const vid = (d?: Decrypted) => (d && d.mime.startsWith("video/") ? d.uri.replace(/^file:\/\//, "") : null);
  for (const id of ids) {
    const p = vid(done.get(id));
    if (p) return p;
  }
  const list = messageId ? msgAtts.get(messageId) : undefined;
  if (!list) return null;
  if (index != null) return vid(done.get(list[index] ?? ""));
  const all = list.map((id) => vid(done.get(id))).filter((p): p is string => !!p);
  return all.length === 1 ? all[0] : null;
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
  if (d.mime.startsWith("video/")) {
    // Discord's inline player streams only over http(s) and fails on file://. Declared as an
    // image, the chat draws the video's first frame instead (its image loader reads local
    // videos). Playing it on tap is a separate step.
    a.content_type = "image/jpeg";
    a.filename = d.name + ".jpg";
  }
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
  const video = mime.startsWith("video/");
  const info = await nativeMediaInfo(out, video ? "video" : "image").catch(() => null);
  return { uri: "file://" + out, name, mime, size, w: info?.w ?? (Number(a.width) || (video ? 640 : 480)), h: info?.h ?? (Number(a.height) || (video ? 360 : 360)) };
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
    if (!imageMimeFor(orig) && !mimeFor(orig).startsWith("video/")) continue;
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
  purged = (async () => {
    try {
      await removePath(`${await cacheDir()}/aegires/in`);
    } catch {
      // The native bridge may not be up yet; nothing to delete then.
    }
  })();
}
