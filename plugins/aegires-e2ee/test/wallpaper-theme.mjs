// The wallpaper as a Valenced theme: what is registered, and the layer fallback without the Themes API.
//   node test/wallpaper-theme.mjs     (crypto packages are stubbed, none needed)
import assert from "node:assert/strict";
import { register } from "node:module";

const hooks = `
  const NAMES = ["blake2b","ghash","hkdf","pbkdf2Async","sha1","sha256"];
  export async function resolve(spec, ctx, next) {
    if (/^@noble\\//.test(spec)) return { url: "stub:" + spec, shortCircuit: true };
    return next(spec, ctx);
  }
  export async function load(url, ctx, next) {
    if (url.startsWith("stub:")) return { format: "module", shortCircuit: true, source: NAMES.map((n) => "export const " + n + " = () => { throw new Error('stub'); };").join("\\n") };
    return next(url, ctx);
  }`;
register("data:text/javascript," + encodeURIComponent(hooks), import.meta.url);

const { bindHost, unbindHost } = await import("../js/host.ts");
const { attachStorage, detachStorage, storage } = await import("../js/storage.ts");
const { initStorage } = await import("../js/state.ts");
const { bindThemes, unbindThemes, syncWallpaperTheme, themesAvailable } = await import("../js/wallpaperTheme.ts");
const { applyAppWallpaper, startNative } = await import("../js/engine/engine.ts");
const ok = (m) => console.log("  ok  " + m);

let wpPath = "/data/user/0/app/files/aegires-wp-1.jpg";
const calls = [];
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");
const bridge = async (name, args) => {
  calls.push([name, ...args]);
  switch (name) {
    case "aegires.ping": return "pong";
    case "aegires.argon2id": case "aegires.argon2with": return KAT_B64;
    case "aegires.argon2engine": return "c:arm64-v8a";
    case "aegires.wallpaperpath": return wpPath;
    default: return "ok";
  }
};
const api = (themes) => ({
  themes,
  jsonStorage: { get: async () => null, set: async () => {}, delete: async () => {} },
  unscoped: { modules: { native: { callNativeMethod: bridge } } },
  cleanup() {},
});
const flush = () => new Promise((r) => setTimeout(r, 20));

const registered = { groups: [], themes: [] };
const themes = {
  registerGroup: (id, g) => registered.groups.push([id, g]),
  registerTheme: (g, id, t) => registered.themes.push([g, id, t]),
};

bindHost(api(themes));
await attachStorage(api(themes).jsonStorage);
initStorage();

// no Themes API: nothing registered, the layer is the fallback
unbindThemes();
assert.equal(themesAvailable(), false);
await syncWallpaperTheme();
assert.equal(registered.themes.length, 0);
ok("without the Themes API nothing is registered");

// with the Themes API
bindThemes({ themes });
assert.equal(themesAvailable(), true);
await syncWallpaperTheme();
let [g, id, t] = registered.themes.at(-1);
assert.equal(g, "aegires");
assert.equal(id, "wallpaper");
assert.deepEqual(t.getColors({}), {});
assert.equal(t.background.path, wpPath);
assert.equal(t.background.dimming, 0.7);
assert.equal(t.background.blur, 0);
assert.equal(registered.groups.at(-1)[0], "aegires");
ok("theme registered with the picture path, medium strength, no blur");

storage.wallpaperStrength = 3;
storage.wallpaperBlur = true;
await syncWallpaperTheme();
t = registered.themes.at(-1)[2];
assert.equal(t.background.dimming, 0.5);
assert.equal(t.background.blur, 10);
storage.wallpaperStrength = 1;
await syncWallpaperTheme();
assert.equal(registered.themes.at(-1)[2].background.dimming, 0.85);
ok("strength and blur change the theme's dimming and blur");

wpPath = "";
await syncWallpaperTheme();
t = registered.themes.at(-1)[2];
assert.equal(t.background, undefined);
ok("no picture: the theme has no background");

wpPath = "/data/user/0/app/files/aegires-wp-2.jpg";
await syncWallpaperTheme();
assert.equal(registered.themes.at(-1)[2].background.path, wpPath);
ok("a new picture (new file name) is picked up");

// the layer on top of the chat only when the Themes API is missing
assert.equal(await startNative(), true);
storage.appWallpaper = true;
calls.length = 0;
applyAppWallpaper();
await flush();
assert.deepEqual(calls.find((c) => c[0] === "aegires.appwallpaper").slice(1), ["0", "1", "1"]);
ok("Themes API present: the layer on top stays off");
unbindThemes();
calls.length = 0;
applyAppWallpaper();
await flush();
assert.deepEqual(calls.find((c) => c[0] === "aegires.appwallpaper").slice(1), ["1", "1", "1"]);
ok("no Themes API: the layer is used");

await detachStorage();
unbindHost();
console.log("\nwallpaper theme tests passed.");
