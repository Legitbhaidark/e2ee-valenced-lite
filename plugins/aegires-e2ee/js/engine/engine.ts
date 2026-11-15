// Faster crypto from the NATIVE half of this plugin (Kotlin/Java, src/main), reached through
// Valenced's native bridge. Right now it does one job: Argon2id, the slow step that turns your
// password into a key (64 MiB of memory work, done once per password and then cached).
//
// If the native half is not installed, fails its self-check, or errors, everything silently
// falls back to the plain JS Argon2id (same bytes, just slower). The WebView engine of the Kettu
// version is gone; the old engine functions below stay as inert stand-ins until attachments are ported.
import { unscoped } from "../host.ts";
import { setArgon2Offload } from "../crypto/kdf2.ts";
import { base64Decode, base64Encode } from "../crypto/base64.ts";
import { diag } from "../diag.ts";

/** Method namespace: must match the names registered in src/main/kotlin/aegires/e2ee/AegiresNative.kt */
const NS = "aegires";

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
  const out = await callNative(
    "argon2id",
    [base64Encode(password), base64Encode(salt), String(t), String(mKiB), String(len)],
    ARGON_TIMEOUT_MS,
  );
  return typeof out === "string" ? base64Decode(out) : null;
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
  } catch (e) {
    why = String((e as any)?.message ?? e).slice(0, 70);
  }
  setArgon2Offload(nativeOk ? nativeArgon2 : null);
  diag.installed["native crypto"] = nativeOk ? "ok (Kotlin Argon2id, self-checked)" : "not used - JS crypto (" + why + ")";
  return nativeOk;
}

export function stopNative(): void {
  nativeOk = false;
  setArgon2Offload(null);
}

export const nativeReady = (): boolean => nativeOk;

// ---- stand-ins for the Kettu WebView engine (attachments are not ported yet) ----
export const engineReady = (): boolean => false;

export const waitEngine = async (_ms?: number): Promise<boolean> => false;

export function call<T>(_op: string, _args?: unknown, _timeoutMs?: number): Promise<T> {
  return Promise.reject(new Error("WebView engine is not in this build"));
}

export function mountChatHost(): () => void {
  return () => {};
}

export function stopEngine(): void {}

export function EngineHost(): null {
  return null;
}
