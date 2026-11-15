// Shared encryption steps for everything we send: message text and attachments.
//
// Rule #1 is FAIL CLOSED: if encryption is switched on for a channel and anything goes wrong
// (no key, text too long, no secure RNG, unreadable file), we do NOT send. Silently sending
// plaintext to a channel where the user believes encryption is on would be the worst bug.
import { notify } from "./notify.ts";
import { RN } from "./rt.ts";
import { MAX_PLAINTEXT_LEN, encryptBytes, encryptText, looksEncrypted } from "./crypto/wire.ts";
import { MAX_FILE_BYTES, encryptedFileName, extOf, imageDims, imageMimeFor, makeSizeTag, mimeFor, videoDims } from "./crypto/files.ts";
import { storage } from "./storage.ts";
import { encryptFile, encryptMessage, maxPlaintextBytes, utf8Length } from "./crypto/v2.ts";
import { getSendFormat, getSendKey, getSendKeyV2, getSendPreset, isEncryptionOn } from "./state.ts";
import { getSelfId } from "./self.ts";
import { readUri, readUriBase64, tempName, writeCacheFile, writeCacheFileBase64 } from "./native.ts";
import { base64Decode, base64Encode } from "./crypto/base64.ts";
import { cacheDir, nativeMediaInfo, nativeRandomName, nativeReady, nativeSealBytes, nativeSealFile, type FileSeal } from "./engine/engine.ts";
import { diag } from "./diag.ts";

export const BLOCK = Symbol("block");

/**
 * Returns the new content, null to leave the message alone, or BLOCK to cancel the send.
 * `force` encrypts even where encryption is off (edits of encrypted messages, captions).
 */
export function prepare(channelId: string, content: unknown, force = false): string | null | typeof BLOCK {
  if (typeof content !== "string" || content.length === 0) return null;
  if (looksEncrypted(content)) return null; // already encrypted (e.g. our own re-send)
  if (!force && !isEncryptionOn(channelId)) return null;

  try {
    if (getSendFormat(channelId) === "v2") {
      const key = getSendKeyV2(channelId);
      if (!key) {
        notify("E2EE is on for this chat but no v2 key is set (set the password again in the plugin settings) - message not sent");
        return BLOCK;
      }
      const self = getSelfId();
      if (!self) {
        notify("Your Discord user id is not known yet, so the message cannot be encrypted - not sent. Try again in a moment.");
        return BLOCK;
      }
      const preset = getSendPreset(channelId);
      const max = maxPlaintextBytes(preset);
      const len = utf8Length(content);
      if (len > max) {
        notify(`Too long to encrypt with ${preset.label} (${len}/${max} bytes) - message not sent`);
        return BLOCK;
      }
      return encryptMessage(content, key, preset, channelId, self, { padme: storage.padme !== false });
    }
    const key = getSendKey(channelId);
    if (!key) {
      notify("E2EE is on for this chat but no key is set - message not sent");
      return BLOCK;
    }
    if (content.length > MAX_PLAINTEXT_LEN) {
      notify(`Too long to encrypt (${content.length}/${MAX_PLAINTEXT_LEN}) - message not sent`);
      return BLOCK;
    }
    return encryptText(content, key);
  } catch (e) {
    notify("Encryption failed - message not sent");
    console.log("[aegires-e2ee] encrypt error", e);
    return BLOCK;
  }
}

// ---------------------------------------------------------------- attachments

const ENC_MIME = "application/octet-stream";

function imageSize(uri: string): Promise<{ w: number; h: number } | null> {
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve(null), 3000);
    try {
      RN.Image.getSize(uri, (w: number, h: number) => { clearTimeout(t); resolve({ w, h }); }, () => { clearTimeout(t); resolve(null); });
    } catch { clearTimeout(t); resolve(null); }
  });
}

/** The object holding the local file info ({ uri, filename/name, mimeType }) of an upload. */
function descriptorOf(u: any): any | null {
  for (const c of [u?.item?.file, u?.item, u?.file, u]) {
    if (c && typeof c === "object" && typeof c.uri === "string") return c;
  }
  return null;
}

/** Sets only fields the object already has (we must not invent a shape Discord doesn't use). */
function setExisting(o: any, fields: Record<string, any>): void {
  for (const [k, v] of Object.entries(fields)) {
    if (o && k in o) {
      try { o[k] = v; } catch {}
    }
  }
}

/** Encrypted temp file uri -> image size of the original (files encrypted when attached). */
const sizes = new Map<string, { w: number; h: number } | null>();

/** What the native file sealing needs: the v2 master key (base64), preset, channel and our user id. */
type Seal = FileSeal;

type Dims = { w: number; h: number };
type MediaKind = "image" | "video";

/** Plain file path of a file:// uri or an absolute path; null for content:// and the like. */
function localPath(uri: string): string | null {
  if (uri.startsWith("/")) return uri;
  if (!uri.startsWith("file://")) return null;
  const raw = uri.slice(7);
  try { return decodeURIComponent(raw); } catch { return raw; }
}

/** Size as Android's own decoders report it (EXIF / video rotation applied). Null if unknown. */
async function probeLocal(src: string, kind: MediaKind): Promise<Dims | null> {
  const path = localPath(src) ?? (src.startsWith("content://") ? src : null); // native reads content:// itself
  if (!path || !nativeReady()) return null;
  try { return await nativeMediaInfo(path, kind); } catch { return null; }
}

/** Size parsed from file bytes JS already read. Images need only the header; videos the whole file. */
function dimsFromBase64(b64: string, kind: MediaKind): Dims | null {
  try {
    const bytes = base64Decode(kind === "image" ? b64.slice(0, 131072) : b64);
    if (!bytes) return null;
    return kind === "image" ? imageDims(bytes) : videoDims(bytes);
  } catch {
    return null;
  }
}

/** Last-resort path that loads the whole file into JS: small files only. Native streams everything else. */
const FALLBACK_MAX_BYTES = 32 * 1024 * 1024;

/** Size parsed in JS from the file header (works for PNG/JPEG+EXIF/GIF/WebP/BMP and MP4). Small files only. */
async function jsDims(src: string, kind: MediaKind): Promise<Dims | null> {
  try { return dimsFromBase64(await readUriBase64(src, 24 * 1024 * 1024), kind); } catch { return null; }
}

/** Seals the file natively into <cache>/aegires/. Resolves to the new file:// uri, its size and, when asked, the plaintext's pixel size. */
async function sealNative(src: string, seal: Seal, want: MediaKind | null = null): Promise<{ uri: string; size: number; dims: Dims | null }> {
  if (!nativeReady()) throw new Error("native module not available");
  const out = `${await cacheDir()}/aegires/${tempName("bin")}`;
  let size: number;
  let dims: Dims | null = null;
  try {
    size = await nativeSealFile(seal, src, out, MAX_FILE_BYTES);
  } catch (e) {
    if (String((e as any)?.message ?? e).includes("file too large")) throw e;
    // Java cannot open some uris itself (content://): read the bytes here, seal them natively.
    const b64 = await readUriBase64(src, FALLBACK_MAX_BYTES);
    if (want) dims = dimsFromBase64(b64, want);
    size = await nativeSealBytes(seal, b64, out, FALLBACK_MAX_BYTES);
  }
  return { uri: "file://" + out, size, dims };
}

/** Best-effort file name of an upload object (Discord's shape differs between places). */
export function uploadFileName(u: any): string {
  const d = descriptorOf(u);
  return String(u?.filename ?? d?.filename ?? d?.name ?? u?.name ?? "");
}

/** Same, by the random encrypted file name: survives Discord copying the file to another uri. */
const sizesByName = new Map<string, { w: number; h: number } | null>();

/** "[sz:W,H]" prefix for the caption, from the first already-encrypted image among the uploads. */
export function captionSizeTag(files: any[]): string {
  for (const u of files) {
    const d = descriptorOf(u);
    const s = (d ? sizes.get(d.uri) : null) ?? sizesByName.get(uploadFileName(u)) ?? null;
    if (s) return makeSizeTag(s.w, s.h);
  }
  return "";
}

/** Encrypts one upload in place; returns the image size for the caption tag, if any. */
async function encryptUpload(u: any, seal: Seal): Promise<{ w: number; h: number } | null> {
  const d = descriptorOf(u);
  if (!d) throw new Error("unknown upload shape: " + Object.keys(u ?? {}).join(","));
  const name: string = d.filename ?? d.name ?? u.filename ?? "file";
  if (sizes.has(d.uri)) return sizes.get(d.uri) ?? null; // already encrypted when attached
  const mime: string = d.mimeType ?? d.type ?? u.mimeType ?? "";
  const src: string = d.originalUri ?? d.uri;

  const kind: MediaKind | null = mime.startsWith("video/") || mimeFor(name).startsWith("video/") ? "video" : mime.startsWith("image/") || imageMimeFor(name) ? "image" : null;
  // Pixel size for the "[sz:W,H]" tag. Aegires sizes the picture ONLY from this tag (Discord gives
  // it no width/height for an encrypted file), so a missing or wrong size shows up there as black
  // bars. Several sources are tried, the most exact (Android's decoders, EXIF applied) first.
  let size: Dims | null = null;
  let how = "";
  if (kind) {
    size = await probeLocal(src, kind);
    if (size) how = "android decoder";
    if (!size && d.uri !== src) {
      size = await probeLocal(d.uri, kind);
      if (size) how = "android decoder (copy)";
    }
  }
  const sealed = await sealNative(src, seal, kind && !size ? kind : null);
  if (!size && sealed.dims) { size = sealed.dims; how = "file bytes"; }
  if (!size && kind) {
    size = await jsDims(src, kind);
    if (size) how = "file header";
  }
  if (!size && kind === "image") {
    size = await imageSize(src);
    if (size) how = "react native";
  }
  diag.installed["last upload size"] = kind
    ? size ? `${kind} ${size.w}x${size.h} via ${how}` : `${kind} - NO SIZE FOUND (Aegires will show black bars)`
    : "not an image or video";
  const tmp = sealed.uri;
  const encSize = sealed.size;
  const encName = await nativeRandomName(name, mime);

  setExisting(d, { uri: tmp, originalUri: tmp, filename: encName, name: encName, mimeType: ENC_MIME, type: ENC_MIME, contentType: ENC_MIME, size: encSize, fileSize: encSize });
  // Never let Discord re-compress the ciphertext as if it were a photo or video.
  if (d !== u) setExisting(u, { filename: encName, mimeType: ENC_MIME, isImage: false, isVideo: false, currentSize: encSize, preCompressionSize: encSize });
  if (d.uri !== tmp) throw new Error("could not swap upload uri");
  if ((d.filename ?? d.name ?? u.filename) !== encName) throw new Error("could not swap file name");
  sizes.set(tmp, size);
  sizesByName.set(encName, size);
  if (sizes.size > 200) sizes.delete(sizes.keys().next().value as string);
  if (sizesByName.size > 200) sizesByName.delete(sizesByName.keys().next().value as string);
  diag.attEncrypted++;
  return size;
}

/** Encrypts files in place as they are attached (no caption yet). Resolves false on failure. */
export function encryptAttachments(channelId: string, files: any[]): Promise<boolean> {
  return encryptOutgoing(channelId, files, []);
}

/**
 * Encrypts every upload in place, then the caption(s) with the "[sz:W,H]" tag of the first
 * image. Resolves true when it is safe to send; on any failure tells the user and resolves
 * false (the caller must then NOT send).
 */
export async function encryptOutgoing(channelId: string, uploads: any[], captions: any[]): Promise<boolean> {
  if (getSendFormat(channelId) !== "v2") {
    notify("Attachments need the v2 format in this build - upload cancelled. Switch this chat to v2 in the plugin settings.");
    return false;
  }
  // v2 file format: per-file HKDF key bound to (channel, sender), 64 KiB authenticated chunks.
  const key = getSendKeyV2(channelId);
  const self = getSelfId();
  if (!key || !self) {
    notify(key ? "Your Discord user id is not known yet - upload cancelled. Try again in a moment." : "E2EE is on for this chat but no v2 key is set - upload cancelled");
    return false;
  }
  const seal: Seal = { master: base64Encode(key), preset: getSendPreset(channelId).index, ch: channelId, sender: self };
  try {
    let tag = "";
    for (const u of uploads) {
      const s = await encryptUpload(u, seal);
      if (s && !tag) tag = makeSizeTag(s.w, s.h);
    }
    for (const holder of captions) {
      if (!holder || typeof holder.content !== "string") continue;
      const out = prepare(channelId, tag + holder.content, true);
      if (out === BLOCK) return false;
      if (out !== null) holder.content = out;
    }
    return true;
  } catch (e) {
    diag.lastAttError = String((e as any)?.message ?? e).slice(0, 80);
    console.log("[aegires-e2ee] attachment encrypt failed", e);
    notify("Attachment encryption failed - upload cancelled (" + diag.lastAttError + ")");
    return false;
  }
}
