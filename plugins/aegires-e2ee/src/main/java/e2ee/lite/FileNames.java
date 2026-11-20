package e2ee.lite;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Random upload names, so the original file name never reaches Discord's servers. Adapted from
 * Aegires' FileNames (LGPL-3.0); the MIME lookup is a small built-in table instead of Android's
 * MimeTypeMap, so it runs on a JVM.
 *
 * The ".enc" marker is kept: Aegires and the Kettu plugin recognise encrypted attachments by
 * "name.enc.ext", so "3fa9c2d41b7e08a65c1d.enc.png" is read by all of them.
 */
public final class FileNames {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FileNames() {}

    private static String extFromMime(String mime) {
        if (mime == null) return null;
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "image/heic": return "heic";
            case "image/bmp": return "bmp";
            case "video/mp4": return "mp4";
            case "video/webm": return "webm";
            case "video/quicktime": return "mov";
            case "video/x-matroska": return "mkv";
            case "audio/mpeg": return "mp3";
            case "audio/ogg": return "ogg";
            case "audio/mp4": return "m4a";
            case "audio/wav": return "wav";
            case "audio/aac": return "aac";
            case "audio/flac": return "flac";
            case "audio/opus": return "opus";
            case "application/pdf": return "pdf";
            case "text/plain": return "txt";
            case "application/zip": return "zip";
            default: return null;
        }
    }

    private static String cleanExt(String ext) {
        if (ext == null) return null;
        ext = ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return ext.isEmpty() || ext.length() > 5 ? null : ext;
    }

    /** 20 random hex characters, then the extension (from the MIME type, else the original name). */
    public static String randomFor(String originalName, String mime) {
        byte[] b = new byte[10];
        RANDOM.nextBytes(b);
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 0xF];
            c[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        String ext = cleanExt(extFromMime(mime));
        if (ext == null && originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) ext = cleanExt(originalName.substring(dot + 1));
        }
        return new String(c) + (ext != null ? "." + ext : "");
    }

    /** Upload name for an encrypted file: "<20 hex>.enc.<ext>" (or "<20 hex>.enc" without one). */
    public static String encryptedName(String originalName, String mime) {
        String n = randomFor(originalName, mime);
        int dot = n.indexOf('.');
        return dot < 0 ? n + ".enc" : n.substring(0, dot) + ".enc" + n.substring(dot);
    }
}
