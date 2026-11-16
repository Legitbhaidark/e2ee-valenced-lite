package aegires.e2ee;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link preview data (title, description, picture) for ONE https page, read from its Open Graph /
 * Twitter-card tags. Plain Java with no Android imports, so the parsing is unit-tested on a JVM.
 *
 * Why this exists: an encrypted message is "§..." to Discord's servers, so Discord never builds an
 * embed for the link inside it. The plugin builds one on this device instead. That means THIS
 * PHONE contacts the linked site (which then sees its IP address), so the JS side only calls this
 * when the user switched link previews on.
 *
 * Limits: https only, port 443, no user info, public addresses only (loopback / private / link-local
 * addresses are refused, also after every redirect), at most 3 redirects, 8 s timeouts, 256 KiB of
 * HTML read, no cookies. Known gap: the address is checked right before connecting, so a DNS
 * answer that changes in between is not caught.
 */
public final class LinkPreview {
    private LinkPreview() {}

    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_REDIRECTS = 3;
    static final int TIMEOUT_MS = 8000;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AegiresLinkPreview/1";

    /** JSON {"title","description","image","w","h","site"} or "" when the page has nothing to show. */
    public static String fetch(String url) throws IOException {
        URL u = checkedUrl(url);
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");
                c.setRequestProperty("Accept-Language", "en");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || hop >= MAX_REDIRECTS) return "";
                    u = checkedUrl(new URL(u, loc).toString());
                    continue;
                }
                if (code != 200) return "";
                String type = c.getContentType();
                if (type == null || !type.toLowerCase(Locale.ROOT).contains("html")) return "";
                byte[] body = readLimited(c.getInputStream(), MAX_BYTES);
                return toJson(parse(decode(body, type), u.toString()));
            } finally {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ address rules

    /** Throws unless the url is a plain https url to a public address. */
    static URL checkedUrl(String url) throws IOException {
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) throw new IOException("https only");
        if (u.getUserInfo() != null) throw new IOException("user info not allowed");
        if (u.getPort() != -1 && u.getPort() != 443) throw new IOException("port not allowed");
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new IOException("no host");
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (!isPublic(a)) throw new IOException("address not public");
        }
        return u;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 0) return false; // "this network"
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false; // carrier-grade NAT
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (b0 >= 240) return false; // reserved / broadcast
        } else if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return false; // unique local fc00::/7
        }
        return true;
    }

    // ------------------------------------------------------------------ reading

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (out.size() < max && (n = s.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    static String decode(byte[] body, String contentType) {
        Charset cs = StandardCharsets.UTF_8;
        String name = null;
        Matcher m = contentType == null ? null : CHARSET.matcher(contentType);
        if (m != null && m.find()) name = m.group(1);
        if (name == null) {
            // <meta charset=...> is in the first bytes; read them as Latin-1 to find it.
            Matcher mm = CHARSET.matcher(new String(body, 0, Math.min(body.length, 2048), StandardCharsets.ISO_8859_1));
            if (mm.find()) name = mm.group(1);
        }
        if (name != null) {
            try {
                cs = Charset.forName(name);
            } catch (Exception ignored) {
                // unknown charset: keep UTF-8
            }
        }
        return new String(body, cs);
    }

    // ------------------------------------------------------------------ parsing

    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);");

    /** Preview fields from HTML. Keys: title, description, image, w, h, site (missing ones are absent). */
    static Map<String, String> parse(String html, String pageUrl) {
        int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        String scan = head > 0 ? html.substring(0, head) : html;
        Map<String, String> meta = new HashMap<>();
        Matcher m = META.matcher(scan);
        while (m.find()) {
            Map<String, String> attrs = new HashMap<>();
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                String v = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
                attrs.put(a.group(1).toLowerCase(Locale.ROOT), v);
            }
            String key = attrs.containsKey("property") ? attrs.get("property") : attrs.get("name");
            String val = attrs.get("content");
            if (key == null || val == null) continue;
            key = key.toLowerCase(Locale.ROOT);
            if (!meta.containsKey(key)) meta.put(key, val); // first one wins
        }
        Map<String, String> out = new HashMap<>();
        String title = first(meta, "og:title", "twitter:title");
        if (title == null) {
            Matcher t = TITLE.matcher(scan);
            if (t.find()) title = t.group(1);
        }
        title = clean(title, 256);
        String desc = clean(first(meta, "og:description", "twitter:description", "description"), 300);
        String site = clean(first(meta, "og:site_name"), 100);
        if (site.isEmpty()) {
            try {
                site = new URI(pageUrl).getHost();
            } catch (Exception ignored) {
                site = "";
            }
            if (site == null) site = "";
        }
        String image = absoluteHttps(pageUrl, first(meta, "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src"));
        if (!title.isEmpty()) out.put("title", title);
        if (!desc.isEmpty()) out.put("description", desc);
        if (!site.isEmpty()) out.put("site", site);
        if (image != null) {
            out.put("image", image);
            int w = toInt(first(meta, "og:image:width"));
            int h = toInt(first(meta, "og:image:height"));
            if (w > 0 && h > 0) {
                out.put("w", String.valueOf(w));
                out.put("h", String.valueOf(h));
            }
        }
        return out;
    }

    private static String first(Map<String, String> meta, String... keys) {
        for (String k : keys) {
            String v = meta.get(k);
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private static int toInt(String s) {
        try {
            int n = Integer.parseInt(s == null ? "" : s.trim());
            return n > 0 && n <= 20000 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String absoluteHttps(String base, String ref) {
        if (ref == null || ref.trim().isEmpty()) return null;
        try {
            String abs = new URL(new URL(base), unescape(ref.trim())).toString();
            return abs.startsWith("https://") && abs.length() <= 2000 ? abs : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes entities, collapses whitespace, drops control characters and cuts to [max] chars. */
    static String clean(String s, int max) {
        if (s == null) return "";
        s = unescape(s).replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return s.length() > max ? s.substring(0, max - 1).trim() + "\u2026" : s;
    }

    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r;
            switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                case "nbsp": r = " "; break;
                default:
                    try {
                        int cp = e.charAt(1) == 'x' ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = Character.isValidCodePoint(cp) && cp > 0 ? new String(Character.toChars(cp)) : "";
                    } catch (NumberFormatException ex) {
                        r = "";
                    }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ output

    /** Empty string when there is nothing worth an embed (no title and no description). */
    static String toJson(Map<String, String> f) {
        if (!f.containsKey("title") && !f.containsKey("description")) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean firstField = true;
        for (String k : new String[] {"title", "description", "image", "site"}) {
            String v = f.get(k);
            if (v == null) continue;
            if (!firstField) sb.append(',');
            firstField = false;
            sb.append('"').append(k).append("\":\"").append(escape(v)).append('"');
        }
        for (String k : new String[] {"w", "h"}) {
            String v = f.get(k);
            if (v == null) continue;
            sb.append(",\"").append(k).append("\":").append(v);
        }
        return sb.append('}').toString();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
