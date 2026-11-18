// The wallpaper as a real Valenced theme. When the Themes API plugin (valenced.api.themes) is installed,
// the picture chosen on the settings screen is registered as the theme "Aegires > Wallpaper". Once the
// user picks it in Valenced's Themes settings, Valenced draws it through Discord's own theme pipeline:
// the surfaces of the app turn see-through and the picture is the background of the whole app, instead
// of a layer laid over the chat (that layer, AppWallpaper.kt, is only the fallback without the Themes API).
//
// Strength and blur are applied by registering the theme again; registering an already picked theme
// refreshes it. No picture: the theme is still listed, without a background.
import { nativeWallpaperPath } from "./engine/engine.ts";
import { storage } from "./storage.ts";

let themes: any = null;

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

/** (Re)registers the wallpaper theme with the current picture and settings. Never throws. */
export async function syncWallpaperTheme(): Promise<void> {
  const t = themes;
  if (!t) return;
  try {
    const path = await nativeWallpaperPath();
    const level = Math.min(3, Math.max(1, Number(storage.wallpaperStrength) || 2));
    t.registerGroup("aegires", { name: "Aegires", description: "Themes from the Aegires E2EE plugin" });
    t.registerTheme("aegires", "wallpaper", {
      name: "Wallpaper",
      description: path
        ? "Your Aegires wallpaper behind the whole app. Choose the picture in the Aegires settings."
        : "Choose a picture in the Aegires settings (Appearance) first.",
      getColors: () => ({}),
      ...(path ? { background: { path, opacity: 1, blur: storage.wallpaperBlur === true ? BLUR : 0, dimming: DIMMING[level] } } : {}),
    });
  } catch (e) {
    console.log("[aegires-e2ee] wallpaper theme failed", e);
  }
}
