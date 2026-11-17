package aegires.e2ee;

import java.util.Locale;

/**
 * The text of the Settings > Crypto benchmark card. Argon2id, HKDF-SHA256 and AES-256-GCM are timed
 * here on the Java and C engines (median of 3 runs, timed inside this class, so the bridge is not in
 * the numbers). Plain JS can only be timed by the JS side, which hands its result in; everything else
 * (availability checks, "match" check, speedups, formatting) is done here.
 *
 * An engine that cannot run on this phone is skipped with the reason, and the other tests still run.
 * Pure Java on purpose (no lambdas, no newer APIs), like the other Java files.
 */
public final class CryptoBench {
    private CryptoBench() {}

    static final int HKDF_N = 2000;
    static final int AES_N = 150;
    private static final int RUNS = 3;

    private static final int ARGON = 0, HKDF = 1, AES = 2;

    /** One finished row: time in ms and the head of the output (for the match check). */
    private static final class Timing {
        final double ms;
        final String head;
        Timing(double ms, String head) { this.ms = ms; this.head = head; }
    }

    /** 1.2 ms / 85.3 ms / 1.25 s: always enough digits to compare, from microseconds to minutes. */
    public static String fmtMs(double ms) {
        if (ms < 1) return Math.round(ms * 1000) + " us";
        if (ms < 10) return String.format(Locale.US, "%.2f ms", ms);
        if (ms < 1000) return String.format(Locale.US, "%.1f ms", ms);
        if (ms < 60000) return String.format(Locale.US, "%.2f s", ms / 1000);
        return (long) (ms / 60000) + " min " + Math.round((ms % 60000) / 1000) + " s";
    }

    private static String times(double a, double b) {
        double x = a / b;
        return "x" + String.format(Locale.US, x >= 100 ? "%.0f" : x >= 10 ? "%.1f" : "%.2f", x);
    }

    private static String oneLine(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int nl = m.indexOf('\n');
        if (nl >= 0) m = m.substring(0, nl);
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    /** "<hash head>;<ns>,<ns>,..." from NativeOps -> median ms and head. */
    private static Timing parse(String reply) {
        String[] parts = reply.split(";");
        String[] ns = parts[1].split(",");
        double[] ms = new double[ns.length];
        for (int i = 0; i < ns.length; i++) ms[i] = Long.parseLong(ns[i]) / 1e6;
        java.util.Arrays.sort(ms);
        return new Timing(ms[ms.length / 2], parts[0]);
    }

    private static Timing runLeg(int kind, String engine, int memKiB) {
        String reply;
        if (kind == ARGON) reply = NativeOps.argon2Bench(engine, "3", Integer.toString(memKiB), Integer.toString(RUNS));
        else if (kind == HKDF) reply = NativeOps.hkdfBench(engine, Integer.toString(HKDF_N), Integer.toString(RUNS));
        else reply = NativeOps.aesBench(Integer.toString(AES_N), Integer.toString(RUNS));
        return parse(reply);
    }

    /**
     * @param js nine strings from the JS side: for Argon2id, HKDF and AES in turn, the plain JS time in
     *           ms ("-1" when it did not run), the head of its output (hex) and, when it did not run, why.
     */
    public static String report(int memMiB, String[] js) {
        StringBuilder out = new StringBuilder();
        boolean[] cWorked = new boolean[3];
        String[] titles = {
            "Argon2id (3, " + memMiB + " MiB, 32B)",
            "HKDF-SHA256 extract+expand x" + HKDF_N,
            "AES-256-GCM 64KiB enc+dec x" + AES_N,
        };
        for (int kind = 0; kind < 3; kind++) {
            if (kind > 0) out.append('\n');
            out.append(titles[kind]).append('\n');

            // The three engines: plain JS (handed in), Java and C.
            Timing jsT = null, javaT = null, cT = null;
            String jsWhy = null, javaWhy = null, cWhy = null;
            double jsMs = Double.parseDouble(js[kind * 3]);
            if (jsMs >= 0) jsT = new Timing(jsMs, js[kind * 3 + 1]);
            else jsWhy = js[kind * 3 + 2].length() == 0 ? "not run" : js[kind * 3 + 2];

            try { javaT = runLeg(kind, "java", memMiB * 1024); } catch (Throwable t) { javaWhy = oneLine(t); }
            if (kind == AES) {
                cWhy = null; // no C version: printed as a note below
            } else if (!NativeArgon2.isReady()) {
                cWhy = "C library not loaded: " + NativeArgon2.status();
            } else if (kind == HKDF && !NativeArgon2.isHkdfReady()) {
                cWhy = "C HKDF not available";
            } else {
                try { cT = runLeg(kind, "c", memMiB * 1024); } catch (Throwable t) { cWhy = oneLine(t); }
            }

            Timing ref = javaT != null ? javaT : cT != null ? cT : jsT;
            String javaName = kind == AES ? "Java/Conscrypt" : "Java";
            if (kind != ARGON) row(out, "Plain JS", jsT, jsWhy, ref, ""); // no JS Argon2id any more
            row(out, javaName, javaT, javaWhy, ref, kind != ARGON && jsT != null && javaT != null ? "  " + times(jsT.ms, javaT.ms) + " vs JS" : "");
            if (kind == AES) out.append("  Native: n/a (no C version, Java only)\n");
            else row(out, "Native", cT, cWhy, ref, javaT != null && cT != null ? "  " + times(javaT.ms, cT.ms) + " vs Java" : "");
            cWorked[kind] = cT != null;
        }

        String status = NativeArgon2.status(); // "c:<abi>" or "java:<why>"
        String engine = status.startsWith("c:") ? "C library (" + status.substring(2) + ")" : "Java (" + status.replaceFirst("^java:", "") + ")";
        out.append('\n').append("Native: ").append(engine)
                .append(" | Argon2id=").append(cWorked[ARGON] ? "native" : "Java")
                .append(" HKDF=").append(cWorked[HKDF] ? "native" : "Java")
                .append(" AES=Java");
        return out.toString();
    }

    private static void row(StringBuilder out, String name, Timing t, String why, Timing ref, String extra) {
        out.append("  ").append(name).append(": ");
        if (t == null) {
            out.append("skipped (").append(why).append(")\n");
            return;
        }
        out.append(fmtMs(t.ms)).append(ref == null || t.head.equals(ref.head) ? " (match)" : " (MISMATCH!)").append(extra).append('\n');
    }
}
