// Color recipes for the Aegires themes (Valenced Themes API) and the math behind them.
//
// A recipe does not list Discord's colors. Valenced hands a theme Discord's raw palette (name -> "#RRGGBB"),
// and a recipe re-colors whole families of it at once: the brand ramp (BRAND_*, BLURPLE_*) gets the accent's
// hue and the grey ramp (PRIMARY_*, NEUTRAL_*, GRAY_*) leans towards a tint. Every semantic color of the app is
// built from the raw palette, so one change re-colors buttons, links, backgrounds and panels together, and it
// keeps working when Discord renames a semantic color. Lightness is never touched (except "deepen"), so the
// contrast Discord designed stays.

export interface Recipe {
  name: string;
  description: string;
  /** The accent (brand) color, "#RRGGBB". */
  accent: string;
  /** The color the greys of the app lean towards, "#RRGGBB"; null leaves the greys' hue alone. */
  tint: string | null;
  /** 0..1: how strongly the greys lean towards the tint. */
  tintAmount: number;
  /** 0..1: how far the darkest greys are pushed towards pure black (an AMOLED look). */
  deepen: number;
}

/** What the native half reads from the wallpaper. */
export interface Palette {
  dominant: string;
  accent: string;
}

export interface Rgb {
  r: number;
  g: number;
  b: number;
  /** Two hex digits when the color had an alpha, else "". */
  a: string;
}

export function parseHex(h: string): Rgb | null {
  const m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(typeof h === "string" ? h.trim() : "");
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: m[2] ?? "" };
}

const hex2 = (v: number): string => Math.round(Math.min(255, Math.max(0, v))).toString(16).padStart(2, "0");
export const toHex = (r: number, g: number, b: number, a = ""): string => "#" + hex2(r) + hex2(g) + hex2(b) + a;

/** h in degrees 0..360, s and l in 0..1. */
export function rgbToHsl(r: number, g: number, b: number): [number, number, number] {
  const R = r / 255, G = g / 255, B = b / 255;
  const max = Math.max(R, G, B), min = Math.min(R, G, B);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return [0, 0, l];
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h: number;
  if (max === R) h = (G - B) / d + (G < B ? 6 : 0);
  else if (max === G) h = (B - R) / d + 2;
  else h = (R - G) / d + 4;
  return [h * 60, s, l];
}

export function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const hh = (((h % 360) + 360) % 360) / 360;
  if (s === 0) return [l * 255, l * 255, l * 255];
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const ch = (t: number): number => {
    let x = t;
    if (x < 0) x += 1;
    if (x > 1) x -= 1;
    if (x < 1 / 6) return p + (q - p) * 6 * x;
    if (x < 1 / 2) return q;
    if (x < 2 / 3) return p + (q - p) * (2 / 3 - x) * 6;
    return p;
  };
  return [ch(hh + 1 / 3) * 255, ch(hh) * 255, ch(hh - 1 / 3) * 255];
}

const BRAND_FAMILY = /^(BRAND|BLURPLE)(_|$)/;
const GREY_FAMILY = /^(PRIMARY|NEUTRAL|GRAY|GREY)(_|$)/;
const BLURPLE_SATURATION = 0.86; // the saturation of Discord's own brand ramp, the reference for "as saturated as the accent"

/** Which family a raw color name belongs to: "brand", "grey" or "" (left alone: reds, greens, whites...). */
export function familyOf(name: string): "brand" | "grey" | "" {
  if (BRAND_FAMILY.test(name)) return "brand";
  if (GREY_FAMILY.test(name)) return "grey";
  return "";
}

/**
 * The raw colors a recipe changes, as name -> "#RRGGBB[AA]". Only changed entries are returned, so an empty
 * result means the palette had no family the recipe knows (the Diagnostics line of a dev build says so).
 */
export function deriveRaw(raw: Record<string, string>, recipe: Recipe): Record<string, string> {
  const out: Record<string, string> = {};
  const acc = parseHex(recipe.accent);
  const tint = recipe.tint ? parseHex(recipe.tint) : null;
  const [accH, accS] = acc ? rgbToHsl(acc.r, acc.g, acc.b) : [0, 0, 0];
  const [tintH, tintS] = tint ? rgbToHsl(tint.r, tint.g, tint.b) : [0, 0, 0];
  for (const [name, value] of Object.entries(raw ?? {})) {
    const family = familyOf(name);
    if (!family) continue;
    const c = parseHex(value);
    if (!c) continue;
    const [h, s, l] = rgbToHsl(c.r, c.g, c.b);
    let next: [number, number, number] | null = null;
    if (family === "brand" && acc) {
      next = [accH, Math.min(1, s * (accS / BLURPLE_SATURATION)), l];
    } else if (family === "grey") {
      let nl = l;
      if (recipe.deepen > 0 && l < 0.3) nl = l * (1 - 0.9 * Math.min(1, recipe.deepen));
      let nh = h;
      let ns = s;
      if (tint) {
        nh = tintH;
        // Dark greys (backgrounds) take the tint; light ones (text) only a little of it.
        ns = Math.min(0.5, tintS * recipe.tintAmount * (l < 0.5 ? 1 : 0.4));
      }
      next = [nh, ns, nl];
    }
    if (!next) continue;
    const [r, g, b] = hslToRgb(next[0], next[1], next[2]);
    const hex = toHex(r, g, b, c.a);
    if (hex.toLowerCase() !== value.trim().toLowerCase()) out[name] = hex;
  }
  return out;
}

/** The recipe ids of the ready-made themes, in the order the pickers show them. */
export const PRESET_IDS = ["midnight", "amoled", "rose", "forest", "ocean", "sunset", "lavender", "graphite"] as const;

export const PRESETS: Record<string, Recipe> = {
  midnight: { name: "Midnight", description: "Deep blue-black with a soft blue accent.", accent: "#6C8CFF", tint: "#3B4A8C", tintAmount: 0.55, deepen: 0.2 },
  amoled: { name: "AMOLED", description: "Pure black backgrounds, Discord's own blurple.", accent: "#5865F2", tint: null, tintAmount: 0, deepen: 1 },
  rose: { name: "Rose", description: "Dark plum greys and a rose accent.", accent: "#FF6B9A", tint: "#7A2E4A", tintAmount: 0.5, deepen: 0.1 },
  forest: { name: "Forest", description: "Dark green greys and a fresh green accent.", accent: "#3FCF86", tint: "#1F4A38", tintAmount: 0.5, deepen: 0.1 },
  ocean: { name: "Ocean", description: "Deep teal greys and a bright cyan accent.", accent: "#2FB6FF", tint: "#0F4C6B", tintAmount: 0.55, deepen: 0.15 },
  sunset: { name: "Sunset", description: "Warm dark greys and an orange accent.", accent: "#FF8A4C", tint: "#6B2E1F", tintAmount: 0.45, deepen: 0.1 },
  lavender: { name: "Lavender", description: "Dusky purple greys and a lavender accent.", accent: "#B48CFF", tint: "#3F2E6B", tintAmount: 0.5, deepen: 0.1 },
  graphite: { name: "Graphite", description: "Neutral dark greys with a silver accent.", accent: "#A8ADB8", tint: "#808080", tintAmount: 0, deepen: 0.3 },
};

/** The recipe for "Wallpaper + colors": the picture's accent, and greys leaning towards its average color. */
export function recipeFromPalette(p: Palette): Recipe {
  return {
    name: "Wallpaper + colors",
    description: "Colors taken from your wallpaper.",
    accent: p.accent,
    tint: p.dominant,
    tintAmount: 0.65,
    deepen: 0.15,
  };
}

/** The raw families found in a palette (names without their trailing shade number), for the dev Diagnostics. */
export function familiesIn(raw: Record<string, string>): string[] {
  const set = new Set<string>();
  for (const name of Object.keys(raw ?? {})) set.add(name.replace(/_?\d+$/, ""));
  return [...set].sort();
}
