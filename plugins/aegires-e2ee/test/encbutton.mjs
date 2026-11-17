// Header "E2EE" button: which state JS sends to the native overlay, and what a press does.
//   node test/encbutton.mjs     (crypto packages are stubbed, none needed)
import assert from "node:assert/strict";
import { register } from "node:module";

const hooks = `
  const NAMES = ["blake2b","deflateSync","inflateSync","ghash","hkdf","pbkdf2Async","sha1","sha256"];
  export async function resolve(spec, ctx, next) {
    if (/^@noble\\//.test(spec) || spec === "fflate") return { url: "stub:" + spec, shortCircuit: true };
    return next(spec, ctx);
  }
  export async function load(url, ctx, next) {
    if (url.startsWith("stub:")) return { format: "module", shortCircuit: true, source: NAMES.map((n) => "export const " + n + " = () => { throw new Error('stub'); };").join("\\n") };
    return next(url, ctx);
  }`;
register("data:text/javascript," + encodeURIComponent(hooks), import.meta.url);

const { bindHost, unbindHost } = await import("../js/host.ts");
const { attachStorage, detachStorage, storage } = await import("../js/storage.ts");
const { initStorage, setChannelEnabled, isEncryptionOn } = await import("../js/state.ts");
const { wantedState, pressed, installEncButton } = await import("../js/patches/encButton.ts");
const { startNative, nativeReady } = await import("../js/engine/engine.ts");
const { diag } = await import("../js/diag.ts");
const ok = (m) => console.log("  ok  " + m);
const CH = "123456789012345678";
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");
const FORCED = "1475115975609835520";
let selected = CH;
let taps = 0;
const sentStates = [];

const bridge = async (name, args) => {
  switch (name) {
    case "aegires.ping": return "pong";
    case "aegires.argon2id": case "aegires.argon2with": return KAT_B64;
    case "aegires.argon2engine": return "c:arm64-v8a";
    case "aegires.encbutton": sentStates.push(args[0]); return "ok";
    case "aegires.encbuttonstatus": return "shown";
    case "aegires.encbuttontaps": { const n = taps; taps = 0; return String(n); }
    case "valenced.fs.getConstants": return { cache: "/c" };
    default: return "";
  }
};
bindHost({
  unscoped: {
    modules: { native: { callNativeMethod: bridge } },
    discord: { flux: { Stores: { SelectedChannelStore: { getChannelId: () => selected } } } },
  },
});
const store = { c: undefined, async get() { store.c = {}; return store.c; }, async set(v) { store.c = v; } };
await attachStorage(store);
initStorage();
assert.equal(await startNative(), true);
const tick = (ms) => new Promise((r) => setTimeout(r, ms));

// states
storage.keyV2 = "ab".repeat(32);
assert.equal(storage.encButton, true, "on by default");
assert.equal(wantedState(""), "hide");
assert.equal(wantedState(CH), "off");
setChannelEnabled(CH, true);
assert.equal(wantedState(CH), "on");
storage.encButton = false;
assert.equal(wantedState(CH), "hide");
storage.encButton = true;
ok("states: hide without a chat or when switched off in settings, otherwise on / off");

// presses
setChannelEnabled(CH, false);
assert.equal(pressed(CH), "");
assert.equal(isEncryptionOn(CH), true);
assert.equal(pressed(CH), "");
assert.equal(isEncryptionOn(CH), false);
ok("a press switches this chat on, the next one off");

delete storage.keyV2;
storage.globalKeyHex = undefined;
assert.match(pressed(CH), /No key for this chat/);
assert.equal(isEncryptionOn(CH), false, "no key: stays off and says why");
storage.keyV2 = "ab".repeat(32);
ok("no key: a press does not switch on, and explains");

// the loop
if (nativeReady()) {
  const stop = installEncButton();
  await tick(450);
  assert.equal(sentStates.at(-1), "off");
  taps = 1;
  await tick(450);
  assert.equal(isEncryptionOn(CH), true, "a native press reached the switch");
  assert.equal(sentStates.at(-1), "on");
  const n = sentStates.length;
  await tick(700);
  assert.equal(sentStates.length, n, "unchanged state is not re-sent every poll");
  selected = "";
  await tick(450);
  assert.equal(sentStates.at(-1), "hide");
  stop();
  await tick(50);
  assert.equal(sentStates.at(-1), "hide");
  assert.match(diag.installed["encryption button"], /hide/);
  ok("loop: sends the state, applies a press, stays quiet when nothing changes, hides with no chat and on stop");
} else {
  assert.fail("native bridge stub not ready");
}

await detachStorage();
unbindHost();
console.log("\nencryption button tests passed.");
