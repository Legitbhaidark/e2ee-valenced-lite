package e2ee.lite;

/**
 * Minimal stand-in for android.util.Base64, so the Aegires Crypto.java (which uses it) compiles
 * unchanged on Android and on a plain JVM. Only the flags and the two methods Crypto.java uses.
 * Decoding accepts input with or without '=' padding, like Android's decoder.
 */
final class Base64 {
    private Base64() {}

    static final int NO_PADDING = 1;
    static final int NO_WRAP = 2;

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String encodeToString(byte[] in, int flags) {
        boolean pad = (flags & NO_PADDING) == 0;
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(ALPHABET[b0 >> 2]);
            sb.append(ALPHABET[((b0 & 3) << 4) | (b1 >> 4)]);
            if (i + 1 < in.length) sb.append(ALPHABET[((b1 & 15) << 2) | (b2 >> 6)]);
            else if (pad) sb.append('=');
            if (i + 2 < in.length) sb.append(ALPHABET[b2 & 63]);
            else if (pad) sb.append('=');
        }
        return sb.toString();
    }

    static byte[] decode(String s, int flags) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '=') n--;
        if (n % 4 == 1) throw new IllegalArgumentException("base64 length");
        byte[] out = new byte[n * 3 / 4];
        int o = 0, acc = 0, bits = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int d;
            if (c >= 'A' && c <= 'Z') d = c - 'A';
            else if (c >= 'a' && c <= 'z') d = c - 'a' + 26;
            else if (c >= '0' && c <= '9') d = c - '0' + 52;
            else if (c == '+') d = 62;
            else if (c == '/') d = 63;
            else throw new IllegalArgumentException("base64 char");
            acc = (acc << 6) | d;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (acc >> bits);
                acc &= (1 << bits) - 1;
            }
        }
        return out;
    }
}
