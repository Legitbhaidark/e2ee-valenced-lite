// Faster crypto from the NATIVE half of this plugin (Kotlin/Java, src/main), reached through
// Valenced's native bridge. Right now it does one job: Argon2id, the slow step that turns your
// password into a key (64 MiB of memory work, done once per password and then cached).
//
// There is no JS Argon2id any more: if the native half is not installed, fails its self-check or
// errors, deriving a key fails with a clear message (stored keys keep working). The WebView engine
// of the Kettu version is gone; the old engine functions below stay as inert stand-ins until attachments are ported.
import { storage } from "../storage.ts";
import { unscoped } from "../host.ts";
import { getArgonEngine, setArgon2Offload } from "../crypto/kdf2.ts";
import { base64Decode, base64Encode } from "../crypto/base64.ts";
import { diag } from "../diag.ts";
import { AES256, decryptMessage, encryptMessage } from "../crypto/v2.ts";

/** Method namespace: must match the names registered in src/main/kotlin/e2ee/lite/LiteNative.kt */
const NS = "e2eelite";

// Known answer made by an independent Argon2id (Python `cryptography`), at a size small enough to
// run in milliseconds. If the native result differs, the native half is NOT used: a wrong key
// would silently make every message undecryptable.
const SELF_CHECK = {
  password: "aegires native check",
  salt: "dcenc2|selfcheck",
  passes: 2,
  memoryKiB: 64,
  outLen: 32,
  expectedHex: "9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8",
};

const PING_TIMEOUT_MS = 3000;
const ARGON_TIMEOUT_MS = 30000;

let nativeOk = false;

const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s);
const hex = (b: Uint8Array): string => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");

function withTimeout<T>(p: Promise<T>, ms: number, what: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(what + " timed out")), ms);
    p.then(
      (v) => { clearTimeout(t); resolve(v); },
      (e) => { clearTimeout(t); reject(e); },
    );
  });
}

function callNative(method: string, args: string[], timeoutMs: number): Promise<unknown> {
  const call = unscoped().modules?.native?.callNativeMethod;
  if (typeof call !== "function") return Promise.reject(new Error("native bridge not available"));
  return withTimeout(Promise.resolve(call(`${NS}.${method}`, args)), timeoutMs, method);
}

async function nativeArgon2(password: Uint8Array, salt: Uint8Array, t: number, mKiB: number, len: number): Promise<Uint8Array | null> {
  // Developer option: a fixed engine ("c" or "java") goes through argon2with; auto keeps the original call.
  const engine = getArgonEngine();
  const fixed = engine === "c" || engine === "java";
  const base = [base64Encode(password), base64Encode(salt), String(t), String(mKiB), String(len)];
  const out = await callNative(fixed ? "argon2with" : "argon2id", fixed ? [engine, ...base] : base, ARGON_TIMEOUT_MS);
  return typeof out === "string" ? base64Decode(out) : null;
}

/** Real-size derivation: records success or the exact failure in Diagnostics. */
async function nativeArgon2Tracked(password: Uint8Array, salt: Uint8Array, t: number, mKiB: number, len: number): Promise<Uint8Array | null> {
  try {
    const out = await nativeArgon2(password, salt, t, mKiB, len);
    if (!out) throw new Error("native returned no data");
    diag.engineJobs++;
    diag.engineLastError = "";
    return out;
  } catch (e) {
    diag.engineFallbacks++;
    diag.engineLastError = String((e as any)?.message ?? e).split("\n")[0].slice(0, 160);
    diag.installed["native crypto"] = "FAILED at real size (" + diag.engineLastError + ")";
    return null;
  }
}

const V2_TIMEOUT_MS = 10000;
const FILE_TIMEOUT_MS = 30 * 60 * 1000; // a 1 GiB file on a slow connection takes a while

/** Native method outside our namespace (Valenced's own file-system methods). */
function callRaw(name: string, args: string[], timeoutMs: number): Promise<unknown> {
  const call = unscoped().modules?.native?.callNativeMethod;
  if (typeof call !== "function") return Promise.reject(new Error("native bridge not available"));
  return withTimeout(Promise.resolve(call(name, args)), timeoutMs, name);
}

/** Discord's cache directory (absolute path, no trailing slash), from Valenced's fs API. */
export async function cacheDir(): Promise<string> {
  const c: any = await callRaw("valenced.fs.getConstants", [], 5000);
  const dir = String(c?.cache ?? "").replace(/\/+$/, "");
  if (!dir.startsWith("/")) throw new Error("unknown cache directory");
  return dir;
}

export interface FileSeal { master: string; preset: number; ch: string; sender: string }

/** Encrypts a local file (path or file:// uri) into outPath with the Java v3 file format. Resolves to the encrypted size. */
export async function nativeSealFile(s: FileSeal, src: string, outPath: string, maxBytes: number): Promise<number> {
  const out = await callNative("sealfile", [s.master, String(s.preset), s.ch, s.sender, src, outPath, String(maxBytes)], FILE_TIMEOUT_MS);
  const n = Number(out);
  if (!Number.isFinite(n)) throw new Error("native returned no size");
  return n;
}

/** Same, for plaintext that JS already read (base64). */
export async function nativeSealBytes(s: FileSeal, plainB64: string, outPath: string, maxBytes: number): Promise<number> {
  const out = await callNative("sealbytes", [s.master, String(s.preset), s.ch, s.sender, plainB64, outPath, String(maxBytes)], FILE_TIMEOUT_MS);
  const n = Number(out);
  if (!Number.isFinite(n)) throw new Error("native returned no size");
  return n;
}

/** Random upload name "<20 hex>.enc.<ext>" (Java FileNames). */
export async function nativeRandomName(original: string, mime: string): Promise<string> {
  const out = await callNative("randomname", [original, mime], 5000);
  if (typeof out !== "string" || !/^[0-9a-f]{20}\.enc(\.[a-z0-9]{1,5})?$/.test(out)) throw new Error("native returned a bad name");
  return out;
}

/** Downloads a Discord CDN https url (or reads a local file) and decrypts it into outPath. Resolves to the plaintext size. */
export async function nativeOpenFile(master: string, ch: string, sender: string, src: string, outPath: string, maxBytes: number): Promise<number> {
  const out = await callNative("openfile", [master, ch, sender, src, outPath, String(maxBytes)], FILE_TIMEOUT_MS);
  const n = Number(out);
  if (!Number.isFinite(n)) throw new Error("native returned no size");
  return n;
}

/** Pixel size of a decrypted image or video file, from Android's decoders. Null when unknown. */
export async function nativeMediaInfo(path: string, kind: "image" | "video"): Promise<{ w: number; h: number } | null> {
  const out = await callNative("mediainfo", [path, kind], 10000);
  const m = typeof out === "string" ? /^(\d+),(\d+)$/.exec(out) : null;
  return m ? { w: Number(m[1]), h: Number(m[2]) } : null;
}

/** Opens the native full-screen player on a decrypted video or audio file. The title is shown for audio. */
export async function nativePlayMedia(path: string, title: string, inline = false, w = 0, h = 0): Promise<void> {
  await callNative("playmedia", [path, title, inline ? "inline" : "full", String(Math.round(w) || 0), String(Math.round(h) || 0)], 5000);
}

/** What the in-chat player did for the last tap (Diagnostics): "inline" or why it fell back to full screen. */
export async function nativeInlineStatus(): Promise<string> {
  const out = await callNative("inlinestatus", [], 3000);
  return typeof out === "string" ? out : String(out ?? "");
}

/** Starts noting where the screen is touched (needed to find the picture a video plays on). */
export async function nativeTouchSpy(): Promise<void> {
  await callNative("touchspy", [], 3000);
}

/**
 * Writes the chat picture of a decrypted video (first frame + play button + length) or audio file
 * (a card with title and length) to outPath as a JPEG. Resolves to the picture's pixel size.
 */
export async function nativeMediaThumb(kind: "video" | "audio", src: string, outPath: string, label: string): Promise<{ w: number; h: number }> {
  const out = await callNative("mediathumb", [kind, src, outPath, label], 60000);
  const m = typeof out === "string" ? /^(\d+),(\d+)$/.exec(out) : null;
  if (!m || !Number(m[1]) || !Number(m[2])) throw new Error("native returned no picture size");
  return { w: Number(m[1]), h: Number(m[2]) };
}

/** Fraction (0-100) of the running decryption into outPath, or null when none is running / the size is unknown. */
export async function nativeProgress(outPath: string): Promise<number | null> {
  const out = await callNative("progress", [outPath], 3000);
  const m = typeof out === "string" ? /^(\d+),(-?\d+)$/.exec(out) : null;
  if (!m) return null;
  const read = Number(m[1]);
  const total = Number(m[2]);
  return total > 0 ? Math.max(0, Math.min(100, Math.floor((read / total) * 100))) : null;
}

/** Sets the header "E2EE" button (native overlay): "on" | "off" | "locked" | "hide". */
export async function nativeEncButton(state: "on" | "off" | "locked" | "hide"): Promise<void> {
  await callNative("encbutton", [state], 3000);
}

/** A short native toast (Android, not Discord's: that one can crash the app). */
export async function nativeToast(text: string): Promise<void> {
  await callNative("toast", [text], 3000);
}

/** Why the header button is shown or hidden (Diagnostics). */
export async function nativeEncButtonStatus(): Promise<string> {
  const out = await callNative("encbuttonstatus", [], 3000);
  return typeof out === "string" ? out : String(out ?? "");
}

/** Native settings screen (SettingsScreen.kt): open it with the current values, change them while open, poll what the user did. */
export async function nativeSettingsOpen(stateJson: string): Promise<void> {
  await callNative("settingsopen", [stateJson], 5000);
}

export async function nativeSettingsUpdate(partialJson: string): Promise<void> {
  await callNative("settingsupdate", [partialJson], 3000);
}

export async function nativeSettingsTake(): Promise<{ open: boolean; events: any[] }> {
  const out = await callNative("settingstake", [], 3000);
  const r = JSON.parse(typeof out === "string" ? out : "{}");
  return { open: !!r.open, events: Array.isArray(r.events) ? r.events : [] };
}

/** Presses of the header button since the last call. */
export async function nativeEncButtonTaps(): Promise<number> {
  const out = await callNative("encbuttontaps", [], 3000);
  const n = typeof out === "string" ? parseInt(out, 10) : Number(out);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/** Writes the "not decrypted yet" picture (state "tap" or "work") for a file of the given kind; resolves to its pixel size. */
export async function nativePlaceholder(kind: "image" | "video" | "audio", state: "tap" | "work", percent: number | null, sizeBytes: number, outPath: string): Promise<{ w: number; h: number }> {
  const out = await callNative("placeholder", [kind, state, String(percent ?? -1), String(Math.max(0, Math.floor(sizeBytes))), outPath], 15000);
  const m = typeof out === "string" ? /^(\d+),(\d+)$/.exec(out) : null;
  if (!m || !Number(m[1]) || !Number(m[2])) throw new Error("native returned no picture size");
  return { w: Number(m[1]), h: Number(m[2]) };
}

/** Deletes a file or folder through Valenced's fs method. Never rejects. */
export async function removePath(path: string): Promise<void> {
  try {
    await callRaw("valenced.fs.delete", [path], 5000);
  } catch {
    // Nothing to do if it cannot be deleted.
  }
}

/** v2 message encrypt through the Java Crypto (copied from Aegires). Rejects on any failure. */
export async function nativeV2Encrypt(master: Uint8Array, preset: number, channelId: string, senderId: string, text: string): Promise<string> {
  const out = await callNative("v2encrypt", [base64Encode(master), String(preset), channelId, senderId, base64Encode(utf8(text))], V2_TIMEOUT_MS);
  if (typeof out !== "string") throw new Error("native returned no data");
  return out;
}

/** v2 message decrypt through the Java Crypto. Rejects on any failure (wrong key, channel, sender, tampering). */
export async function nativeV2Decrypt(master: Uint8Array, channelId: string, senderId: string, wire: string): Promise<string> {
  const out = await callNative("v2decrypt", [base64Encode(master), channelId, senderId, wire], V2_TIMEOUT_MS);
  const bytes = typeof out === "string" ? base64Decode(out) : null;
  if (!bytes) throw new Error("native returned no data");
  return new TextDecoder().decode(bytes);
}

/**
 * Cross-check of the two implementations of the same format: what the JS code encrypts, the Java
 * code must decrypt, and the other way round. Shown in Diagnostics; it never switches anything off.
 */
async function checkV2(): Promise<void> {
  const master = Uint8Array.from({ length: 32 }, (_, i) => i);
  const ch = "123456789012345678";
  const me = "876543210987654321";
  const text = "h\u00e9llo \u00a7 \u2713 self-check";
  try {
    const fromJs = encryptMessage(text, master, AES256, ch, me);
    if ((await nativeV2Decrypt(master, ch, me, fromJs)) !== text) throw new Error("Java could not read JS output");
    const fromJava = await nativeV2Encrypt(master, AES256.index, ch, me, text);
    if (decryptMessage(fromJava, master, ch, me) !== text) throw new Error("JS could not read Java output");
    diag.installed["native v2 crypto"] = "ok (Java Crypto and JS agree)";
  } catch (e) {
    diag.installed["native v2 crypto"] = "not used (" + String((e as any)?.message ?? e).split("\n")[0].slice(0, 120) + ")";
  }
}

/** Which Argon2id the native half runs. The C library loads a moment after start, so ask a few times. */
async function readArgon2Engine(): Promise<string> {
  let last = "Java";
  for (let i = 0; i < 4; i++) {
    try {
      const r = await callNative("argon2engine", [], PING_TIMEOUT_MS);
      if (typeof r !== "string") return last;
      if (r.startsWith("c:")) return "C library (" + r.slice(2) + ")";
      const why = r.replace(/^java:/, "");
      last = "Java (" + why + ")";
      if (!/not loaded yet/.test(why)) return last;
    } catch {
      return "Java (old native half, no C library)";
    }
    await new Promise((res) => setTimeout(res, 400));
  }
  return last;
}

/** Looks for the native half, checks it, and if it passes uses it for Argon2id. Never throws. */
export async function startNative(): Promise<boolean> {
  nativeOk = false;
  let why = "";
  try {
    const pong = await callNative("ping", [], PING_TIMEOUT_MS);
    if (pong !== "pong") throw new Error("unexpected ping reply");
    const c = SELF_CHECK;
    const got = await nativeArgon2(utf8(c.password), utf8(c.salt), c.passes, c.memoryKiB, c.outLen);
    if (!got || hex(got) !== c.expectedHex) throw new Error("self-check mismatch - native result is wrong");
    nativeOk = true;
    void nativeTouchSpy().catch(() => {}); // the UI is up by now, so the window can be wrapped
    diag.argon2Engine = await readArgon2Engine();
    void checkV2();
  } catch (e) {
    // First line only (a native failure carries a whole Java stack trace), long enough to name the exception.
    why = String((e as any)?.message ?? e).split("\n")[0].slice(0, 160);
  }
  diag.engine = nativeOk ? "native (" + diag.argon2Engine + ")" : "native unavailable (no Argon2id)";
  setArgon2Offload(nativeOk ? nativeArgon2Tracked : null, why);
  diag.installed["native crypto"] = nativeOk ? "ok (Argon2id: " + diag.argon2Engine + ", self-checked)" : "UNAVAILABLE - keys cannot be derived (" + why + ")";
  return nativeOk;
}

export function stopNative(): void {
  nativeOk = false;
  setArgon2Offload(null);
}

export const nativeReady = (): boolean => nativeOk;
