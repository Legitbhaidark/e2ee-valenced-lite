// The settings page logic (js/ui/settingsModel.ts): set / clear the key, pick the profile, status text,
// and the lookup of Discord's design components. Drawing itself (Settings.tsx) is JSX, which Node
// cannot run, so it is not covered here.
//   node --experimental-strip-types test/settings-model.mjs     (crypto packages are stubbed, none needed)
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
const { initStorage, hasGlobalKey, PRESETS, getPreset } = await import("../js/state.ts");
const { startNative } = await import("../js/engine/engine.ts");
const { useKeySettings, designOrNull, diagText } = await import("../js/ui/settingsModel.ts");
const ok = (m) => console.log("  ok  " + m);
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");

// A tiny stand-in for React hooks: runs the hook function, keeps its state, re-runs on every change.
const alerts = [];
function makeReact() {
  let states = [];
  let i = 0;
  let rerender = () => {};
  return {
    React: {
      createElement: () => null,
      useState(init) {
        const k = i++;
        if (!(k in states)) states[k] = init;
        return [states[k], (v) => { states[k] = typeof v === "function" ? v(states[k]) : v; rerender(); }];
      },
    },
    run(fn) {
      let result;
      const render = () => { i = 0; result = fn(); };
      rerender = render;
      render();
      return () => result;
    },
  };
}

let gate = null; // lets the test hold the key derivation open
let failArgon = false;
const bridge = async (name) => {
  switch (name) {
    case "e2eelite.ping": return "pong";
    case "e2eelite.argon2id": case "e2eelite.argon2with": if (gate) await gate; if (failArgon) throw new Error("native failure"); return KAT_B64;
    case "e2eelite.argon2engine": return "c:arm64-v8a";
    default: return "";
  }
};
const hostReact = makeReact();
const rn = { View: "View", Alert: { alert: (_t, m) => alerts.push(m) } };
bindHost({ unscoped: { react: { React: hostReact.React, ReactNative: rn }, modules: { native: { callNativeMethod: bridge } } } });
const store = { c: undefined, async get() { store.c = {}; return store.c; }, async set(v) { store.c = v; } };
await attachStorage(store);
initStorage();
assert.equal(await startNative(), true);

const s = hostReact.run(() => useKeySettings());

// start: no key
assert.equal(hasGlobalKey(), false);
assert.equal(s().status, "No key set.");
assert.equal(s().busy, false);
assert.equal(s().pw, "");
ok("start: no key set");

// a too short password is refused with a message, and nothing changes
s().setPw("short");
assert.equal(s().pw, "short");
await s().setKey();
assert.equal(alerts.length, 1);
assert.match(alerts[0], /at least 8 characters/);
assert.equal(hasGlobalKey(), false);
assert.equal(s().status, "No key set.");
ok("short password: refused, message shown");

// a good password: busy while the key is derived, then set, field cleared
s().setPw("correct horse battery");
let release;
gate = new Promise((r) => (release = r));
const pending = s().setKey();
assert.equal(s().busy, true);
assert.equal(s().status, "Deriving key...");
await s().setKey(); // pressing again while busy does nothing
assert.equal(alerts.length, 1);
release();
await pending;
gate = null;
assert.equal(s().busy, false);
assert.equal(hasGlobalKey(), true);
assert.equal(s().status, "Key set.");
assert.equal(s().pw, "", "the password is not kept in the field");
ok("set key: busy while deriving, then set, field cleared");

// clear key
s().clearKey();
assert.equal(hasGlobalKey(), false);
assert.equal(s().status, "No key set.");
ok("clear key");

// profile
const other = PRESETS.find((p) => p.index !== getPreset().index);
s().pick(other.index);
assert.equal(getPreset().index, other.index);
assert.equal(storage.preset, other.index);
s().pick(999);
assert.equal(getPreset().index, other.index, "an unknown profile is ignored");
ok("profile: pick one, unknown ignored");

// a failing derivation shows the reason and ends busy
s().setPw("another password!");
failArgon = true;
await s().setKey();
assert.match(alerts.at(-1), /Set key failed: Argon2id needs the native half/);
assert.equal(s().busy, false);
assert.equal(hasGlobalKey(), false);
failArgon = false;
ok("failed derivation: reason shown, not busy");

// Discord's design components: all there, or null
const full = { Text: 1, Button: 1, TextInput: 1, TableRadioGroup: 1, TableRadioRow: 1 };
bindHost({ unscoped: { discord: { design: full } } });
assert.equal(designOrNull(), full);
for (const missing of Object.keys(full)) {
  const d = { ...full };
  delete d[missing];
  bindHost({ unscoped: { discord: { design: d } } });
  assert.equal(designOrNull(), null, "null without " + missing);
}
bindHost({ unscoped: { discord: {} } });
assert.equal(designOrNull(), null);
bindHost({ unscoped: { discord: { get design() { throw new Error("index not initialized"); } } } });
assert.equal(designOrNull(), null, "a throwing lookup is null, not a crash");
ok("design components: found, or null when any is missing");

assert.match(diagText(), /^global key: NOT SET\nprofile: AES-/);
ok("diagnostics text");

await detachStorage();
unbindHost();
console.log("\nsettings model tests passed.");
