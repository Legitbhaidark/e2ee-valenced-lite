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
const { bindThemes, unbindThemes, syncWallpaperTheme, themesAvailable, themeStatus, activateTheme, themeList, THEME_PLUGIN_ID } = await import("../js/wallpaperTheme.ts");
const { applyAppWallpaper, startNative } = await import("../js/engine/engine.ts");
const ok = (m) => console.log("  ok  " + m);

let wpPath = "/data/user/0/app/files/aegires-wp-1.jpg";
let themeState = null; // what Valenced has saved as the selected color theme
const calls = [];
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");
const bridge = async (name, args) => {
  calls.push([name, ...args]);
  switch (name) {
    case "aegires.ping": return "pong";
    case "aegires.argon2id": case "aegires.argon2with": return KAT_B64;
    case "aegires.argon2engine": return "c:arm64-v8a";
    case "aegires.wallpaperpath": return wpPath;
    case "aegires.wallpaperpalette": return wpPath ? JSON.stringify({ dominant: "#304060", accent: "#4F8CFF" }) : "";
    case "valenced.themes.getState": return JSON.stringify({ colors: themeState });
    case "valenced.themes.setColors": themeState = args[0] ? JSON.parse(args[0]) : null; return "ok";
    default: return "ok";
  }
};
const api = (themes) => ({
  themes,
  jsonStorage: { get: async () => null, set: async () => {}, delete: async () => {} },
  unscoped: { modules: { native: { callNativeMethod: bridge } } },
  cleanup() {},
});
const last = (id) => registered.themes.findLast((x) => x[1] === id);
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
let [g, id, t] = last("wallpaper");
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
t = last("wallpaper")[2];
assert.equal(t.background.dimming, 0.5);
assert.equal(t.background.blur, 10);
storage.wallpaperStrength = 1;
await syncWallpaperTheme();
assert.equal(last("wallpaper")[2].background.dimming, 0.85);
ok("strength and blur change the theme's dimming and blur");

wpPath = "";
await syncWallpaperTheme();
t = last("wallpaper")[2];
assert.equal(t.background, undefined);
ok("no picture: the theme has no background");

wpPath = "/data/user/0/app/files/aegires-wp-2.jpg";
await syncWallpaperTheme();
assert.equal(last("wallpaper")[2].background.path, wpPath);
ok("a new picture (new file name) is picked up");


// the color themes
{
  const raw = { BRAND_500: "#5865F2", BRAND_360: "#7983F5", PRIMARY_800: "#2B2D31", PRIMARY_100: "#F2F3F5", RED_400: "#ED4245", WHITE_500: "#FFFFFF" };
  const wc = last("wallpaper-colors")[2];
  const colors = wc.getColors({ raw });
  assert.ok(wc.background.path, "wallpaper + colors carries the picture too");
  assert.ok(colors.raw.BRAND_500 && colors.raw.BRAND_500.toLowerCase() !== "#5865f2", "brand ramp re-colored");
  assert.ok(colors.raw.PRIMARY_800, "grey ramp re-colored");
  assert.equal(colors.raw.RED_400, undefined, "status colors untouched");
  assert.equal(colors.raw.WHITE_500, undefined, "white untouched");
  ok("Wallpaper + colors: brand and grey ramps follow the picture, reds and white stay");
  const ids = themeList().map((x) => x.id);
  assert.deepEqual(ids, ["wallpaper", "wallpaper-colors", "midnight", "amoled", "rose", "forest", "ocean", "sunset", "lavender", "graphite"]);
  for (const id of ids.slice(2)) {
    const th = last(id)[2];
    assert.equal(th.background, undefined, id + " is colors only");
    assert.ok(th.name && th.description);
    assert.ok(Object.keys(th.getColors({ raw }).raw).length > 0, id + " changes colors");
  }
  ok("eight ready-made color themes are registered, colors only");
  assert.equal(Object.keys(last("amoled")[2].getColors({ raw }).raw).length > 0, true);
  const dark = last("amoled")[2].getColors({ raw: { PRIMARY_800: "#2B2D31" } }).raw.PRIMARY_800;
  assert.ok(parseInt(dark.slice(1, 3), 16) < 12 && parseInt(dark.slice(3, 5), 16) < 12 && parseInt(dark.slice(5, 7), 16) < 12, "near black: " + dark);
  ok("AMOLED pushes the dark greys to near black");
}

// choosing a theme from this plugin's settings screen
{
  assert.equal(await themeStatus(), "none");
  assert.match(await activateTheme("midnight"), /Restart Discord/);
  assert.deepEqual(themeState, { plugin: THEME_PLUGIN_ID, group: "aegires", theme: "midnight", values: {} });
  assert.equal(await themeStatus(), "midnight");
  themeState = { plugin: "some.other.plugin", group: "x", theme: "y", values: {} };
  assert.equal(await themeStatus(), "other");
  assert.equal(await activateTheme("nope"), "Unknown theme.");
  assert.match(await activateTheme("off"), /removed/);
  assert.equal(themeState, null);
  assert.equal(await themeStatus(), "none");
  ok("themes are selected through Valenced's saved state, off clears it, unknown ids are refused");
  unbindThemes();
  assert.equal(await themeStatus(), "unavailable");
  assert.match(await activateTheme("midnight"), /not installed/);
  bindThemes({ themes });
  ok("without the Themes API the status says so and nothing is written");
}

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
