package e2ee.lite;

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
        return argon2idWith("auto", passwordB64, saltB64, passes, memoryKiB, outLen);
    }

    /**
     * Same, but the caller picks the engine: "auto" (C library, else Java), "c" (C only, fails if it
     * is not loaded) or "java" (the Java code only). All engines give the same bytes.
     */
    public static String argon2idWith(String engine, String passwordB64, String saltB64, String passes, String memoryKiB, String outLen) {
        byte[] password = decode(passwordB64);
        byte[] salt = decode(saltB64);
        int t = Integer.parseInt(passes);
        int m = Integer.parseInt(memoryKiB);
        int len = Integer.parseInt(outLen);
        checkParams(t, m, len);
        byte[] out = runArgon2(engine, password, salt, t, m, len);
        java.util.Arrays.fill(password, (byte) 0);
        return encode(out);
    }

    /**
     * Benchmark: runs Argon2id `runs` times with fixed inputs on one engine and returns
     * "<first 8 output bytes in hex>;<ns run 1>,<ns run 2>,..." (timed here, so the bridge is not in the numbers).
     */
    public static String argon2Bench(String engine, String passes, String memoryKiB, String runsStr) {
        int t = Integer.parseInt(passes);
        int m = Integer.parseInt(memoryKiB);
        int runs = Math.max(1, Math.min(10, Integer.parseInt(runsStr)));
        checkParams(t, m, 32);
        byte[] password = "benchmark-password".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] salt = "dcenc2|bench|v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = null;
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            out = runArgon2(engine, password, salt, t, m, 32);
            long ns = System.nanoTime() - t0;
            if (i > 0) times.append(',');
            times.append(ns);
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8 && i < out.length; i++) {
            hex.append(Character.forDigit((out[i] >> 4) & 15, 16)).append(Character.forDigit(out[i] & 15, 16));
        }
        return hex + ";" + times;
    }

    // ---- benchmarks for HKDF and AES-GCM (timed here, so the bridge is not in the numbers) ----------

    /** HKDF-SHA256 with javax.crypto HMAC, extract+expand repeated `iterations` times; returns the last output. */
    public static byte[] hkdfJava(byte[] salt, byte[] ikm, byte[] info, int len, int iterations) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] okm = new byte[len];
            for (int k = 0; k < iterations; k++) {
                mac.init(new javax.crypto.spec.SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
                byte[] prk = mac.doFinal(ikm);
                mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
                byte[] t = new byte[0];
                int pos = 0;
                for (int ctr = 1; pos < len; ctr++) {
                    mac.update(t);
                    mac.update(info);
                    mac.update((byte) ctr);
                    t = mac.doFinal();
                    int n = Math.min(32, len - pos);
                    System.arraycopy(t, 0, okm, pos, n);
                    pos += n;
                }
            }
            return okm;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String headHex(byte[] b) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8 && i < b.length; i++) {
            hex.append(Character.forDigit((b[i] >> 4) & 15, 16)).append(Character.forDigit(b[i] & 15, 16));
        }
        return hex.toString();
    }

    /** "<first 8 output bytes hex>;<ns run 1>,<ns run 2>,..." for `iterations` x (extract+expand to 44 bytes). */
    public static String hkdfBench(String engine, String iterationsStr, String runsStr) {
        int iterations = Math.max(1, Math.min(100000, Integer.parseInt(iterationsStr)));
        int runs = Math.max(1, Math.min(10, Integer.parseInt(runsStr)));
        byte[] salt = new byte[16], ikm = new byte[32], info = "aegires|bench|hkdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 32; i++) { ikm[i] = (byte) (i * 3 + 7); if (i < 16) salt[i] = (byte) (i + 1); }
        boolean c = "c".equals(engine);
        if (c && !NativeArgon2.isHkdfReady()) throw new IllegalStateException("C HKDF not available: " + NativeArgon2.status());
        byte[] out = null;
        StringBuilder times = new StringBuilder();
        for (int r = 0; r < runs; r++) {
            long t0 = System.nanoTime();
            out = c ? NativeArgon2.hkdf(salt, ikm, info, 44, iterations) : hkdfJava(salt, ikm, info, 44, iterations);
            long ns = System.nanoTime() - t0;
            if (out == null) throw new IllegalStateException("C HKDF failed");
            if (r > 0) times.append(',');
            times.append(ns);
        }
        return headHex(out) + ";" + times;
    }

    /**
     * AES-256-GCM, `iterations` x (encrypt + decrypt 64 KiB) with javax.crypto (Conscrypt on Android).
     * There is no C AES, so this engine is Java only. Same return format as the other benchmarks;
     * the "hash" is the head of a ciphertext made with a fixed nonce, so JS can compare it.
     */
    public static String aesBench(String iterationsStr, String runsStr) {
        int iterations = Math.max(1, Math.min(10000, Integer.parseInt(iterationsStr)));
        int runs = Math.max(1, Math.min(10, Integer.parseInt(runsStr)));
        try {
            byte[] key = new byte[32], plain = new byte[64 * 1024];
            for (int i = 0; i < 32; i++) key[i] = (byte) (i * 11 + 5);
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i * 31 + (i >> 8));
            javax.crypto.SecretKey sk = new javax.crypto.spec.SecretKeySpec(key, "AES");
            javax.crypto.Cipher enc = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.Cipher dec = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            int counter = 0;
            StringBuilder times = new StringBuilder();
            for (int r = 0; r < runs; r++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < iterations; k++) {
                    byte[] iv = new byte[12];
                    iv[0] = (byte) k; iv[1] = (byte) (k >> 8); iv[2] = (byte) (counter++); // a fresh nonce per operation
                    enc.init(javax.crypto.Cipher.ENCRYPT_MODE, sk, new javax.crypto.spec.GCMParameterSpec(128, iv));
                    byte[] ct = enc.doFinal(plain);
                    dec.init(javax.crypto.Cipher.DECRYPT_MODE, sk, new javax.crypto.spec.GCMParameterSpec(128, iv));
                    byte[] back = dec.doFinal(ct);
                    if (back.length != plain.length || back[777] != plain[777]) throw new IllegalStateException("AES round trip failed");
                }
                long ns = System.nanoTime() - t0;
                if (r > 0) times.append(',');
                times.append(ns);
            }
            // Fixed nonce, outside the timing: the same input JS encrypts, so the two engines can be compared.
            enc.init(javax.crypto.Cipher.ENCRYPT_MODE, sk, new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
            return headHex(enc.doFinal(plain)) + ";" + times;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkParams(int t, int m, int len) {
        if (t < 1 || t > 16) throw new IllegalArgumentException("passes");
        if (m < 8 || m > MAX_MEMORY_KIB) throw new IllegalArgumentException("memory");
        if (len < 4 || len > 1024) throw new IllegalArgumentException("outLen");
    }

    private static byte[] runArgon2(String engine, byte[] password, byte[] salt, int t, int m, int len) {
        if ("java".equals(engine)) return Argon2id.hash(password, salt, t, m, len);
        // C library when it is loaded and passed its check, otherwise the Java code (same bytes).
        byte[] out = NativeArgon2.hash(password, salt, t, m, len);
        if (out != null) return out;
        if ("c".equals(engine)) throw new IllegalStateException("C library not available: " + NativeArgon2.status());
        return Argon2id.hash(password, salt, t, m, len);
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
