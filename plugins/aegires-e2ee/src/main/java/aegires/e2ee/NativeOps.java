package aegires.e2ee;

/**
 * The operations the JS side calls through Valenced's native bridge. Plain Java with no Android or
 * Valenced imports, so it is unit-tested on a normal JVM (test/native). Everything crosses the
 * bridge as strings: binary data is base64 (own codec below, because java.util.Base64 needs API 26).
 */
public final class NativeOps {
    private NativeOps() {}

    /** Argon2id memory above this is refused rather than risking an OutOfMemoryError in Discord. */
    static final int MAX_MEMORY_KIB = 256 * 1024;

    /** Argon2id v1.3, one lane. Arguments are strings because the bridge carries strings safely. */
    public static String argon2id(String passwordB64, String saltB64, String passes, String memoryKiB, String outLen) {
        byte[] password = decode(passwordB64);
        byte[] salt = decode(saltB64);
        int t = Integer.parseInt(passes);
        int m = Integer.parseInt(memoryKiB);
        int len = Integer.parseInt(outLen);
        if (t < 1 || t > 16) throw new IllegalArgumentException("passes");
        if (m < 8 || m > MAX_MEMORY_KIB) throw new IllegalArgumentException("memory");
        if (len < 4 || len > 1024) throw new IllegalArgumentException("outLen");
        // C library when it is loaded and passed its check, otherwise the Java code (same bytes).
        byte[] out = NativeArgon2.hash(password, salt, t, m, len);
        if (out == null) out = Argon2id.hash(password, salt, t, m, len);
        java.util.Arrays.fill(password, (byte) 0);
        return encode(out);
    }

    /** "c:<abi>" when Argon2id runs in the C library, otherwise "java:<why not>". */
    public static String argon2Engine() {
        return NativeArgon2.status();
    }

    // ---- Aegires v2 messages (Crypto.java, copied from Aegires) ----------------------------------
    // The master key is the 32-byte Argon2id output (base64). Plaintext crosses the bridge as base64
    // of its UTF-8 bytes so no character can be mangled. Failure throws, so JS can fall back.

    private static javax.crypto.SecretKey masterKey(String masterB64) {
        byte[] master = decode(masterB64);
        if (master.length != 32) throw new IllegalArgumentException("master key length");
        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(master, "HKDF");
        java.util.Arrays.fill(master, (byte) 0);
        return key;
    }

    /** Encrypts one message; returns the wire text ("§" + base64). */
    public static String v2Encrypt(String masterB64, String presetIndex, String channelId, String senderId, String plainB64) {
        Crypto.Preset preset = Crypto.Preset.fromIndex(Integer.parseInt(presetIndex));
        if (preset == null) throw new IllegalArgumentException("preset");
        String plain = new String(decode(plainB64), java.nio.charset.StandardCharsets.UTF_8);
        String wire = Crypto.encrypt(plain, masterKey(masterB64), preset, channelId, senderId);
        if (wire == null) throw new IllegalArgumentException("encrypt failed");
        return wire;
    }

    /** Decrypts one message; returns the plaintext as base64 of its UTF-8 bytes. */
    public static String v2Decrypt(String masterB64, String channelId, String senderId, String wire) {
        String plain = Crypto.decrypt(wire, masterKey(masterB64), channelId, senderId);
        if (plain == null) throw new IllegalArgumentException("decrypt failed");
        return encode(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(ALPHABET[b0 >> 2]);
            sb.append(ALPHABET[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < in.length ? ALPHABET[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < in.length ? ALPHABET[b2 & 63] : '=');
        }
        return sb.toString();
    }

    static byte[] decode(String s) {
        int n = s.length();
        if (n % 4 != 0) throw new IllegalArgumentException("base64 length");
        int pad = n > 0 && s.charAt(n - 1) == '=' ? (s.charAt(n - 2) == '=' ? 2 : 1) : 0;
        byte[] out = new byte[n / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < n; i += 4) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                char c = s.charAt(i + j);
                int d;
                if (c >= 'A' && c <= 'Z') d = c - 'A';
                else if (c >= 'a' && c <= 'z') d = c - 'a' + 26;
                else if (c >= '0' && c <= '9') d = c - '0' + 52;
                else if (c == '+') d = 62;
                else if (c == '/') d = 63;
                else if (c == '=' && i + j >= n - pad) d = 0;
                else throw new IllegalArgumentException("base64 char");
                v = (v << 6) | d;
            }
            if (o < out.length) out[o++] = (byte) (v >> 16);
            if (o < out.length) out[o++] = (byte) (v >> 8);
            if (o < out.length) out[o++] = (byte) v;
        }
        return out;
    }
}
