// Native settings screen: what JS shows it, and what JS does with what the user did there.
//   node test/native-settings.mjs     (crypto packages are stubbed, none needed)
import assert from "node:assert/strict";
import { register } from "node:module";

const hooks = `
  const NAMES = ["blake2b","ghash","hkdf","pbkdf2Async","sha1","sha256"];
  export async function resolve(spec, ctx, next) {
    if (/^@noble\\//.test(spec)) return { url: "stub:" + spec, shortCircuit: true };
    return next(spec, ctx);
  }
  export async function load(url, ctx, next) {
    if (url.startsWith("stub:")) return { format: "module", shortCircuit: true, source: NAMES.map((n) => n === "pbkdf2Async" ? "export const pbkdf2Async = async () => new Uint8Array(32);" : "export const " + n + " = () => { throw new Error('stub'); };").join("\\n") };
    return next(url, ctx);
  }`;
register("data:text/javascript," + encodeURIComponent(hooks), import.meta.url);

const { bindHost, unbindHost } = await import("../js/host.ts");
const { attachStorage, detachStorage, storage } = await import("../js/storage.ts");
const { initStorage, hasGlobalKey, PRESETS } = await import("../js/state.ts");
const { startNative } = await import("../js/engine/engine.ts");
const { snapshot, handleEvent, openNativeSettings } = await import("../js/nativeSettings.ts");
const ok = (m) => console.log("  ok  " + m);
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");

let opened = null;
const updates = [];
let queue = [];
let isOpen = true;
const bridge = async (name, args) => {
  switch (name) {
    case "e2eelite.ping": return "pong";
    case "e2eelite.argon2id": case "e2eelite.argon2with": return KAT_B64;
    case "e2eelite.argon2engine": return "c:arm64-v8a";
    case "e2eelite.settingsopen": opened = JSON.parse(args[0]); return "ok";
    case "e2eelite.settingsupdate": updates.push(JSON.parse(args[0])); return "ok";
    case "e2eelite.settingstake": { const events = queue; queue = []; return JSON.stringify({ open: isOpen, events }); }
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

// what the screen is shown: only the key state and the presets
let snap = snapshot();
assert.deepEqual(Object.keys(snap).sort(), ["hasKey", "preset", "presets"], "the screen has two things: key and preset");
assert.equal(snap.hasKey, false);
assert.deepEqual(snap.presets.map((p) => p.index), PRESETS.map((p) => p.index));
assert.ok(snap.presets.every((p) => p.label && p.max > 1000), "every preset has a label and a max length");
ok("snapshot: key state and presets only");

// preset
const other = PRESETS.find((p) => p.index !== snap.preset);
await handleEvent({ t: "setPreset", i: other.index });
assert.equal(snapshot().preset, other.index);
await handleEvent({ t: "setPreset", i: 999 });
await handleEvent({ t: "setPreset", i: "x" });
assert.equal(snapshot().preset, other.index, "an unknown preset is ignored");
ok("preset choice, unknown ones ignored");

// everything else is gone: events for removed settings change nothing
await handleEvent({ t: "set", k: "padme", v: false });
await handleEvent({ t: "set", k: "showLock", v: false });
await handleEvent({ t: "set", k: "keyV2", v: true });
await handleEvent({ t: "dev", a: "selftest" });
assert.equal(storage.padme, true, "Padmé stays on");
assert.equal(storage.showLock, true, "the lock icon stays on");
assert.notEqual(storage.keyV2, true);
ok("events of removed settings are ignored");

// global key
updates.length = 0;
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
assert.equal(opened.presets.length, PRESETS.length);
queue = [{ t: "setPreset", i: PRESETS[0].index }];
await tick(450);
assert.equal(snapshot().preset, PRESETS[0].index, "the polling loop applies events");
queue = [{ t: "setPreset", i: PRESETS[1].index }];
isOpen = false;
await tick(450);
assert.equal(snapshot().preset, PRESETS[1].index, "the last events are still applied after the screen closes");
ok("open: the loop applies events and stops after the screen closes");

// close callback
let closed = 0;
isOpen = true;
assert.equal(await openNativeSettings(() => closed++), "");
assert.equal(closed, 0, "not called while the screen is open");
isOpen = false;
await tick(450);
assert.equal(closed, 1, "called once when the screen closes");
ok("open: the close callback runs when the screen closes");
