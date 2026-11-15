package aegires.e2ee;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * File encryption/decryption for attachments, streamed through Crypto.encryptStream/decryptStream
 * (the Aegires v3 file format). Works on file PATHS, so file contents never cross the JS bridge
 * as base64. Plain Java (no Android imports) so it is unit-tested on a normal JVM.
 *
 * Output is written to "<out>.part" and renamed only when everything authenticated, so a failed
 * or tampered download never leaves a half-written file under the final name.
 */
public final class FileOps {
    private FileOps() {}

    /** Hard ceiling regardless of what the caller asks for. */
    static final long MAX_BYTES = 64L << 20;

    /** Only Discord's CDN hosts, over https. */
    public static boolean isAllowedUrl(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null || !"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("cdn.discordapp.com")
                    || host.equals("media.discordapp.net")
                    || host.endsWith(".discordapp.com")
                    || host.endsWith(".discordapp.net");
        } catch (Exception e) {
            return false;
        }
    }

    private static SecretKey masterKey(String masterB64) {
        byte[] master = NativeOps.decode(masterB64);
        if (master.length != 32) throw new IllegalArgumentException("master key length");
        SecretKey key = new SecretKeySpec(master, "HKDF");
        java.util.Arrays.fill(master, (byte) 0);
        return key;
    }

    /** Fails with "file too large" as soon as more than max bytes have been read. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long max) {
            super(in);
            left = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw new IOException("file too large");
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) throw new IOException("file too large");
            }
            return n;
        }
    }

    private static String localPath(String s) {
        if (!s.startsWith("file://")) return s;
        try {
            return new URI(s).getPath(); // percent-decoded; '+' stays '+'
        } catch (Exception e) {
            return s.substring(7);
        }
    }

    private static InputStream openSource(String src, long max, HttpURLConnection[] conn) throws IOException {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            if (!isAllowedUrl(src)) throw new IOException("url not allowed");
            HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false); // a redirect could leave the allowed hosts
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                throw new IOException("http " + code);
            }
            long len = c.getContentLengthLong();
            if (len > max) {
                c.disconnect();
                throw new IOException("file too large");
            }
            conn[0] = c;
            return new Limited(new BufferedInputStream(c.getInputStream()), max);
        }
        File f = new File(localPath(src));
        if (f.length() > max) throw new IOException("file too large");
        return new Limited(new BufferedInputStream(new FileInputStream(f)), max);
    }

    private static long run(boolean seal, SecretKey key, int preset, String ch, String sender, String src, InputStream given, String outPath, long max)
            throws IOException {
        if (!outPath.startsWith("/")) throw new IllegalArgumentException("outPath");
        if (max < 1 || max > MAX_BYTES) max = MAX_BYTES;
        File out = new File(outPath);
        File part = new File(outPath + ".part");
        File dir = out.getParentFile();
        if (dir != null) dir.mkdirs();
        HttpURLConnection[] conn = new HttpURLConnection[1];
        boolean ok = false;
        try (InputStream in = given != null ? given : openSource(src, seal ? max : max + (max >> 4) + 1024, conn);
                OutputStream os = new BufferedOutputStream(new FileOutputStream(part))) {
            boolean res;
            if (seal) {
                Crypto.Preset p = Crypto.Preset.fromIndex(preset);
                if (p == null) throw new IllegalArgumentException("preset");
                res = Crypto.encryptStream(in, os, key, p, ch, sender);
            } else {
                res = Crypto.decryptStream(in, os, key, ch, sender);
            }
            if (!res) throw new IOException(seal ? "encrypt failed" : "decrypt failed");
            ok = true;
        } finally {
            if (conn[0] != null) conn[0].disconnect();
            if (!ok) part.delete();
        }
        if (!part.renameTo(out)) {
            out.delete();
            if (!part.renameTo(out)) {
                part.delete();
                throw new IOException("could not write " + outPath);
            }
        }
        return out.length();
    }

    /**
     * Decrypts src (a Discord CDN https URL, or a local path / file:// uri) into outPath.
     * Returns the plaintext size in bytes as a decimal string. Throws on any failure.
     */
    public static String open(String masterB64, String channelId, String senderId, String src, String outPath, String maxBytes) {
        try {
            return Long.toString(run(false, masterKey(masterB64), 0, channelId, senderId, src, null, outPath, Long.parseLong(maxBytes)));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage() == null ? "io error" : e.getMessage());
        }
    }

    /** Encrypts the local file src into outPath. Returns the encrypted size as a decimal string. */
    public static String seal(String masterB64, String presetIndex, String channelId, String senderId, String src, String outPath, String maxBytes) {
        try {
            return Long.toString(run(true, masterKey(masterB64), Integer.parseInt(presetIndex), channelId, senderId, src, null, outPath, Long.parseLong(maxBytes)));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage() == null ? "io error" : e.getMessage());
        }
    }

    /**
     * Like seal, but the plaintext arrives as base64 (for sources Java cannot open itself, such as
     * content:// uris that JS read). Returns the encrypted size as a decimal string.
     */
    public static String sealBytes(String masterB64, String presetIndex, String channelId, String senderId, String plainB64, String outPath, String maxBytes) {
        try {
            byte[] plain = NativeOps.decode(plainB64);
            long max = Long.parseLong(maxBytes);
            if (plain.length > (max < 1 || max > MAX_BYTES ? MAX_BYTES : max)) throw new IOException("file too large");
            return Long.toString(run(true, masterKey(masterB64), Integer.parseInt(presetIndex), channelId, senderId, "", new java.io.ByteArrayInputStream(plain), outPath, max));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage() == null ? "io error" : e.getMessage());
        }
    }
}
