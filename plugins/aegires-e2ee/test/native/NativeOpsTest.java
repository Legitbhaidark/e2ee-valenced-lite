import aegires.e2ee.NativeOps;
import java.lang.reflect.Method;
import java.util.Base64;

/**
 * Checks the Java Argon2id (copied from Aegires) against an INDEPENDENT implementation:
 * the expected values below were produced by Python's `cryptography` Argon2id (libargon2/OpenSSL).
 * Includes the real parameters (t=3, 64 MiB) used for Aegires v2 keys.
 *
 * From the plugin folder:
 *   javac -d /tmp/an src/main/java/aegires/e2ee/*.java test/native/NativeOpsTest.java && java -cp /tmp/an NativeOpsTest
 */
public class NativeOpsTest {
    public record Case(String name, String pwB64, String saltB64, int t, int mKiB, int len, String expectedHex) {}

    public static final Case[] CASES = {
        new Case("small, 3 passes", "Y29ycmVjdCBob3JzZQ==", "ZGNlbmMyfHVzZXIta2V5fHYx", 3, 1024, 32, "cf3f019e5f1dfc71614a559a339682b69ed4084f7d87aa1534a2305e7d54cf72"),
        new Case("unicode password (NFKC)", "cMOkc3N3w7ZyZOKckyBmaQ==", "ZGNlbmMyfGN0eHxmb3JjZWR8MTIzNDU2Nzg5MDEyMzQ1Njc4", 3, 512, 32, "fb9757d1e3c2abc57d3f4ff5499fff88d20d1e5e96230167214f0d4e0a06b584"),
        new Case("1 pass, 64-byte output", "eA==", "c2FsdHNhbHRzYWx0", 1, 64, 64, "c88424fc70ca5f656e5f73335e0921b5e37ae43b552b4bdc354979bfe4454019dd82618af7cd8fd59be8315d334731c35bbac01f9f17372506d2426bac465654"),
        new Case("output over 64 bytes", "bG9uZ2VyIG91dHB1dA==", "YW5vdGhlciBzYWx0IQ==", 2, 128, 100, "0726849ee8db1d367676982cdd3ec59131b23cb3c738edeb355e6f265e08fac5be380c3ea88e2d53911d35dbc315442f3852b230c51b7b500cf9208df692087f30ab85e2c5598a3c56a242874808fe83c7add372bf70b4ca86a6eada7dc058de15bfcce4"),
        new Case("real parameters 64 MiB", "bXkgcmVhbCBwYXNzcGhyYXNl", "ZGNlbmMyfHVzZXIta2V5fHYx", 3, 65536, 32, "26e5bedd20ffad0d008e27362d6ee88a7ae5ae158fdf8f29e72093c4edcb2adf"),
    };

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        System.out.println("  ok  " + msg);
    }

    static void rejects(String msg, Runnable r) {
        try { r.run(); } catch (RuntimeException e) { System.out.println("  ok  " + msg); return; }
        throw new AssertionError(msg + " (was accepted)");
    }

    public static void main(String[] args) throws Exception {
        for (Case c : CASES) {
            long t0 = System.nanoTime();
            String outB64 = NativeOps.argon2id(c.pwB64(), c.saltB64(), "" + c.t(), "" + c.mKiB(), "" + c.len());
            String got = hex(Base64.getDecoder().decode(outB64));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(got.equals(c.expectedHex()), "argon2id == independent reference: " + c.name() + " (" + ms + " ms)");
        }

        // own base64 codec == java.util.Base64, all remainders and every byte value
        java.util.Random rnd = new java.util.Random(1);
        Method enc = NativeOps.class.getDeclaredMethod("encode", byte[].class);
        Method dec = NativeOps.class.getDeclaredMethod("decode", String.class);
        enc.setAccessible(true);
        dec.setAccessible(true);
        for (int len = 0; len < 300; len++) {
            byte[] b = new byte[len];
            rnd.nextBytes(b);
            String s = (String) enc.invoke(null, (Object) b);
            if (!(s.equals(Base64.getEncoder().encodeToString(b)) && java.util.Arrays.equals(b, (byte[]) dec.invoke(null, s)))) throw new AssertionError("base64 len " + len);
        }
        System.out.println("  ok  base64 codec matches java.util.Base64 for lengths 0..299");

        rejects("memory above the cap is refused", () -> NativeOps.argon2id("eA==", "c2FsdHNhbHRzYWx0", "3", "" + (256 * 1024 + 1), "32"));
        rejects("passes 0 is refused", () -> NativeOps.argon2id("eA==", "c2FsdHNhbHRzYWx0", "0", "1024", "32"));
        rejects("bad base64 is refused", () -> NativeOps.argon2id("!!!!", "c2FsdHNhbHRzYWx0", "3", "1024", "32"));
        rejects("short salt is refused", () -> NativeOps.argon2id("eA==", "YQ==", "3", "1024", "32"));
        System.out.println("\nnative tests passed.");
    }
}
