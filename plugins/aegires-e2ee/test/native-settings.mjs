// Native settings screen: what JS shows it, and what JS does with what the user did there.
//   node test/native-settings.mjs     (crypto packages are stubbed, none needed)
import assert from "node:assert/strict";
import { register } from "node:module";

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
const { initStorage, hasGlobalKey } = await import("../js/state.ts");
const { startNative } = await import("../js/engine/engine.ts");
const { snapshot, handleEvent, openNativeSettings, TOGGLES } = await import("../js/nativeSettings.ts");
const ok = (m) => console.log("  ok  " + m);
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");

let opened = null;
const updates = [];
let queue = [];
let isOpen = true;
const bridge = async (name, args) => {
  switch (name) {
    case "aegires.ping": return "pong";
    case "aegires.argon2id": case "aegires.argon2with": return KAT_B64;
    case "aegires.argon2engine": return "c:arm64-v8a";
    case "aegires.settingsopen": opened = JSON.parse(args[0]); return "ok";
    case "aegires.settingsupdate": updates.push(JSON.parse(args[0])); return "ok";
    case "aegires.settingstake": { const events = queue; queue = []; return JSON.stringify({ open: isOpen, events }); }
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

// what the screen is shown
let snap = snapshot();
assert.equal(snap.hasKey, false);
assert.equal(snap.rule, "8 characters");
assert.deepEqual(Object.keys(snap.values).sort(), [...TOGGLES].sort());
assert.equal(snap.values.inlineVideo, true, "inline video is on by default");
assert.equal(snap.values.encButton, true, "header button is on by default");
assert.equal(snap.values.linkPreviews, false);
assert.equal(snap.autoMb, 25);
assert.equal(snap.isDev, false);
assert.ok(!("debug" in snap.values), "no debug switch in a release build");
assert.match(snap.build, /release$/);
ok("snapshot: keys, defaults, release build has no debug switch");

// toggles
await handleEvent({ t: "set", k: "showLock", v: false });
assert.equal(storage.showLock, false);
await handleEvent({ t: "set", k: "inlineVideo", v: false });
assert.equal(storage.inlineVideo, false);
assert.equal(snapshot().values.inlineVideo, false);
await handleEvent({ t: "set", k: "encryptByDefault", v: true });
assert.equal(storage.encryptByDefault, true);
await handleEvent({ t: "set", k: "encryptByDefault", v: "yes" });
assert.equal(storage.encryptByDefault, false, "only a real true switches on");
ok("toggles are written to storage");

// not allowed
await handleEvent({ t: "set", k: "keyV2", v: true });
await handleEvent({ t: "set", k: "devKdfOn", v: true });
await handleEvent({ t: "set", k: "debug", v: true });
await handleEvent({ t: "set", k: "__proto__", v: true });
assert.notEqual(storage.keyV2, true);
assert.notEqual(storage.devKdfOn, true);
assert.notEqual(storage.debug, true, "debug cannot be switched on in a release build");
ok("unknown and developer keys are refused");

// auto-decrypt size
await handleEvent({ t: "set", k: "autoDecryptMB", v: "50" });
assert.equal(storage.autoDecryptMB, 50);
await handleEvent({ t: "set", k: "autoDecryptMB", v: "" });
assert.equal(storage.autoDecryptMB, 25, "empty goes back to the default");
await handleEvent({ t: "set", k: "autoDecryptMB", v: "12ab3456789" });
assert.equal(storage.autoDecryptMB, 1234, "digits only, 4 at most");
ok("auto-decrypt size: digits only, empty = 25");

// global key
await handleEvent({ t: "setKey", pw: "correct horse" });
assert.equal(hasGlobalKey(), true);
assert.equal(updates[0].busy, true);
assert.equal(updates.at(-1).busy, false);
assert.equal(updates.at(-1).hasKey, true);
ok("setKey derives the key, shows busy, then reports the key is set");
await handleEvent({ t: "setKey", pw: "short" });
assert.equal(updates.at(-1).busy, false, "a refused password still ends the busy state");
await handleEvent({ t: "clearKey" });
assert.equal(hasGlobalKey(), false);
assert.equal(updates.at(-1).hasKey, false);
ok("clearKey removes it; a too-short password ends busy");

await handleEvent(null);
await handleEvent({ t: "nope" });
ok("garbage events are ignored");

// open + poll loop
updates.length = 0;
assert.equal(await openNativeSettings(), "");
assert.equal(opened.version.length > 0, true);
queue = [{ t: "set", k: "showLock", v: true }];
await tick(450);
assert.equal(storage.showLock, true, "the polling loop applies events");
queue = [{ t: "set", k: "showLock", v: false }];
isOpen = false;
await tick(450);
assert.equal(storage.showLock, false, "the last events are still applied after the screen closes");
ok("open: the loop applies events and stops after the screen closes");

await detachStorage();
unbindHost();
console.log("\nnative settings tests passed.");
