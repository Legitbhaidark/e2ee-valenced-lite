// GIF / media links -> ENCRYPTED FILES. Work in progress: this step is a DRY RUN, dev builds only.
//
// Picking a GIF in Discord sends its Tenor page link as the message text. In an encrypted chat the
// link travels encrypted, but the receiver only gets a link: no preview, and opening it contacts
// Tenor. The goal is to download the GIF on THIS phone, encrypt it, and send it as an encrypted
// attachment instead.
//
// The dry run sends the message exactly as before and, in the background, resolves the link,
// downloads the file and encrypts it, so Diagnostics shows which of those stages works. Nothing is
// attached or sent, and both temporary files are deleted afterwards. It also records the shape of
// what Discord hands over for a GIF pick and for an attached file: the attach step needs both.
import { DEV_BUILD } from "../about.ts";
import { storage } from "../storage.ts";
import { diag } from "../diag.ts";
import { base64Encode } from "../crypto/base64.ts";
import { getSendKeyV2, getSendPreset, isEncryptionOn } from "../state.ts";
import { getSelfId } from "../self.ts";
import { cacheDir, nativeFetchMedia, nativeReady, nativeSealFile, removePath, type FileSeal } from "../engine/engine.ts";

const DRY_MAX_BYTES = 50 * 1024 * 1024;

const GIF_HOST = /^(?:(?:www\.)?(?:tenor|giphy)\.com|(?:media\d*|c|i)\.(?:tenor|giphy)\.com|i\.imgur\.com)$/i;

/** The link when the whole text is ONE https link to a GIF site (what the GIF picker sends), else null. */
export function bareMediaUrl(text: unknown): string | null {
  if (typeof text !== "string") return null;
  const t = text.trim().replace(/^<(.*)>$/, "$1");
  if (t.length > 1000 || !/^https:\/\/\S+$/.test(t)) return null;
  const m = /^https:\/\/([^/?#:@\s]+)/i.exec(t);
  return m && GIF_HOST.test(m[1]) ? t : null;
}

/** Outline of an object's keys a few levels deep ("CloudUpload:item{file{uri,name}}"), for Diagnostics. Values are never shown. */
export function shapeOf(o: any, depth = 3): string {
  if (!o || typeof o !== "object") return typeof o;
  if (Array.isArray(o)) return `[${o.length ? shapeOf(o[0], depth - 1) : ""}]`;
  const ctor = o.constructor && o.constructor.name && o.constructor.name !== "Object" ? `${o.constructor.name}:` : "";
  const keys = Object.keys(o).slice(0, 24);
  const body = keys
    .map((k) => {
      const v = o[k];
      return depth > 0 && v && typeof v === "object" ? `${k}{${shapeOf(v, depth - 1)}}` : k;
    })
    .join(",");
  return (ctor + body).slice(0, 300);
}

const fmtMB = (n: number) => (n / 1048576).toFixed(2) + " MB";
const note = (s: string) => { diag.installed["gif dry run"] = s.slice(0, 420); };

let busy = false;

async function dryRun(url: string, channelId: string): Promise<void> {
  if (busy) return; // one at a time
  busy = true;
  const t0 = Date.now();
  const files: string[] = [];
  try {
    if (!nativeReady()) return note("native half not ready");
    const key = getSendKeyV2(channelId);
    const self = getSelfId();
    if (!key || !self) return note("no v2 key or own user id");
    note("resolving and downloading ...");
    const dir = `${await cacheDir()}/aegires/dl`;
    await removePath(dir); // leftovers of an earlier run
    const got = await nativeFetchMedia(url, `${dir}/g${Date.now().toString(36)}`, DRY_MAX_BYTES);
    files.push(got.path);
    note(`downloaded ${got.mime} ${fmtMB(got.size)} via ${got.how} (${got.ms} ms), encrypting ...`);
    const seal: FileSeal = { master: base64Encode(key), preset: getSendPreset(channelId).index, ch: channelId, sender: self };
    const sealedPath = `${dir}/s${Date.now().toString(36)}.bin`;
    files.push(sealedPath);
    const size = await nativeSealFile(seal, got.path, sealedPath, DRY_MAX_BYTES);
    note(`OK ${got.mime} ${fmtMB(got.size)} -> encrypted ${fmtMB(size)} in ${Date.now() - t0} ms | picked ${got.how} ${got.url.slice(0, 90)} | found: ${got.found}`);
  } catch (e) {
    note(`FAILED after ${Date.now() - t0} ms: ${String((e as any)?.message ?? e).slice(0, 300)}`);
  } finally {
    busy = false;
    for (const f of files) void removePath(f); // plaintext and ciphertext are both deleted
  }
}

/** Called for every outgoing message in dev builds: records what a GIF pick looks like and runs the dry run. */
export function probeGifSend(channelId: string, message: any, options: any): void {
  if (!DEV_BUILD || !storage.gifDryRun) return;
  const url = bareMediaUrl(message?.content);
  if (!url || !isEncryptionOn(channelId)) return;
  diag.installed["gif send"] = `message{${shapeOf(message, 1)}} options{${shapeOf(options, 1)}} link ${url.slice(0, 70)}`.slice(0, 420);
  void dryRun(url, channelId);
}
