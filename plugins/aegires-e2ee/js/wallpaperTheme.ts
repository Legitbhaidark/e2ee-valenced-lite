// The wallpaper and the color themes as real Valenced themes. When the Themes API plugin
// (valenced.api.themes) is installed, this plugin registers the group "Aegires" with:
//   - Wallpaper:           the picture chosen on the settings screen behind the whole app.
//   - Wallpaper + colors:  the same, and the app's colors follow the picture (accent and tinted greys).
//   - eight ready-made color themes (Midnight, AMOLED, Rose, ...), colors only.
// Valenced draws the picture through Discord's own theme pipeline (the surfaces of the app turn
// see-through), instead of a layer laid over the chat (AppWallpaper.kt, only the fallback without the
// Themes API). The user can pick a theme in Valenced's Themes settings, or with the buttons on this
// plugin's settings screen ([activateTheme]).
//
// Strength and blur are applied by registering the themes again: re-registering the selected theme
// refreshes it live, and re-registering at startup completes a selection that [activateTheme] saved.
import { nativeWallpaperPalette, nativeWallpaperPath } from "./engine/engine.ts";
import { storage } from "./storage.ts";
import { unscoped } from "./host.ts";
import { diag } from "./diag.ts";
import { PRESETS, PRESET_IDS, deriveRaw, familiesIn, recipeFromPalette, type Recipe } from "./themeColors.ts";

let themes: any = null;

export const THEME_GROUP = "aegires";
/** This plugin's id (manifest.json): Valenced keys a selected theme by plugin, group and theme. */
export const THEME_PLUGIN_ID = "io.github.system32crowdstrikewin11.aegires-e2ee";

/** "Subtle / Medium / Strong" as the share of the app's surfaces that stays opaque over the picture (Valenced calls it dimming). */
const DIMMING: Record<number, number> = { 1: 0.85, 2: 0.7, 3: 0.5 };
const BLUR = 10;

/** Remembers the Themes API if this plugin got it (it is an optional dependency). */
export function bindThemes(api: any): void {
  themes = api?.themes ?? null;
}

export function unbindThemes(): void {
  themes = null;
}

export const themesAvailable = (): boolean => themes != null;

/** Every theme of the group, for the buttons on the settings screen: [{ id, name }]. */
export function themeList(): Array<{ id: string; name: string }> {
  return [
    { id: "wallpaper", name: "Wallpaper" },
    { id: "wallpaper-colors", name: "Wallpaper + colors" },
    ...PRESET_IDS.map((id) => ({ id, name: PRESETS[id].name })),
  ];
}

/** getColors of a recipe-based theme. A dev build notes in Diagnostics what the recipe found in Discord's palette. */
function colorsOf(id: string, recipe: Recipe, discord: any): { raw: Record<string, string> } {
  const palette: Record<string, string> = discord?.raw ?? {};
  const raw = deriveRaw(palette, recipe);
  diag.installed["theme colors"] = `${id}: ${Object.keys(raw).length} of ${Object.keys(palette).length} raw colors changed` +
    (Object.keys(raw).length === 0 ? ` (no brand/grey family found; palette families: ${familiesIn(palette).join(", ") || "none"})` : "");
  return { raw };
}

/** (Re)registers the themes with the current picture and settings. Never throws. */
export async function syncWallpaperTheme(): Promise<void> {
  const t = themes;
  if (!t) return;
  try {
    const path = await nativeWallpaperPath();
    const palette = path ? await nativeWallpaperPalette().catch(() => null) : null;
    const level = Math.min(3, Math.max(1, Number(storage.wallpaperStrength) || 2));
    const background = path
      ? { background: { path, opacity: 1, blur: storage.wallpaperBlur === true ? BLUR : 0, dimming: DIMMING[level] } }
      : {};
    t.registerGroup(THEME_GROUP, { name: "Aegires", description: "Themes from the Aegires E2EE plugin" });
    t.registerTheme(THEME_GROUP, "wallpaper", {
      name: "Wallpaper",
      description: path
        ? "Your Aegires wallpaper behind the whole app. Choose the picture in the Aegires settings."
        : "Choose a picture in the Aegires settings (Appearance) first.",
      getColors: () => ({}),
      ...background,
    });
    const fromPicture = palette ? recipeFromPalette(palette) : PRESETS.midnight;
    t.registerTheme(THEME_GROUP, "wallpaper-colors", {
      name: "Wallpaper + colors",
      description: path
        ? "Your wallpaper behind the whole app, and the app's colors follow it: the accent and the tint of the greys come from the picture."
        : "Choose a picture in the Aegires settings (Appearance) first. Without one this uses the Midnight colors.",
      getColors: (discord: any) => colorsOf("wallpaper-colors", fromPicture, discord),
      ...background,
    });
    for (const id of PRESET_IDS) {
      const recipe = PRESETS[id];
      t.registerTheme(THEME_GROUP, id, {
        name: recipe.name,
        description: recipe.description,
        getColors: (discord: any) => colorsOf(id, recipe, discord),
      });
    }
  } catch (e) {
    console.log("[aegires-e2ee] theme registration failed", e);
  }
}

// ---------------------------------------------------------------- choosing a theme from this plugin

function valencedCall(method: string, args: unknown[]): Promise<unknown> {
  const call = unscoped().modules?.native?.callNativeMethod;
  if (typeof call !== "function") return Promise.reject(new Error("native bridge not available"));
  return Promise.resolve(call(method, args));
}

/**
 * Which theme Valenced has selected: "unavailable" (no Themes API), "none", "other" (a theme of another
 * plugin), an Aegires theme id ("wallpaper", "midnight", ...), or "unknown" (the state could not be read).
 */
export async function themeStatus(): Promise<string> {
  if (!themes) return "unavailable";
  try {
    const state = JSON.parse(String(await valencedCall("valenced.themes.getState", [])));
    const c = state?.colors;
    if (!c) return "none";
    return c.plugin === THEME_PLUGIN_ID && c.group === THEME_GROUP ? String(c.theme) : "other";
  } catch {
    return "unknown";
  }
}

/**
 * Selects one of this plugin's themes (or "off") in Valenced's saved theme state. Valenced applies a saved
 * selection when the app starts, and then this plugin registers the theme again, which makes Valenced work
 * out its colors from Discord's real palette. So a restart of Discord finishes the job.
 * Returns a short note for the settings screen.
 */
export async function activateTheme(id: string): Promise<string> {
  if (!themes) return "The Themes API plugin is not installed.";
  try {
    if (id === "off") {
      await valencedCall("valenced.themes.setColors", [null]);
      return "Theme removed. Restart Discord to apply.";
    }
    if (!themeList().some((t) => t.id === id)) return "Unknown theme.";
    const selection = { plugin: THEME_PLUGIN_ID, group: THEME_GROUP, theme: id, values: {} };
    await valencedCall("valenced.themes.setColors", [JSON.stringify(selection)]);
    return "Selected. Restart Discord to apply it.";
  } catch (e) {
    return "Could not select the theme: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 100);
  }
}
