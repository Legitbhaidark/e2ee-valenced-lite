package aegires.e2ee;

/**
 * Colour maths for the "Glass controls" look (Settings > Behaviour, needs Liquid glass): glass text
 * boxes, buttons and switches. Plain Java with no Android imports, so the readability rules are
 * unit-tested on a JVM. Colours are 0xAARRGGBB ints.
 *
 * The rule: text on a glass control must stay readable on ANY wallpaper. So every fill is checked
 * against the worst case, a pure white backdrop (a bright wallpaper under a frosted card) with the
 * brightest highlight the glass drawing adds on top, and is made more opaque, then darker, until the
 * text reaches the contrast target:
 *   text boxes   7:1  (WCAG AAA)
 *   button text  4.5:1 (WCAG AA)
 *   shapes       3:1  (a switch bead against its track)
 * On a dark wallpaper the controls simply end up a bit darker than they need to be.
 */
public final class GlassMath {
    private GlassMath() {}

    /** The brightest thing a control can sit on: a white wallpaper under a frosted card. */
    public static final int WORST_BACKDROP = 0xFFFFFFFF;
    public static final int WHITE = 0xFFFFFFFF;
    /** The settings screen's text colour (SettingsScreen.TEXT). */
    public static final int TEXT = 0xFFDBDEE1;
    /** Placeholder text in glass text boxes (a bit lighter than the usual muted grey). */
    public static final int HINT = 0xFFB9BEC6;
    /** The most white the glass drawing puts over a fill, as the alpha of white. Keep in step with ControlDrawable. */
    public static final int HIGHLIGHT = 0x22;

    public static final double TEXT_BOX_CONTRAST = 7.0;
    public static final double LABEL_CONTRAST = 4.5;
    public static final double SHAPE_CONTRAST = 3.0;

    public static final int ACCENT = 0x5865F2;
    public static final int DANGER = 0xDA373C;
    private static final int NEUTRAL_BASE = 0x1B1C20;
    private static final int FIELD_BASE = 0x0E0F12;

    /** Switch bead: lightest at the top, a little darker at the bottom. */
    public static final int THUMB_TOP = 0xFFFFFFFF;
    public static final int THUMB_BOTTOM = 0xFFE3E6EB;

    // ------------------------------------------------------------------ colour maths

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance of the RGB part of a colour. */
    public static double luminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 0xFF) + 0.7152 * linear((argb >> 8) & 0xFF) + 0.0722 * linear(argb & 0xFF);
    }

    /** WCAG contrast ratio between two opaque colours (1 to 21). */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** [fg] (with its own alpha) painted over the opaque [bg], the way Android blends it. Result is opaque. */
    public static int over(int fg, int bg) {
        int a = fg >>> 24;
        int r = ((fg >> 16 & 0xFF) * a + (bg >> 16 & 0xFF) * (255 - a)) / 255;
        int g = ((fg >> 8 & 0xFF) * a + (bg >> 8 & 0xFF) * (255 - a)) / 255;
        int b = ((fg & 0xFF) * a + (bg & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Linear mix of two colours' RGB: t = 0 gives a, t = 1 gives b. Alpha of a is kept. */
    public static int mix(int a, int b, float t) {
        int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = (int) ((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    /** The lightest a fill can look: over the worst backdrop, with the brightest highlight on top. */
    public static int lightest(int fill) {
        return over((HIGHLIGHT << 24) | 0xFFFFFF, over(fill, WORST_BACKDROP));
    }

    // ------------------------------------------------------------------ readable fills

    /**
     * The fill for a control whose text is [textRgb]: starts at [baseRgb] with [alpha], becomes more
     * opaque until the text reaches [minContrast] in the lightest case, and if even a fully opaque fill
     * is not enough the colour is darkened a little and tried again.
     */
    public static int readableFill(int baseRgb, int alpha, int textRgb, double minContrast) {
        int rgb = baseRgb & 0xFFFFFF;
        for (int step = 0; step < 300; step++) {
            for (int a = alpha; ; a += 4) {
                int aa = Math.min(a, 255);
                int fill = (aa << 24) | rgb;
                if (contrast(textRgb, lightest(fill)) >= minContrast) return fill;
                if (aa == 255) break;
            }
            rgb = mix(rgb, 0, 0.05f) & 0xFFFFFF;
        }
        return 0xFF000000;
    }

    /** Text box fill (text colour [TEXT], 7:1). */
    public static int fieldFill() {
        return readableFill(FIELD_BASE, 0xD0, TEXT, TEXT_BOX_CONTRAST);
    }

    /** Neutral button / unchecked switch track fill (white text, 4.5:1). */
    public static int neutralFill() {
        return readableFill(NEUTRAL_BASE, 0xD0, WHITE, LABEL_CONTRAST);
    }

    /** Tinted button / checked switch track fill (white text, 4.5:1): the tint, deepened only as far as it must be. */
    public static int tintedFill(int tintRgb) {
        return readableFill(tintRgb, 0xE8, WHITE, LABEL_CONTRAST);
    }

    /** A pressed button: the fill a little darker (darker never lowers the contrast). */
    public static int pressed(int fill) {
        return mix(fill, 0xFF000000, 0.18f);
    }
}
