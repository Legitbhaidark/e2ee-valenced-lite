import aegires.e2ee.NativeArgon2;
import aegires.e2ee.NativeOps;
import java.io.File;
import java.util.Base64;

/**
 * Checks the C Argon2id (through NativeArgon2 -> NativeOps) against the same independent reference
 * values as NativeOpsTest, and against the Java code. Needs a C library built for this machine:
 *
 *   JH=$(dirname $(dirname $(readlink -f $(which javac))))
 *   mkdir -p /tmp/an/res/lib/host
 *   gcc -O3 -shared -fPIC -I$JH/include -I$JH/include/linux src/main/c/argon2id_jni.c -o /tmp/an/res/lib/host/libaegirescrypto.so
 *   javac -d /tmp/an src/main/java/aegires/e2ee/*.java test/native/NativeOpsTest.java test/native/NativeArgon2Test.java
 *   java -cp /tmp/an:/tmp/an/res NativeArgon2Test
 */
public class NativeArgon2Test {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        System.out.println("  ok  " + msg);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "aegires-native-test-" + System.nanoTime());
        ClassLoader cl = NativeArgon2Test.class.getClassLoader();

        // 1. no library for this CPU: not ready, Java answers, nothing breaks
        check(!NativeArgon2.load(cl, dir, new String[] {"no-such-abi"}), "no library for the CPU: load() is false");
        check(NativeArgon2.status().startsWith("java:no C library"), "status says why: " + NativeArgon2.status());
        check(NativeArgon2.hash(new byte[1], new byte[8], 1, 8, 32) == null, "hash() is null when the library is not loaded");

        String pwB = Base64.getEncoder().encodeToString("pw".getBytes()), saltB = Base64.getEncoder().encodeToString("saltsalt".getBytes());
        boolean threw = false;
        try { NativeOps.argon2idWith("c", pwB, saltB, "1", "64", "32"); } catch (IllegalStateException e) { threw = true; }
        check(threw, "engine \"c\" refuses when the library is not loaded");
        check(NativeOps.argon2idWith("auto", pwB, saltB, "1", "64", "32").equals(NativeOps.argon2idWith("java", pwB, saltB, "1", "64", "32")), "auto falls back to Java, same bytes");

        // 2. the real library
        check(NativeArgon2.load(cl, dir, new String[] {"nope", "host"}), "library extracted, loaded and passed the Java cross-check");
        check(NativeArgon2.status().equals("c:host"), "status: " + NativeArgon2.status());
        File[] files = dir.listFiles();
        check(files != null && files.length == 1 && !java.nio.file.Files.getPosixFilePermissions(files[0].toPath()).contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), "one library file in the folder, owner-write bit off");

        // 3. independent reference values, now computed by C
        for (NativeOpsTest.Case c : NativeOpsTest.CASES) {
            long t0 = System.nanoTime();
            String got = NativeOpsTest.hex(Base64.getDecoder().decode(NativeOps.argon2id(c.pwB64(), c.saltB64(), "" + c.t(), "" + c.mKiB(), "" + c.len())));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(got.equals(c.expectedHex()), "C == independent reference: " + c.name() + " (" + ms + " ms)");
        }

        String viaC = NativeOps.argon2idWith("c", pwB, saltB, "2", "256", "32");
        check(viaC.equals(NativeOps.argon2idWith("java", pwB, saltB, "2", "256", "32")) && viaC.equals(NativeOps.argon2idWith("auto", pwB, saltB, "2", "256", "32")), "engines c / java / auto agree");
        String bc = NativeOps.argon2Bench("c", "2", "256", "3"), bj = NativeOps.argon2Bench("java", "2", "256", "3");
        check(bc.split(";")[0].equals(bj.split(";")[0]) && bc.split(";")[0].length() == 16, "bench: same hash head from c and java: " + bc.split(";")[0]);
        check(bc.split(";")[1].split(",").length == 3 && Long.parseLong(bc.split(";")[1].split(",")[0]) > 0, "bench: 3 timings in ns: " + bc.split(";")[1]);

        check(NativeArgon2.isHkdfReady(), "C HKDF loaded and matched Java HKDF");
        byte[] hs = new byte[16], hi = new byte[32], hn = "ctx".getBytes();
        for (int i = 0; i < 32; i++) { hi[i] = (byte) i; if (i < 16) hs[i] = (byte) (i * 9); }
        check(java.util.Arrays.equals(NativeOps.hkdfJava(hs, hi, hn, 76, 1), NativeArgon2.hkdf(hs, hi, hn, 76, 1)), "C HKDF == Java HKDF (76 bytes)");
        // RFC 5869 test case 1 (independent known answer)
        byte[] rIkm = new byte[22]; java.util.Arrays.fill(rIkm, (byte) 0x0b);
        byte[] rSalt = new byte[13]; for (int i = 0; i < 13; i++) rSalt[i] = (byte) i;
        byte[] rInfo = new byte[10]; for (int i = 0; i < 10; i++) rInfo[i] = (byte) (0xf0 + i);
        check(NativeOpsTest.hex(NativeArgon2.hkdf(rSalt, rIkm, rInfo, 42, 1)).equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), "C HKDF == RFC 5869 test case 1");
        String hc = NativeOps.hkdfBench("c", "200", "3"), hj = NativeOps.hkdfBench("java", "200", "3");
        check(hc.split(";")[0].equals(hj.split(";")[0]), "hkdf bench: same output from c and java: " + hc.split(";")[0]);
        String ab = NativeOps.aesBench("5", "2");
        check(ab.split(";")[0].length() == 16 && ab.split(";")[1].split(",").length == 2, "aes bench runs: " + ab);

        // 4. refused parameters give null (not a crash), and the C output is the exact length asked for
        check(NativeArgon2.hash(new byte[1], new byte[4], 1, 64, 32) == null, "short salt: null, no crash");
        check(NativeArgon2.hash(new byte[0], new byte[8], 1, 4, 32) == null, "memory below 8 KiB: null, no crash");
        check(NativeArgon2.isReady(), "refused parameters do not switch the library off");

        // 5. reload (plugin update): a second load in the same JVM works and replaces the old file
        check(NativeArgon2.load(cl, dir, new String[] {"host"}), "second load() works");
        files = dir.listFiles();
        check(files != null && files.length == 1, "old library file was removed, one file left");

        // 6. speed at the real size: C vs Java
        byte[] pw = "my real passphrase".getBytes(), salt = "dcenc2|user-key|v1".getBytes();
        NativeArgon2.hash(pw, salt, 3, 65536, 32); // warm-up
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            NativeArgon2.hash(pw, salt, 3, 65536, 32);
            best = Math.min(best, System.nanoTime() - t0);
        }
        long bestJava = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            aegires.e2ee.Argon2id.hash(pw, salt, 3, 65536, 32);
            bestJava = Math.min(bestJava, System.nanoTime() - t0);
        }
        System.out.printf("  --  64 MiB, t=3, best of 3: C %d ms, Java %d ms (this JVM, not the phone)%n", best / 1_000_000, bestJava / 1_000_000);

        for (File f : dir.listFiles()) f.delete();
        dir.delete();
        System.out.println("\nnative argon2 tests passed.");
    }
}
