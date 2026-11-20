// Tests js/engine/engine.ts: the JS side of the native Argon2id offload, against a FAKE native bridge.
// It checks what we send (argument order and encoding), the self-check that guards against a wrong
// native build, and that every failure falls back instead of breaking key derivation.
// The Kotlin/Java side is tested separately: test/native/NativeOpsTest.java.
//
// Run from the plugin folder:  node test/native-offload.mjs
import assert from "node:assert/strict";

let realCrypto = true;
try {
  import.meta.resolve("@noble/hashes/blake2b");
} catch {
  realCrypto = false;
}

const { bindHost, unbindHost } = await import("../js/host.ts");
const { startNative, stopNative, nativeReady } = await import("../js/engine/engine.ts");
const { deriveMasterKey } = await import("../js/crypto/kdf2.ts");
const { diag } = await import("../js/diag.ts");

const ok = (m) => console.log("  ok  " + m);
const b64 = (s) => Buffer.from(s).toString("base64");
const KAT_HEX = "9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8";
const KAT_B64 = Buffer.from(KAT_HEX, "hex").toString("base64");
const FAKE_KEY = Buffer.alloc(32, 0xab);

/** Fake bridge. `behavior` can override how each method answers. */
function install(behavior = {}) {
  const calls = [];
  const callNativeMethod = async (name, args) => {
    calls.push({ name, args });
    if (behavior.reject) throw new Error(behavior.reject);
    if (name === "e2eelite.ping") return behavior.ping === undefined ? "pong" : behavior.ping;
    if (name === "e2eelite.argon2id") {
      const [pw, salt, t, m, len] = args;
      // the self-check call: answer with the real known answer, unless told to lie
      if (Buffer.from(salt, "base64").toString() === "dcenc2|selfcheck") return behavior.lie ? FAKE_KEY.toString("base64") : KAT_B64;
      return behavior.argon ?? FAKE_KEY.toString("base64");
    }
    throw new Error("unknown method " + name);
  };
  bindHost({ unscoped: { modules: { native: { callNativeMethod } } } });
  return calls;
}

// 1. healthy native half: self-check passes, derivation goes native with the right arguments
{
  const calls = install();
  assert.equal(await startNative(), true);
  assert.equal(nativeReady(), true);
  assert.match(diag.installed["native crypto"], /^ok/);
  assert.deepEqual(calls.map((c) => c.name), ["e2eelite.ping", "e2eelite.argon2id", "e2eelite.touchspy", "e2eelite.argon2engine"]);
  // self-check call encodes (password, salt, passes, memoryKiB, outLen) in that order
  assert.deepEqual(calls[1].args, [b64("aegires native check"), b64("dcenc2|selfcheck"), "2", "64", "32"]);
  ok("healthy native half: ping + self-check pass, arguments in the order Kotlin reads them");

  calls.length = 0;
  const key = await deriveMasterKey("correct horse");
  assert.deepEqual(Buffer.from(key), FAKE_KEY);
  assert.deepEqual(calls[0].args, [b64("correct horse"), b64("dcenc2|user-key|v1"), "3", "65536", "32"]);
  ok("deriveMasterKey uses native with the real parameters (t=3, 64 MiB, 32 bytes, user-key salt)");

  calls.length = 0;
  await deriveMasterKey("pw", { context: "forced|42" });
  assert.equal(Buffer.from(calls[0].args[1], "base64").toString(), "dcenc2|ctx|forced|42");
  ok("forced-channel context goes into the salt");

  // NFKC normalisation happens BEFORE the bridge (Aegires normalises the password)
  calls.length = 0;
  await deriveMasterKey("ﬁ");
  assert.equal(Buffer.from(calls[0].args[0], "base64").toString(), "fi");
  ok("password is NFKC-normalised before it is sent");

  stopNative();
  assert.equal(nativeReady(), false);
}

// 2. native returns a WRONG key at the self-check: it must not be used
{
  const calls = install({ lie: true });
  assert.equal(await startNative(), false);
  assert.match(diag.installed["native crypto"], /self-check mismatch/);
  calls.length = 0;
  await assert.rejects(deriveMasterKey("x", { memoryKiB: 64 }), /needs the native half/);
  assert.equal(calls.length, 0);
  ok("wrong native result at the self-check: native is not used for real keys");
}

// 3. native half not installed (the call errors), or no bridge at all
{
  const calls = install({ reject: "Call failed: method not found" });
  assert.equal(await startNative(), false);
  assert.match(diag.installed["native crypto"], /UNAVAILABLE/);
  calls.length = 0;
  await assert.rejects(deriveMasterKey("x", { memoryKiB: 64 }), /needs the native half/);
  assert.equal(calls.length, 0);
  ok("native half missing: key derivation fails clearly, native is never called for keys");

  bindHost({ unscoped: { modules: {} } });
  assert.equal(await startNative(), false);
  assert.match(diag.installed["native crypto"], /bridge not available/);
  ok("no native bridge: handled");
}

// 4. unexpected ping reply
{
  install({ ping: "nope" });
  assert.equal(await startNative(), false);
  ok("wrong ping reply: not used");
}

// 5. native errors on a real derivation after passing the self-check: clear error, no JS fallback
{
  install();
  assert.equal(await startNative(), true);
  bindHost({ unscoped: { modules: { native: { callNativeMethod: async () => { throw new Error("boom"); } } } } });
  await assert.rejects(deriveMasterKey("fallback pw", { memoryKiB: 64 }), /needs the native half/);
  ok("native errors on a real derivation: derivation fails (no JS Argon2id any more)");
  stopNative();
}

// 6. a ping that never answers is given up on (3 s) and native stays unused
{
  bindHost({ unscoped: { modules: { native: { callNativeMethod: () => new Promise(() => {}) } } } });
  const t0 = Date.now();
  assert.equal(await startNative(), false);
  assert.ok(Date.now() - t0 < 5000);
  assert.match(diag.installed["native crypto"], /timed out/);
  ok("a native call that never answers times out");
}

stopNative();
unbindHost();
console.log("\nnative offload tests passed.");
