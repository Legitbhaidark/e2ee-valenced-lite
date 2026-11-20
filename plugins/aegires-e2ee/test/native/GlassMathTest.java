package aegires.e2ee;

/**
 * Readability rules of the glass controls (no Android). From the plugin folder:
 *   javac -d /tmp/gm src/main/java/aegires/e2ee/GlassMath.java test/native/GlassMathTest.java && java -cp /tmp/gm aegires.e2ee.GlassMathTest
 */
public class GlassMathTest {
    static int fails = 0;

    static void check(boolean ok, String msg) {
        if (ok) System.out.println("  ok  " + msg);
        else {
            fails++;
            System.out.println("  FAIL " + msg);
        }
    }

    static String hex(int c) {
        return String.format("#%08X", c);
    }

    static double ratio(int text, int fill) {
        return GlassMath.contrast(text, GlassMath.lightest(fill));
    }

    static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    public static void main(String[] args) {
        // the maths
        check(near(GlassMath.contrast(0xFF000000, 0xFFFFFFFF), 21.0, 0.001), "black on white is 21:1");
        check(near(GlassMath.contrast(0xFF777777, 0xFFFFFFFF), 4.48, 0.02), "#777 on white is about 4.48:1 (known value)");
        check(GlassMath.over(0x80FFFFFF, 0xFF000000) == 0xFF808080, "half white over black is grey");
        check(GlassMath.over(0xFF123456, 0xFFABCDEF) == 0xFF123456, "opaque paint hides the backdrop");
        check(GlassMath.mix(0xFF5865F2, 0xFF000000, 0f) == 0xFF5865F2 && (GlassMath.mix(0xFF5865F2, 0xFF000000, 1f) & 0xFFFFFF) == 0, "mix ends");

        // every fill reaches its target in the WORST case (white backdrop + highlight)
        int field = GlassMath.fieldFill();
        int neutral = GlassMath.neutralFill();
        int accent = GlassMath.tintedFill(GlassMath.ACCENT);
        int danger = GlassMath.tintedFill(GlassMath.DANGER);
        System.out.println("  fills: field " + hex(field) + ", neutral " + hex(neutral) + ", accent " + hex(accent) + ", danger " + hex(danger));
        check(ratio(GlassMath.TEXT, field) >= 7.0, "text box: text >= 7:1 (" + String.format("%.2f", ratio(GlassMath.TEXT, field)) + ")");
        check(ratio(GlassMath.HINT, field) >= 4.5, "text box: placeholder >= 4.5:1 (" + String.format("%.2f", ratio(GlassMath.HINT, field)) + ")");
        check(ratio(GlassMath.WHITE, neutral) >= 4.5, "neutral button: label >= 4.5:1 (" + String.format("%.2f", ratio(GlassMath.WHITE, neutral)) + ")");
        check(ratio(GlassMath.WHITE, accent) >= 4.5, "accent button: label >= 4.5:1 (" + String.format("%.2f", ratio(GlassMath.WHITE, accent)) + ")");
        check(ratio(GlassMath.WHITE, danger) >= 4.5, "danger button: label >= 4.5:1 (" + String.format("%.2f", ratio(GlassMath.WHITE, danger)) + ")");
        check(ratio(GlassMath.WHITE, GlassMath.pressed(accent)) >= 4.5 && ratio(GlassMath.WHITE, GlassMath.pressed(neutral)) >= 4.5, "pressed buttons stay readable");

        // the switch: the bead must stand out from both tracks (3:1), in the lightest case
        for (int track : new int[] {neutral, accent}) {
            double r = GlassMath.contrast(GlassMath.THUMB_BOTTOM, GlassMath.lightest(track));
            check(r >= GlassMath.SHAPE_CONTRAST, "switch bead vs track " + hex(track) + " >= 3:1 (" + String.format("%.2f", r) + ")");
        }
        check(GlassMath.contrast(GlassMath.lightest(neutral), GlassMath.lightest(accent)) > 1.0 || neutral != accent, "checked and unchecked tracks differ");

        // the colour stays recognisable: tinted fills keep their hue direction
        check((accent >> 16 & 0xFF) < (accent & 0xFF) && (danger >> 16 & 0xFF) > (danger & 0xFF), "accent stays bluish, danger stays reddish");

        // a fill that is already readable is returned unchanged
        check(GlassMath.readableFill(0x000000, 0xFF, GlassMath.WHITE, 4.5) == 0xFF000000, "black stays black");
        check(GlassMath.readableFill(0x101010, 0xF0, GlassMath.WHITE, 4.5) == 0xF0101010, "an already dark fill is not changed");

        // a dark backdrop only makes things easier
        int darkBackdrop = 0xFF101114;
        for (int fill : new int[] {field, neutral, accent, danger}) {
            int eff = GlassMath.over((GlassMath.HIGHLIGHT << 24) | 0xFFFFFF, GlassMath.over(fill, darkBackdrop));
            check(GlassMath.contrast(GlassMath.WHITE, eff) >= GlassMath.contrast(GlassMath.WHITE, GlassMath.lightest(fill)), "dark backdrop is never worse: " + hex(fill));
        }

        // an impossible request ends in black instead of looping forever
        check(GlassMath.readableFill(0xFFFFFF, 0x10, 0xFF050505, 21.0) == 0xFF000000 || GlassMath.contrast(0xFF050505, GlassMath.lightest(GlassMath.readableFill(0xFFFFFF, 0x10, 0xFF050505, 21.0))) >= 1.0, "impossible target terminates");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
