// On-device self-test (plugin settings > Self-test). It proves, on THIS phone's JS engine, that
// everything both Aegires formats need gives the right bytes:
//   v1: PBKDF2 + AES-256-GCM vectors made by Aegires' real Java code.
//   v2: secure random, BigInt (Argon2), AES-128/192/256 + short GCM tags, HKDF, file chunks and
//       Argon2id itself (small-memory vectors; "Measure key derivation" runs the real 64 MiB one).
import { deriveKey } from "./crypto/keys.ts";
import { decryptBytes, decryptText, encryptBytes, encryptText } from "./crypto/wire.ts";
import { decryptFile, decryptMessage, encryptFile, encryptMessage, messagePreset, presetFromIndex } from "./crypto/v2.ts";
import { deriveMasterKey } from "./crypto/kdf2.ts";
import { nativeAesBench, nativeBench, nativeHkdfBench } from "./engine/engine.ts";
import { diag } from "./diag.ts";
import { fromHex, toHex } from "./crypto/hex.ts";
import { hasSecureRandom } from "./crypto/random.ts";
import { fileModule } from "./native.ts";
import { VECTORS_V1 } from "./selftest-vectors-v1.ts";
import { CHANNEL_ID, FILE, KDF, MASTER_HEX, MESSAGES, SENDER_ID } from "./selftest-vectors.ts";

export async function runSelfTest(): Promise<{ ok: boolean; lines: string[] }> {
  const lines: string[] = [];
  let ok = true;
  const check = (pass: boolean, text: string) => {
    lines.push((pass ? "PASS  " : "FAIL  ") + text);
    if (!pass) ok = false;
  };
  const guard = async (label: string, fn: () => Promise<void> | void) => {
    try { await fn(); } catch (e) { check(false, label + " threw: " + String(e)); }
  };

  const rng = hasSecureRandom();
  check(rng, "secure random (crypto.getRandomValues) available");
  check(typeof (globalThis as any).BigInt === "function", "BigInt available (needed for Argon2id)");

  // ---- v1
  for (const v of VECTORS_V1) {
    await guard("v1 " + v.label, async () => {
      const key = await deriveKey(v.password);
      check(decryptText(v.wire, key) === v.plain, "v1 decrypt Aegires vector: " + v.label);
      if (rng) check(decryptText(encryptText(v.plain, key), key) === v.plain, "v1 round trip: " + v.label);
    });
  }
  if (rng) {
    await guard("v1 file", async () => {
      const key = await deriveKey("selftest");
      const bytes = new Uint8Array(4096).map((_, i) => (i * 31) & 255);
      const back = decryptBytes(encryptBytes(bytes, key), key);
      check(!!back && toHex(back) === toHex(bytes), "v1 attachment round trip");
    });
  }

  // ---- v2
  const master = fromHex(MASTER_HEX)!;
  for (const v of MESSAGES) {
    await guard("v2 " + v.label, () => {
      check(decryptMessage(v.wire, master, CHANNEL_ID, SENDER_ID) === v.plain, "v2 decrypt vector: " + v.label);
      check(messagePreset(v.wire)?.index === v.preset, "v2 preset detected from header: " + v.label);
      check(decryptMessage(v.wire, master, CHANNEL_ID, "999") === null, "v2 wrong sender rejected: " + v.label);
      if (rng) {
        const again = decryptMessage(encryptMessage(v.plain, master, presetFromIndex(v.preset)!, CHANNEL_ID, SENDER_ID), master, CHANNEL_ID, SENDER_ID);
        check(again === v.plain, "v2 round trip (Padmé padded): " + v.label);
      }
    });
  }
  await guard("v2 file", () => {
    const got = decryptFile(fromHex(FILE.wireHex)!, master, CHANNEL_ID, SENDER_ID);
    check(!!got && toHex(got) === FILE.plainHex, "v2 decrypt file vector: " + FILE.label);
    if (rng) {
      const big = new Uint8Array(70000).map((_, i) => (i * 31) & 255);
      const back = decryptFile(encryptFile(big, master, presetFromIndex(2)!, CHANNEL_ID, SENDER_ID), master, CHANNEL_ID, SENDER_ID);
      check(!!back && back.length === big.length && toHex(back.subarray(65530, 65545)) === toHex(big.subarray(65530, 65545)), "v2 file round trip across a chunk boundary (70000 B)");
    }
  });
  for (const v of KDF) {
    await guard("Argon2id " + v.label, async () => {
      const t0 = Date.now();
      const key = await deriveMasterKey(v.password, { context: v.context ?? undefined, memoryKiB: v.memoryKiB });
      check(toHex(key) === v.key, `Argon2id vector: ${v.label} (${Date.now() - t0} ms)`);
    });
  }

  // Not fatal: without it attachments cannot be encrypted (uploads are then blocked).
  lines.push((fileModule() ? "PASS  " : "WARN  ") + "native file module (needed for attachments)");
  return { ok, lines };
}

/** Real-size derivation (64 MiB, 3 passes) so you can see how long a key takes on this phone. */
export async function measureKdf(onProgress?: (fraction: number) => void): Promise<number> {
  const t0 = Date.now();
  await deriveMasterKey("benchmark-password", { onProgress });
  return Date.now() - t0;
}

/** 1.2 ms / 85.3 ms / 1.25 s: always enough digits to compare, from microseconds to minutes. */
export function fmtMs(ms: number): string {
  if (ms < 1) return Math.round(ms * 1000) + " us";
  if (ms < 10) return ms.toFixed(2) + " ms";
  if (ms < 1000) return ms.toFixed(1) + " ms";
  if (ms < 60000) return (ms / 1000).toFixed(2) + " s";
  return Math.floor(ms / 60000) + " min " + Math.round((ms % 60000) / 1000) + " s";
}

const hexOf = (b: Uint8Array): string => Array.from(b.subarray(0, 8), (x) => x.toString(16).padStart(2, "0")).join("");
const nowMs = (): number => (typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now());
const median = (a: number[]): number => a.slice().sort((x, y) => x - y)[Math.floor(a.length / 2)];
const oneLine = (e: unknown): string => String((e as any)?.message ?? e).split("\n")[0].slice(0, 90);
const yieldUi = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

type Timing = { ms: number; head: string };

/**
 * Crypto benchmark in three sections: Argon2id, HKDF-SHA256 and AES-256-GCM, each on plain JS,
 * Kotlin/Java and the C library. Native results are the median of 3 runs, timed inside the native half.
 * `lines` is called as results arrive, so the screen fills in. Plain JS only runs when asked (slow).
 */
export async function benchCrypto(
  memMiB: number,
  includeJs: boolean,
  lines: (l: string[]) => void,
  onProgress?: (fraction: number) => void,
): Promise<void> {
  const HKDF_N = 2000;
  const AES_N = 150;
  const out: string[] = [];
  const push = (l: string) => { out.push(l); lines(out.slice()); };
  const set = (i: number, l: string) => { out[i] = l; lines(out.slice()); };
  const enc = (t: string) => new TextEncoder().encode(t);
  const got: Record<string, boolean> = {};

  // One section: runs js / java / c in turn and prints "Name: time (match) xN" lines.
  const section = async (
    title: string,
    js: (() => Promise<Timing>) | null,
    java: () => Promise<Timing>,
    c: (() => Promise<Timing>) | null,
    javaName: string,
    key: string,
  ) => {
    if (out.length) push("");
    push(title);
    const res: Record<string, Timing | null> = { js: null, java: null, c: null };
    const row = async (id: string, name: string, fn: () => Promise<Timing>) => {
      push("  " + name + ": running...");
      const at = out.length - 1;
      try {
        res[id] = await fn();
        set(at, "  " + name + ": " + fmtMs(res[id]!.ms));
      } catch (e) {
        set(at, "  " + name + ": not available (" + oneLine(e) + ")");
      }
    };
    if (js) await row("js", "Plain JS", js);
    await row("java", javaName, java);
    if (c) await row("c", "Native", c);
    else push("  Native: n/a (no C version, Java only)");
    // Annotate: match / mismatch against the Java answer, and the speedups.
    const ref = res.java ?? res.c ?? res.js;
    const idx = (name: string) => out.findIndex((l, i) => l.startsWith("  " + name + ":") && i >= out.length - 6);
    const tag = (id: string, name: string, extra: string) => {
      const r = res[id];
      const i = idx(name);
      if (!r || i < 0) return;
      const same = ref ? r.head === ref.head : true;
      set(i, out[i] + (same ? " (match)" : " (MISMATCH!)") + extra);
    };
    tag("js", "Plain JS", "");
    tag("java", javaName, res.js ? "  x" + (res.js.ms / res.java!.ms).toFixed(res.js.ms / res.java!.ms >= 100 ? 0 : 1) + " vs JS" : "");
    tag("c", "Native", res.java ? "  x" + (res.java.ms / res.c!.ms).toFixed(2) : "");
    got[key + "-c"] = !!res.c;
  };

  // ---- Argon2id (3 passes, memMiB, 32 bytes) ----
  const mKiB = memMiB * 1024;
  await section(
    `Argon2id (3, ${memMiB} MiB, 32B)`,
    includeJs
      ? async () => {
          const { argon2id } = await import("./crypto/argon2.ts");
          const t0 = nowMs();
          const key = await argon2id(enc("benchmark-password"), enc("dcenc2|bench|v1"), { t: 3, m: mKiB, dkLen: 32, onProgress });
          return { ms: nowMs() - t0, head: hexOf(key) };
        }
      : null,
    async () => { const r = await nativeBench("java", 3, mKiB, 3); return { ms: median(r.ms), head: r.head }; },
    async () => { const r = await nativeBench("c", 3, mKiB, 3); return { ms: median(r.ms), head: r.head }; },
    "Java",
    "argon",
  );

  // ---- HKDF-SHA256 extract+expand x2000 (44 bytes out, like an AES-256 key + nonce) ----
  await section(
    `HKDF-SHA256 extract+expand x${HKDF_N}`,
    includeJs
      ? async () => {
          const { hkdf } = await import("@noble/hashes/hkdf");
          const { sha256 } = await import("@noble/hashes/sha256");
          const ikm = new Uint8Array(32), salt = new Uint8Array(16);
          for (let i = 0; i < 32; i++) { ikm[i] = (i * 3 + 7) & 255; if (i < 16) salt[i] = i + 1; }
          const info = enc("aegires|bench|hkdf");
          let last = new Uint8Array(0);
          const t0 = nowMs();
          for (let k = 0; k < HKDF_N; k++) {
            last = hkdf(sha256, ikm, salt, info, 44);
            if (k % 200 === 199) { onProgress?.((k + 1) / HKDF_N); await yieldUi(); }
          }
          return { ms: nowMs() - t0, head: hexOf(last) };
        }
      : null,
    async () => { const r = await nativeHkdfBench("java", HKDF_N, 3); return { ms: median(r.ms), head: r.head }; },
    async () => { const r = await nativeHkdfBench("c", HKDF_N, 3); return { ms: median(r.ms), head: r.head }; },
    "Java",
    "hkdf",
  );

  // ---- AES-256-GCM, 64 KiB encrypt + decrypt x150 ----
  await section(
    `AES-256-GCM 64KiB enc+dec x${AES_N}`,
    includeJs
      ? async () => {
          const { gcmOpen, gcmSeal } = await import("./crypto/aead.ts");
          const key = new Uint8Array(32), plain = new Uint8Array(64 * 1024);
          for (let i = 0; i < 32; i++) key[i] = (i * 11 + 5) & 255;
          for (let i = 0; i < plain.length; i++) plain[i] = (i * 31 + (i >> 8)) & 255;
          const aad = new Uint8Array(0);
          const t0 = nowMs();
          for (let k = 0; k < AES_N; k++) {
            const nonce = new Uint8Array(12);
            nonce[0] = k & 255; nonce[1] = k >> 8;
            const back = gcmOpen(key, nonce, aad, gcmSeal(key, nonce, aad, plain, 16), 16);
            if (!back || back[777] !== plain[777]) throw new Error("JS AES round trip failed");
            if (k % 10 === 9) { onProgress?.((k + 1) / AES_N); await yieldUi(); }
          }
          const ms = nowMs() - t0;
          return { ms, head: hexOf(gcmSeal(key, new Uint8Array(12), aad, plain, 16)) };
        }
      : null,
    async () => { const r = await nativeAesBench(AES_N, 3); return { ms: median(r.ms), head: r.head }; },
    null,
    "Java/Conscrypt",
    "aes",
  );

  push("");
  const abi = diag.argon2Engine || "unknown";
  push("Native: " + abi + " | Argon2id=" + (got["argon-c"] ? "native" : "Java") + " HKDF=" + (got["hkdf-c"] ? "native" : "Java") + " AES=Java");
}
