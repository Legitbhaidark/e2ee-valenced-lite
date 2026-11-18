package aegires.e2ee;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Argon2id in C (src/main/c/argon2id_jni.c). The compiled library is packed inside plugin.jar as
 * lib/<abi>/libaegirescrypto.so; load() copies it to a private folder and loads it.
 *
 * It is optional and fails soft: if the library is missing, cannot be loaded, or disagrees with the
 * Java Argon2id in the start-up check, hash() returns null and the caller uses the Java code.
 * Plain Java with no Android imports (the caller passes the folder and the ABI list), so it is
 * unit-tested on a normal JVM (test/native/NativeArgon2Test.java).
 */
public final class NativeArgon2 {
    private NativeArgon2() {}

    static final String RESOURCE_NAME = "libaegirescrypto.so";
    private static final String FILE_PREFIX = "libaegirescrypto-";

    private static volatile boolean ready;
    private static volatile String abiUsed = "";
    private static volatile String reason = "C library not loaded yet";

    /** Returns null when the parameters are refused or memory cannot be allocated. */
    private static native byte[] hashNative(byte[] password, byte[] salt, int passes, int memoryKiB, int outLen);

    /** HKDF-SHA256 extract+expand, repeated `iterations` times on the same input; returns the last output. */
    private static native byte[] hkdfNative(byte[] salt, byte[] ikm, byte[] info, int len, int iterations);

    /** True when the C library runs SHA-256 / HMAC / HKDF on the ARMv8 SHA-2 instructions in this process. */
    private static native boolean sha256HwNative();

    public static boolean isReady() {
        return ready;
    }

    /**
     * "hw" when the C HKDF uses the ARMv8 SHA-2 instructions (CPU has them and they matched the portable
     * code at first use), "sw" for the portable C code, "java" when the C library is not in use. Never
     * throws: an older library without the probe just reports "sw".
     */
    public static String sha256Engine() {
        if (!ready) return "java";
        try {
            return sha256HwNative() ? "hw" : "sw";
        } catch (Throwable t) {
            return "sw";
        }
    }

    /** "c:arm64-v8a" when the C library is in use, otherwise "java:<why not>". Shown in Diagnostics. */
    public static String status() {
        return ready ? "c:" + abiUsed : "java:" + reason;
    }

    /**
     * Extracts lib/<abi>/libaegirescrypto.so (first ABI in the list that has one) from the class
     * loader's resources into {@code dir}, loads it and checks it against the Java implementation.
     * Never throws. A fresh file name is used every time: a library path can be loaded by only one
     * class loader, and a plugin reload creates a new one.
     */
    public static synchronized boolean load(ClassLoader loader, File dir, String[] abis) {
        ready = false;
        abiUsed = "";
        try {
            if (loader == null) loader = NativeArgon2.class.getClassLoader();
            byte[] so = null;
            String abi = null;
            for (String a : abis) {
                so = readResource(loader, "lib/" + a + "/" + RESOURCE_NAME);
                if (so != null) {
                    abi = a;
                    break;
                }
            }
            if (so == null) {
                StringBuilder tried = new StringBuilder();
                for (String a : abis) tried.append(tried.length() == 0 ? "" : ",").append(a);
                reason = "no C library for this CPU (" + tried + ")";
                return false;
            }
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) if (f.getName().startsWith(FILE_PREFIX)) f.delete();
            }
            File lib = new File(dir, FILE_PREFIX + Long.toString(System.nanoTime(), 36) + ".so");
            FileOutputStream out = new FileOutputStream(lib);
            try {
                out.write(so);
            } finally {
                out.close();
            }
            lib.setReadOnly();
            System.load(lib.getAbsolutePath());

            String bad = selfCheck();
            if (bad != null) {
                reason = "C library disagrees with Java (" + bad + ")";
                return false;
            }
            abiUsed = abi;
            hkdfReady = hkdfCheck(); // an old or wrong HKDF only switches HKDF off, Argon2id keeps using C
            ready = true;
            return true;
        } catch (Throwable t) {
            ready = false;
            hkdfReady = false;
            reason = "C library not used (" + oneLine(t) + ")";
            return false;
        }
    }

    /**
     * Argon2id through the C library, or null when it is not usable (then the caller uses the
     * Java code). An error inside the library switches it off for the rest of the session.
     */
    public static byte[] hash(byte[] password, byte[] salt, int passes, int memoryKiB, int outLen) {
        if (!ready) return null;
        try {
            byte[] out = hashNative(password, salt, passes, memoryKiB, outLen);
            return out != null && out.length == outLen ? out : null;
        } catch (Throwable t) {
            ready = false;
            reason = "C library switched off after an error (" + oneLine(t) + ")";
            return null;
        }
    }

    private static volatile boolean hkdfReady;

    /** True when the C HKDF is loaded and matched the Java HKDF (checked at load). */
    public static boolean isHkdfReady() {
        return ready && hkdfReady;
    }

    /** HKDF-SHA256 through the C library, or null when it is not usable (an error switches only HKDF off). */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len, int iterations) {
        if (!ready || !hkdfReady) return null;
        try {
            byte[] out = hkdfNative(salt, ikm, info, len, iterations);
            return out != null && out.length == len ? out : null;
        } catch (Throwable t) {
            hkdfReady = false;
            return null;
        }
    }

    /** HKDF known-answer style check against the Java (javax.crypto) HKDF; false only switches C HKDF off. */
    private static boolean hkdfCheck() {
        try {
            byte[] salt = new byte[16], ikm = new byte[32], info = "aegires|check".getBytes();
            for (int i = 0; i < 32; i++) { ikm[i] = (byte) (i * 5 + 1); if (i < 16) salt[i] = (byte) (255 - i); }
            int[] lens = {44, 32, 7, 100};
            for (int len : lens) {
                if (!Arrays.equals(NativeOps.hkdfJava(salt, ikm, info, len, 1), hkdfNative(salt, ikm, info, len, 1))) return false;
            }
            return Arrays.equals(NativeOps.hkdfJava(new byte[0], ikm, new byte[0], 44, 1), hkdfNative(new byte[0], ikm, new byte[0], 44, 1));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Small cases (a few ms in Java): long password, empty password, output over 64 bytes. */
    private static String selfCheck() {
        byte[] longPw = new byte[200];
        for (int i = 0; i < longPw.length; i++) longPw[i] = (byte) (i * 7 + 3);
        byte[][] pw = {"correct horse".getBytes(), new byte[0], longPw};
        byte[][] salt = {"dcenc2|user-key|v1".getBytes(), "saltsaltsalt".getBytes(), "sixteen byte sal".getBytes()};
        int[][] p = {{3, 256, 32}, {1, 64, 100}, {2, 128, 32}}; // passes, KiB, output length
        for (int i = 0; i < pw.length; i++) {
            byte[] want = Argon2id.hash(pw[i], salt[i], p[i][0], p[i][1], p[i][2]);
            byte[] got = hashNative(pw[i].clone(), salt[i], p[i][0], p[i][1], p[i][2]);
            if (got == null || !Arrays.equals(want, got)) return "case " + (i + 1);
        }
        return null;
    }

    private static byte[] readResource(ClassLoader loader, String name) throws IOException {
        InputStream in = loader.getResourceAsStream(name);
        if (in == null) return null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.size() > 0 ? buf.toByteArray() : null;
        } finally {
            in.close();
        }
    }

    private static String oneLine(Throwable t) {
        String m = String.valueOf(t.getMessage());
        int nl = m.indexOf('\n');
        if (nl >= 0) m = m.substring(0, nl);
        if (m.length() > 120) m = m.substring(0, 120);
        return t.getClass().getSimpleName() + ": " + m;
    }
}
