// Aegires v2 key derivation (Crypto.deriveKey):
//   Argon2id v1.3, t=3 passes, m=64 MiB, p=1, 32-byte output.
//   password = NFKC-normalised, UTF-8.
//   salt     = "dcenc2|user-key|v1"            (session-wide password)
//            = "dcenc2|ctx|" + context          (e.g. context "forced|<channelId>" for preset channels)
// The 32-byte result is the "master" key; per-message keys come from HKDF (see v2.ts).
// Slow by design (64 MiB of memory work): always call it once and cache the result.
import { utf8Encode } from "./utf8.ts";

export const ARGON_T = 3;
export const ARGON_M_KIB = 64 * 1024;
export const MASTER_LEN = 32;

const USER_KEY_SALT = "dcenc2|user-key|v1";

export function forcedContext(channelId: string): string {
  return "forced|" + channelId;
}

function normalize(password: string): string {
  try {
    return password.normalize("NFKC");
  } catch {
    return password; // runtime without String.prototype.normalize
  }
}

export type KdfOptions = {
  context?: string;
  onProgress?: (fraction: number) => void;
  /** Test hook only: lower memory. Real keys must use the default. */
  memoryKiB?: number;
};

/** Faster Argon2id elsewhere (the WebView engine); resolves null to use the JS code here. */
export type Argon2Offload = (password: Uint8Array, salt: Uint8Array, t: number, mKiB: number, len: number) => Promise<Uint8Array | null>;
let offload: Argon2Offload | null = null;

export function setArgon2Offload(fn: Argon2Offload | null): void {
  offload = fn;
}

/** Developer option: replaces the standard 3 passes / 64 MiB. Keys made this way do NOT match other Aegires clients. */
export type KdfOverride = { t: number; mKiB: number };
let kdfOverride: KdfOverride | null = null;

export function setKdfOverride(o: KdfOverride | null): void {
  kdfOverride = o;
}

export const getKdfOverride = (): KdfOverride | null => kdfOverride;

/** Developer option: which Argon2id runs. auto = C library, else Kotlin/Java, else plain JS. */
export type ArgonEngine = "auto" | "c" | "java" | "js";
let argonEngine: ArgonEngine = "auto";

export function setArgonEngine(e: ArgonEngine): void {
  argonEngine = e;
}

export const getArgonEngine = (): ArgonEngine => argonEngine;

export async function deriveMasterKey(password: string, opts: KdfOptions = {}): Promise<Uint8Array> {
  if (!password) throw new Error("empty password");
  const salt = utf8Encode(opts.context === undefined ? USER_KEY_SALT : "dcenc2|ctx|" + opts.context);
  // The memory test hook (self-test vectors) always wins over the developer override.
  const custom = opts.memoryKiB === undefined ? kdfOverride : null;
  const t = custom ? custom.t : ARGON_T;
  const m = opts.memoryKiB ?? (custom ? custom.mKiB : ARGON_M_KIB);
  if (offload && argonEngine !== "js") {
    const fast = await offload(utf8Encode(normalize(password)), salt, t, m, MASTER_LEN).catch(() => null);
    if (fast && fast.length === MASTER_LEN) return fast;
  }
  // Lazy import: BLAKE2b (@noble/hashes) uses BigInt at module load. Loading it only when a key is
  // derived means a runtime without BigInt can still decrypt/encrypt with stored keys.
  const { argon2id } = await import("./argon2.ts");
  return argon2id(utf8Encode(normalize(password)), salt, {
    t,
    m,
    dkLen: MASTER_LEN,
    onProgress: opts.onProgress, // also yields to the event loop every ~100 ms so the UI stays alive
  });
}
