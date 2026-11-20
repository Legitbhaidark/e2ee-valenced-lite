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
const { FORCED_CHANNELS } = await import("../js/forced.ts");
const { startNative } = await import("../js/engine/engine.ts");
const { snapshot, handleEvent, openNativeSettings, TOGGLES } = await import("../js/nativeSettings.ts");
const ok = (m) => console.log("  ok  " + m);
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");

const glassCalls = [];
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
    case "aegires.glass": glassCalls.push(args[0]); return "ok";
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
for (const gone of ["attachmentsExp", "inlineVideo", "encButton"]) assert.equal(gone in snap.values, false, gone + " is part of the plugin now, not a switch");
assert.equal(snap.values.linkPreviews, false);
assert.equal(snap.autoMb, 25);
assert.equal(snap.isDev, false);
assert.ok(!("debug" in snap.values), "no debug switch in a release build");
assert.match(snap.build, /release$/);
ok("snapshot: keys, defaults, release build has no debug switch");

// format card: presets, Padmé, built-in channels
assert.ok(!("format" in snap), "v1 is gone: no format field");
assert.deepEqual(snap.presets.map((p) => p.index), PRESETS.map((p) => p.index));
assert.ok(snap.presets.every((p) => p.label && p.max > 1000), "every preset has a label and a max length");
assert.equal(snap.values.padme, true, "Padmé padding is on by default");
assert.deepEqual(snap.forced, Object.keys(FORCED_CHANNELS));
const other = PRESETS.find((p) => p.index !== snap.preset);
await handleEvent({ t: "setPreset", i: other.index });
assert.equal(snapshot().preset, other.index);
await handleEvent({ t: "setPreset", i: 999 });
await handleEvent({ t: "setPreset", i: "x" });
assert.equal(snapshot().preset, other.index, "an unknown preset is ignored");
await handleEvent({ t: "set", k: "padme", v: false });
assert.equal(storage.padme, false);
await handleEvent({ t: "set", k: "padme", v: true });
assert.equal(snapshot().values.padme, true);
ok("format card: preset choice, Padmé, built-in channels list");

// per-chat card
assert.equal(snap.chanId, "", "no chat open in the test");
assert.deepEqual(snap.chats, []);
updates.length = 0;
await handleEvent({ t: "saveChat", id: "abc" });
assert.equal(updates.length, 0, "a channel id with letters is refused before anything happens");
await handleEvent({ t: "saveChat", id: "123456789" });
assert.deepEqual(snapshot().chats, [{ id: "123456789", hasKey: false, enabled: true }], "no password: only switched on");
assert.equal(updates.at(-1).chatBusy, false);
await handleEvent({ t: "saveChat", id: "987654321", pw: "chat password" });
assert.equal(updates.some((u) => u.chatBusy === true), true, "a password shows busy while the key is derived");
assert.equal(snapshot().chats.find((c) => c.id === "987654321").hasKey, true);
await handleEvent({ t: "chatEnabled", id: "987654321", v: false });
assert.equal(snapshot().chats.find((c) => c.id === "987654321").enabled, false);
assert.deepEqual(updates.at(-1).chats.map((c) => c.id).sort(), ["123456789", "987654321"]);
await handleEvent({ t: "removeChat", id: "123456789" });
assert.deepEqual(snapshot().chats.map((c) => c.id), ["987654321"]);
await handleEvent({ t: "removeChat", id: "987654321" });
await handleEvent({ t: "saveChat", id: "55", pw: "short" });
assert.equal(updates.at(-1).chatBusy, false, "a refused password still ends the busy state");
assert.deepEqual(snapshot().chats, []);
ok("per-chat card: save, own key, switch, remove, bad input");

// toggles
await handleEvent({ t: "set", k: "showLock", v: false });
assert.equal(storage.showLock, false);
await handleEvent({ t: "set", k: "inlineVideo", v: false });
assert.equal(storage.inlineVideo, undefined, "a switch that no longer exists is ignored");
await handleEvent({ t: "set", k: "encryptByDefault", v: true });
assert.equal(storage.encryptByDefault, true);
await handleEvent({ t: "set", k: "encryptByDefault", v: "yes" });
assert.equal(storage.encryptByDefault, false, "only a real true switches on");
ok("toggles are written to storage");

// liquid glass: one bit mask tells the native half what to draw (1 glass, 2 soft glass, 4 glass controls)
glassCalls.length = 0;
await handleEvent({ t: "set", k: "glassControls", v: true });
await tick(5);
assert.equal(storage.glassControls, true);
assert.equal(glassCalls.at(-1), "0", "glass controls do nothing while liquid glass is off");
await handleEvent({ t: "set", k: "liquidGlass", v: true });
await tick(5);
assert.equal(glassCalls.at(-1), "5", "glass + glass controls");
await handleEvent({ t: "set", k: "softGlass", v: true });
await tick(5);
assert.equal(glassCalls.at(-1), "7", "glass + soft glass + glass controls");
await handleEvent({ t: "set", k: "glassControls", v: false });
await tick(5);
assert.equal(glassCalls.at(-1), "3", "glass + soft glass");
await handleEvent({ t: "set", k: "softGlass", v: false });
await tick(5);
assert.equal(glassCalls.at(-1), "1", "glass only");
await handleEvent({ t: "set", k: "liquidGlass", v: false });
await tick(5);
assert.equal(glassCalls.at(-1), "0", "liquid glass off turns everything off");
ok("liquid glass: bit mask for glass, soft glass and glass controls");

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
assert.equal(opened.version.length > 0, true);
queue = [{ t: "set", k: "showLock", v: true }];
await tick(450);
assert.equal(storage.showLock, true, "the polling loop applies events");
queue = [{ t: "set", k: "showLock", v: false }];
isOpen = false;
await tick(450);
assert.equal(storage.showLock, false, "the last events are still applied after the screen closes");
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

// a release build ignores every developer event and shows no developer data
assert.ok(!("dev" in snapshot()), "no developer data in a release build");
for (const ev of [
  { t: "dev", a: "set", k: "shortPw", v: true },
  { t: "dev", a: "set", k: "v1", v: true },
  { t: "dev", a: "kdfOn", v: true },
  { t: "dev", a: "engine", v: "java" },
  { t: "dev", a: "kdfSave", mem: "1", passes: "1" },
]) await handleEvent(ev);
assert.ok(!storage.devShortPw && !storage.devKdfOn && storage.format === undefined, "developer events change nothing in a release build");
assert.notEqual(storage.devArgonEngine, "java");
ok("release build: developer events are refused");

await detachStorage();
unbindHost();
console.log("\nnative settings tests passed.");
