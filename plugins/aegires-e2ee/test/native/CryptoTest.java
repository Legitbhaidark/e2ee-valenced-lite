import e2ee.lite.Crypto;
import e2ee.lite.NativeOps;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Round-trips the Java Crypto (copied from Aegires) and the NativeOps wrappers the bridge calls,
 * and checks that the context binding rejects other channels and senders.
 *
 *   javac -d /tmp/ct src/main/java/e2ee/lite/*.java test/native/CryptoTest.java && java -cp /tmp/ct CryptoTest
 */
public class CryptoTest {
    static int failed = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "ok   " : "FAIL ") + msg);
        if (!ok) failed++;
    }

    public static void main(String[] a) {
        byte[] mk = new byte[32];
        for (int i = 0; i < 32; i++) mk[i] = (byte) i;
        SecretKey master = new SecretKeySpec(mk, "HKDF");
        String ch = "123456789012345678", me = "876543210987654321";
        String text = "h\u00e9llo \u00a7 \u2713 \uD83D\uDD12 self-check";

        for (Crypto.Preset p : Crypto.Preset.values()) {
            String w = Crypto.encrypt(text, master, p, ch, me);
            check(w != null && w.startsWith("\u00a7"), p.label + " encrypts");
            check(text.equals(Crypto.decrypt(w, master, ch, me)), p.label + " round trip");
            check(Crypto.decrypt(w, master, "999", me) == null, p.label + " wrong channel rejected");
            check(Crypto.decrypt(w, master, ch, "999") == null, p.label + " wrong sender rejected");
            check(!w.equals(Crypto.encrypt(text, master, p, ch, me)), p.label + " fresh salt each time");
        }
        check("".equals(Crypto.decrypt(Crypto.encrypt("", master, Crypto.Preset.AES256, ch, me), master, ch, me)), "empty message");

        // The wrappers the bridge calls: base64 in, base64 out.
        String mb = Base64.getEncoder().encodeToString(mk);
        String pb = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        for (int preset = 0; preset < 3; preset++) {
            String w = NativeOps.v2Encrypt(mb, String.valueOf(preset), ch, me, pb);
            String back = new String(Base64.getDecoder().decode(NativeOps.v2Decrypt(mb, ch, me, w)), StandardCharsets.UTF_8);
            check(text.equals(back), "NativeOps preset " + preset + " round trip");
        }
        try { NativeOps.v2Decrypt(mb, "999", me, NativeOps.v2Encrypt(mb, "2", ch, me, pb)); check(false, "bad context throws"); }
        catch (IllegalArgumentException e) { check(true, "bad context throws"); }
        try { NativeOps.v2Encrypt(mb, "7", ch, me, pb); check(false, "bad preset throws"); }
        catch (IllegalArgumentException e) { check(true, "bad preset throws"); }
        try { NativeOps.v2Encrypt(Base64.getEncoder().encodeToString(new byte[16]), "2", ch, me, pb); check(false, "short key throws"); }
        catch (IllegalArgumentException e) { check(true, "short key throws"); }

        // Our Base64 shim must agree with java.util.Base64 (unpadded, as the wire format uses).
        String w = Crypto.encrypt(text, master, Crypto.Preset.AES128, ch, me).substring(1);
        check(!w.contains("=") && Base64.getDecoder().decode(w).length > 17, "wire is unpadded base64");

        System.out.println(failed == 0 ? "\nALL PASSED" : "\n" + failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
