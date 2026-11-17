// Developer tools screen (a dev build): what JS shows it, and what JS does with what the user did there.
//   node test/native-dev-settings.mjs     (crypto packages are stubbed, none needed)
import assert from "node:assert/strict";
import { register } from "node:module";

globalThis.__DEV__ = true; // a developer build (about.ts reads this when it loads)

const hooks = `
  const NAMES = ["blake2b","deflateSync","inflateSync","ghash","hkdf","pbkdf2Async","sha1","sha256"];
  export async function resolve(spec, ctx, next) {
    if (/^@noble\\//.test(spec) || spec === "fflate") return { url: "stub:" + spec, shortCircuit: true };
    return next(spec, ctx);
  }
  export async function load(url, ctx, next) {
    if (url.startsWith("stub:")) return { format: "module", shortCircuit: true, source: NAMES.map((n) => n === "pbkdf2Async" ? "export const pbkdf2Async = async () => new Uint8Array(32);" : "export const " + n + " = () => { throw new Error('stub'); };").join("\\n") };
    return next(url, ctx);
  }`;
register("data:text/javascript," + encodeURIComponent(hooks), import.meta.url);

const { bindHost, unbindHost } = await import("../js/host.ts");
const { attachStorage, detachStorage, storage } = await import("../js/storage.ts");
const { initStorage } = await import("../js/state.ts");
const { startNative } = await import("../js/engine/engine.ts");
const { snapshot, handleEvent } = await import("../js/nativeSettings.ts");
const ok = (m) => console.log("  ok  " + m);
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");

let opened = null;
const updates = [];
const bridge = async (name, args) => {
  switch (name) {
    case "aegires.ping": return "pong";
    case "aegires.argon2id": case "aegires.argon2with": return KAT_B64;
    case "aegires.argon2engine": return "c:arm64-v8a";
    case "aegires.settingsopen": opened = JSON.parse(args[0]); return "ok";
    case "aegires.settingsupdate": updates.push(JSON.parse(args[0])); return "ok";
    case "aegires.cryptobench": return "line one\nline two";
    case "valenced.fs.getConstants": return { cache: "/c" };
    default: return "";
  }
};
bindHost({ unscoped: { modules: { native: { callNativeMethod: bridge } } } });
const store = { c: undefined, async get() { store.c = {}; return store.c; }, async set(v) { store.c = v; } };
await attachStorage(store);
initStorage();
assert.equal(await startNative(), true);
const tick = (ms) => new Promise((r) => setTimeout(r, ms));

const dev = () => snapshot().dev;
const last = (k) => [...updates].reverse().find((u) => k in u)?.[k];

// what the screen is shown
let snap = snapshot();
assert.equal(snap.isDev, true);
assert.ok(snap.dev, "developer data is part of the snapshot");
assert.match(snap.dev.diagText, /^global key: NOT SET\nformat: v2 /);
assert.match(snap.dev.diagText, /fast engine \(WebView\)/);
assert.equal(dev().v1, false);
assert.equal(dev().shortPw, false);
assert.equal(dev().engine, "auto");
assert.equal(dev().kdfOn, false);
assert.equal(dev().kdfMem, 64);
assert.equal(dev().kdfPasses, 3);
assert.equal(dev().maxMem, 256);
assert.equal(dev().maxPasses, 16);
ok("snapshot: developer data, defaults");

// Diagnostics
updates.length = 0;
await handleEvent({ t: "dev", a: "diag" });
assert.match(last("dev").diagText, /^global key: NOT SET/);
ok("diag: sends fresh text");

// switches
await handleEvent({ t: "dev", a: "set", k: "v1", v: true });
assert.equal(storage.format, "v1");
assert.equal(last("format"), "v1", "the Format card follows the v1 switch");
assert.equal(last("dev").v1, true);
await handleEvent({ t: "dev", a: "set", k: "v1", v: false });
assert.equal(storage.format, "v2");
assert.equal(last("format"), "v2");
await handleEvent({ t: "dev", a: "set", k: "shortPw", v: true });
assert.equal(storage.devShortPw, true);
assert.equal(last("dev").shortPw, true);
await handleEvent({ t: "dev", a: "set", k: "shortPw", v: false });
assert.equal(storage.devShortPw, false);
await handleEvent({ t: "dev", a: "set", k: "bogus", v: true });
assert.equal(storage.bogus, undefined, "an unknown switch is refused");
ok("switches: v1 format, short passwords");

// engine
await handleEvent({ t: "dev", a: "engine", v: "java" });
assert.equal(storage.devArgonEngine, "java");
assert.equal(last("dev").engine, "java");
await handleEvent({ t: "dev", a: "engine", v: "rust" });
assert.equal(storage.devArgonEngine, "java", "an unknown engine is refused");
await handleEvent({ t: "dev", a: "engine", v: "auto" });
assert.equal(last("dev").engine, "auto");
ok("engine: choices and refusal");

// custom Argon2id size
updates.length = 0;
await handleEvent({ t: "dev", a: "kdfSave", mem: "0", passes: "3" });
await handleEvent({ t: "dev", a: "kdfSave", mem: "257", passes: "3" });
await handleEvent({ t: "dev", a: "kdfSave", mem: "8", passes: "17" });
await handleEvent({ t: "dev", a: "kdfSave", mem: "8.5", passes: "2" });
await handleEvent({ t: "dev", a: "kdfSave", mem: "abc", passes: "2" });
assert.equal(updates.length, 0, "bad sizes change nothing");
assert.notEqual(storage.devKdfMemMiB, 8);
await handleEvent({ t: "dev", a: "kdfSave", mem: "8", passes: "2" });
assert.equal(storage.devKdfMemMiB, 8);
assert.equal(storage.devKdfPasses, 2);
assert.equal(last("dev").kdfMem, 8);
assert.equal(last("dev").kdfPasses, 2);
assert.equal(last("dev").kdfOn, false, "saved but not forced yet");
await handleEvent({ t: "dev", a: "kdfOn", v: true });
assert.equal(storage.devKdfOn, true);
assert.equal(last("dev").kdfOn, true);
await handleEvent({ t: "dev", a: "kdfOn", v: false });
assert.equal(last("dev").kdfOn, false);
ok("custom size: validation, save, force on/off");

// slow actions: busy label, result lines, busy cleared
updates.length = 0;
await handleEvent({ t: "dev", a: "selftest" });
assert.equal(updates[0].devBusy, "Running...");
assert.ok(Array.isArray(last("devLines")) && last("devLines").length > 0, "self-test lines are sent");
assert.equal(last("devBusy"), "", "busy ends");
assert.ok(last("dev"), "developer data is refreshed afterwards");
updates.length = 0;
await handleEvent({ t: "dev", a: "measure" });
assert.match(last("devLines")[0], /^Argon2id 64 MiB took .+ on this phone, /);
assert.equal(last("devBusy"), "");
updates.length = 0;
await handleEvent({ t: "dev", a: "bench", mib: 3, js: false });
assert.equal(updates.length, 0, "an unknown benchmark size is refused");
await handleEvent({ t: "dev", a: "bench", mib: 1, js: false });
assert.deepEqual(last("devBench"), ["line one", "line two"], "native benchmark text is passed on");
assert.equal(last("devBusy"), "");
ok("self-test, measure, benchmark: busy label, results, busy cleared");

await detachStorage();
unbindHost();
console.log("\ndeveloper settings tests passed.");
