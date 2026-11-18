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

/** Argon2id from the native half (Kotlin/Java/C). Resolves null when it failed. */
export type Argon2Offload = (password: Uint8Array, salt: Uint8Array, t: number, mKiB: number, len: number) => Promise<Uint8Array | null>;
let offload: Argon2Offload | null = null;

let nativeWhy = "";

export function setArgon2Offload(fn: Argon2Offload | null, why = ""): void {
  offload = fn;
  nativeWhy = why;
}

/** Developer option: replaces the standard 3 passes / 64 MiB. Keys made this way do NOT match other Aegires clients. */
export type KdfOverride = { t: number; mKiB: number };
let kdfOverride: KdfOverride | null = null;

export function setKdfOverride(o: KdfOverride | null): void {
  kdfOverride = o;
}

export const getKdfOverride = (): KdfOverride | null => kdfOverride;

/** Developer option: which Argon2id runs. auto = C library, else Kotlin/Java. */
export type ArgonEngine = "auto" | "c" | "java";
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
  if (offload) {
    const fast = await offload(utf8Encode(normalize(password)), salt, t, m, MASTER_LEN).catch(() => null);
    if (fast && fast.length === MASTER_LEN) return fast;
  }
  // No JS Argon2id any more (far too slow on Hermes): the native half is required for key derivation.
  throw new Error("Argon2id needs the native half of this plugin, which is not available (" + (nativeWhy || "not started yet") + ")");
}
