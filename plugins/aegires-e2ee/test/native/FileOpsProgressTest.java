package aegires.e2ee;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * FileOps.progress(): the percentage source for the "Decrypting..." placeholder. From the plugin folder:
 *   javac -d /tmp/fp src/main/java/aegires/e2ee/*.java test/native/FileOpsProgressTest.java && java -cp /tmp/fp aegires.e2ee.FileOpsProgressTest
 */
public class FileOpsProgressTest {
    static int fails = 0;

    static void check(boolean ok, String msg) {
        if (ok) System.out.println("  ok  " + msg);
        else {
            fails++;
            System.out.println("  FAIL " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("aegires-prog").toFile();
        File plain = new File(dir, "plain.bin");
        byte[] chunk = new byte[1 << 20];
        new Random(7).nextBytes(chunk);
        try (FileOutputStream f = new FileOutputStream(plain)) {
            for (int i = 0; i < 48; i++) f.write(chunk); // 48 MiB
        }
        String master = NativeOps.encode(new byte[32]);
        String enc = new File(dir, "enc.bin").getPath();
        String dec = new File(dir, "aegires/in/dec.bin").getPath();
        FileOps.seal(master, "0", "111", "222", plain.getPath(), enc, "0");

        check("".equals(FileOps.progress(dec)), "no progress entry before the decryption starts");

        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                FileOps.open(master, "111", "222", enc, dec, "0");
            } catch (Throwable e) {
                err[0] = e;
            }
        });
        t.start();
        long lastRead = -1;
        long total = -2;
        int samples = 0;
        boolean monotonic = true;
        while (t.isAlive()) {
            String p = FileOps.progress(dec);
            if (!p.isEmpty()) {
                String[] parts = p.split(",");
                long read = Long.parseLong(parts[0]);
                total = Long.parseLong(parts[1]);
                if (read < lastRead) monotonic = false;
                lastRead = read;
                samples++;
            }
            Thread.sleep(2);
        }
        t.join();
        check(err[0] == null, "decryption of a 48 MiB file works" + (err[0] == null ? "" : ": " + err[0]));
        check(samples > 2, "progress was readable while it ran (" + samples + " samples)");
        check(monotonic, "bytes read only ever go up");
        check(total == new File(enc).length(), "total is the encrypted file size");
        check(lastRead > 0 && lastRead <= total, "last sample is within the file");
        check("".equals(FileOps.progress(dec)), "entry is removed when it is done");
        check(new File(dec).length() == plain.length(), "output has the plaintext size");

        // a failed decryption also clears its entry
        File bad = new File(dir, "bad.bin");
        try (FileOutputStream f = new FileOutputStream(bad)) {
            f.write(chunk, 0, 5000);
        }
        String dec2 = new File(dir, "aegires/in/bad.out").getPath();
        try {
            FileOps.open(master, "111", "222", bad.getPath(), dec2, "0");
            check(false, "garbage should not decrypt");
        } catch (IllegalStateException e) {
            check(true, "garbage does not decrypt (" + e.getMessage() + ")");
        }
        check("".equals(FileOps.progress(dec2)), "entry is removed after a failure too");

        if (fails > 0) {
            System.out.println("\n" + fails + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nprogress tests passed.");
    }
}
