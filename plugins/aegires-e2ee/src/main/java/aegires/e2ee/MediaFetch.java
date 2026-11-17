package aegires.e2ee;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a GIF / picture / video from a link so the plugin can send it as an ENCRYPTED file
 * instead of as a plain link (which only gives the receiver a link and no preview). Plain Java with
 * no Android imports, so the parsing is unit-tested on a JVM.
 *
 * What it accepts:
 *  - a direct media link on a known host (Tenor, Giphy, Imgur, Discord's CDN),
 *  - a Giphy page link (the media address follows from the id in the link),
 *  - a Tenor page link: the page is read and the media address is taken from its tags
 *    (og:video, og:image, JSON-LD contentUrl) or from the media links right next to the GIF's own id.
 *    Media links elsewhere on the page (other GIFs in "related") are never used, so a wrong GIF
 *    cannot be picked by accident.
 *
 * Safety: https only, port 443, public addresses only (checked again after every redirect), at most
 * 3 redirects, only the hosts below, only image/* or video/* answers, a size limit, 15 s timeouts,
 * no cookies. The file is written next to its final name and renamed when complete.
 */
public final class MediaFetch {
    private MediaFetch() {}

    static final int MAX_REDIRECTS = 3;
    static final int TIMEOUT_MS = 15_000;
    static final int PAGE_MAX_BYTES = 1024 * 1024;
    static final int NEAR_BEFORE = 1500;
    static final int NEAR_AFTER = 4000;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AegiresMedia/1";

    // ------------------------------------------------------------------ host rules

    private static final Pattern TENOR_MEDIA = Pattern.compile("(?:media\\d*|c)\\.tenor\\.com");
    private static final Pattern GIPHY_MEDIA = Pattern.compile("media\\d*\\.giphy\\.com");

    /** Hosts the media bytes may come from. */
    static boolean isMediaHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return TENOR_MEDIA.matcher(h).matches()
                || GIPHY_MEDIA.matcher(h).matches()
                || h.equals("i.giphy.com")
                || h.equals("i.imgur.com")
                || h.equals("cdn.discordapp.com")
                || h.equals("media.discordapp.net");
    }

    /** Hosts whose pages may be read to find the media address. */
    static boolean isPageHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("tenor.com") || h.equals("www.tenor.com") || h.equals("giphy.com") || h.equals("www.giphy.com");
    }

    static URL checkedMedia(String url) throws IOException {
        URL u = LinkPreview.checkedUrl(url);
        if (!isMediaHost(u.getHost())) throw new IOException("media host not allowed");
        return u;
    }

    static URL checkedPage(String url) throws IOException {
        URL u = LinkPreview.checkedUrl(url);
        if (!isPageHost(u.getHost())) throw new IOException("page host not allowed");
        return u;
    }

    // ------------------------------------------------------------------ candidates

    /** One possible media address and where it was found. */
    static final class Cand {
        final String url;
        final String how; // "direct", "giphy:id", "tag:og:video", "tag:og:image", "ld:contentUrl", "near-id"

        Cand(String url, String how) {
            this.url = url;
            this.how = how;
        }

        String ext() {
            String p = url;
            int q = p.indexOf('?');
            if (q >= 0) p = p.substring(0, q);
            int slash = p.lastIndexOf('/');
            int dot = p.lastIndexOf('.');
            return dot > slash ? p.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }

        /** 0 = GIF, 1 = mp4, 2 = webm, 9 = not animated (or unknown). */
        int rank() {
            switch (ext()) {
                case "gif": return 0;
                case "mp4": return 1;
                case "webm": return 2;
                default: return 9;
            }
        }

        /** 0 = the page's own tags (most trusted), 1 = found next to the GIF's id. */
        int group() {
            return how.equals("near-id") ? 1 : 0;
        }

        @Override
        public String toString() {
            return ext() + "@" + how;
        }
    }

    private static final Pattern TENOR_ID = Pattern.compile("-(\\d{6,25})/?$");
    private static final Pattern GIPHY_PATH = Pattern.compile("^/(?:gifs|stickers|embed)/([^/?#]+)/?$");

    /** The numeric GIF id at the end of a Tenor page path, or null. */
    static String tenorId(String path) {
        if (path == null) return null;
        Matcher m = TENOR_ID.matcher(path);
        return m.find() ? m.group(1) : null;
    }

    /** The Giphy id of a page path like /gifs/some-words-3o7TKSjRrfIPjeiVyM, or null. */
    static String giphyId(String path) {
        if (path == null) return null;
        Matcher m = GIPHY_PATH.matcher(path);
        if (!m.find()) return null;
        String seg = m.group(1);
        int dash = seg.lastIndexOf('-');
        String id = dash >= 0 ? seg.substring(dash + 1) : seg;
        return id.matches("[A-Za-z0-9]{5,30}") ? id : null;
    }

    /** Media address candidates for a link, most trusted first. Direct media links resolve to themselves. */
    static List<Cand> resolve(String url) throws IOException {
        URL u = LinkPreview.checkedUrl(url);
        String host = u.getHost().toLowerCase(Locale.ROOT);
        List<Cand> out = new ArrayList<>();
        if (isMediaHost(host)) {
            out.add(new Cand(u.toString(), "direct"));
            return out;
        }
        if (!isPageHost(host)) throw new IOException("host not allowed");
        if (host.endsWith("giphy.com")) {
            String id = giphyId(u.getPath());
            if (id == null) throw new IOException("no giphy id in link");
            out.add(new Cand("https://media.giphy.com/media/" + id + "/giphy.gif", "giphy:id"));
            return out;
        }
        String html = fetchPage(u);
        return extract(html, tenorId(u.getPath()));
    }

    // ------------------------------------------------------------------ page reading

    static String fetchPage(URL start) throws IOException {
        URL u = start;
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
                    if (loc == null || hop >= MAX_REDIRECTS) throw new IOException("too many redirects");
                    u = checkedPage(new URL(u, loc).toString()); // a redirect may not leave the page hosts
                    continue;
                }
                if (code != 200) throw new IOException("page http " + code);
                String type = c.getContentType();
                if (type == null || !type.toLowerCase(Locale.ROOT).contains("html")) throw new IOException("page is not html");
                return LinkPreview.decode(readLimited(c.getInputStream(), PAGE_MAX_BYTES), type);
            } finally {
                c.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (out.size() < max && (n = s.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link\\s[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", Pattern.DOTALL);
    private static final Pattern CONTENT_URL = Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MEDIA_URL = Pattern.compile("https://(?:media\\d*|c|i)\\.(?:tenor|giphy)\\.com/[^\\s\"'<>\\\\)]+");
    private static final Set<String> TAG_KEYS = new HashSet<>(Arrays.asList(
            "og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream",
            "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src", "contenturl"));

    private static Map<String, String> attrs(String tag) {
        Map<String, String> a = new HashMap<>();
        Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            String v = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            a.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), v);
        }
        return a;
    }

    private static void add(Map<String, Cand> out, String raw, String how) {
        if (raw == null) return;
        String u = LinkPreview.unescape(raw.trim());
        if (u.startsWith("//")) u = "https:" + u;
        while (u.endsWith(",") || u.endsWith(";") || u.endsWith("\\")) u = u.substring(0, u.length() - 1);
        if (!u.startsWith("https://") || u.length() > 2000) return;
        try {
            if (!isMediaHost(new URL(u).getHost())) return;
        } catch (IOException e) {
            return;
        }
        out.putIfAbsent(u, new Cand(u, how));
    }

    /**
     * Media addresses on a Tenor page: first the page's own tags, then media links within a window
     * around the GIF's own id (pageId). Nothing else on the page is used.
     */
    static List<Cand> extract(String html, String pageId) {
        String n = html.replace("\\u002F", "/").replace("\\u002f", "/").replace("\\u0026", "&").replace("\\/", "/");
        Map<String, Cand> out = new LinkedHashMap<>();
        Matcher mm = META.matcher(n);
        while (mm.find()) {
            Map<String, String> a = attrs(mm.group());
            String key = a.get("property") != null ? a.get("property") : a.get("name") != null ? a.get("name") : a.get("itemprop");
            String val = a.get("content") != null ? a.get("content") : a.get("value");
            if (key == null || val == null) continue;
            key = key.toLowerCase(Locale.ROOT);
            if (TAG_KEYS.contains(key)) add(out, val, "tag:" + key);
        }
        Matcher lm = LINK.matcher(n);
        while (lm.find()) {
            Map<String, String> a = attrs(lm.group());
            if ("image_src".equalsIgnoreCase(a.get("rel"))) add(out, a.get("href"), "tag:image_src");
        }
        Matcher ld = CONTENT_URL.matcher(n);
        while (ld.find()) add(out, ld.group(1), "ld:contentUrl");
        if (pageId != null) {
            int from = 0;
            for (int seen = 0; seen < 20; seen++) {
                int i = n.indexOf(pageId, from);
                if (i < 0) break;
                String win = n.substring(Math.max(0, i - NEAR_BEFORE), Math.min(n.length(), i + NEAR_AFTER));
                Matcher um = MEDIA_URL.matcher(win);
                while (um.find()) add(out, um.group(), "near-id");
                from = i + pageId.length();
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The best animated candidate: the page's own tags win over links found next to the id, and
     * within a group GIF beats mp4 beats webm. A still picture is only returned when allowStill is
     * set (a direct link the user pasted), never for a page.
     */
    static Cand choose(List<Cand> list, boolean allowStill) {
        for (int group = 0; group <= 1; group++) {
            Cand best = null;
            for (Cand c : list) {
                if (c.group() != group || c.rank() >= 9) continue;
                if (best == null || c.rank() < best.rank()) best = c;
            }
            if (best != null) return best;
        }
        return allowStill && !list.isEmpty() ? list.get(0) : null;
    }

    static String summary(List<Cand> list) {
        if (list.isEmpty()) return "no media links found";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 8; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ download

    static final class Downloaded {
        final String path;
        final String mime;
        final long size;

        Downloaded(String path, String mime, long size) {
            this.path = path;
            this.mime = mime;
            this.size = size;
        }
    }

    /** File extension for a download: from its type, else from the link. */
    static String extFor(String mime, String url) {
        switch (mime) {
            case "image/gif": return "gif";
            case "video/mp4": return "mp4";
            case "video/webm": return "webm";
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            default:
                String e = new Cand(url, "").ext();
                return e.matches("gif|mp4|webm|png|jpg|jpeg|webp") ? e : "bin";
        }
    }

    /** Downloads one media link into outBase + "." + extension. Returns the file and its type. */
    static Downloaded download(String url, String outBase, long maxBytes) throws IOException {
        if (!outBase.startsWith("/")) throw new IllegalArgumentException("outBase");
        URL u = checkedMedia(url);
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", USER_AGENT);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || hop >= MAX_REDIRECTS) throw new IOException("too many redirects");
                    u = checkedMedia(new URL(u, loc).toString()); // a redirect may not leave the media hosts
                    continue;
                }
                if (code != 200) throw new IOException("http " + code);
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                int semi = type.indexOf(';');
                if (semi >= 0) type = type.substring(0, semi);
                type = type.trim();
                if (!type.startsWith("image/") && !type.startsWith("video/")) throw new IOException("not media: " + type);
                if (c.getContentLengthLong() > maxBytes) throw new IOException("file too large");
                File out = new File(outBase + "." + extFor(type, u.toString()));
                File part = new File(out.getPath() + ".part");
                File dir = out.getParentFile();
                if (dir != null) dir.mkdirs();
                long n = 0;
                boolean ok = false;
                try (InputStream in = c.getInputStream(); OutputStream os = new BufferedOutputStream(new FileOutputStream(part))) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        n += r;
                        if (n > maxBytes) throw new IOException("file too large");
                        os.write(buf, 0, r);
                    }
                    ok = true;
                } finally {
                    if (!ok) part.delete();
                }
                if (n == 0) {
                    part.delete();
                    throw new IOException("empty answer");
                }
                if (out.exists()) out.delete();
                if (!part.renameTo(out)) {
                    part.delete();
                    throw new IOException("cannot write file");
                }
                return new Downloaded(out.getPath(), type, n);
            } finally {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Resolves a link and downloads the best animated candidate. Returns JSON:
     * {"path","mime","size","url","how","found":"gif@tag:og:image, ...","ms"}.
     */
    public static String fetch(String url, String outBase, long maxBytes) throws IOException {
        long t0 = System.nanoTime();
        List<Cand> list = resolve(url);
        boolean direct = list.size() == 1 && list.get(0).how.equals("direct");
        Cand c = choose(list, direct);
        if (c == null) throw new IOException("no animated media found (" + summary(list) + ")");
        Downloaded d = download(c.url, outBase, maxBytes);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return "{\"path\":\"" + LinkPreview.escape(d.path) + "\",\"mime\":\"" + LinkPreview.escape(d.mime)
                + "\",\"size\":" + d.size + ",\"url\":\"" + LinkPreview.escape(c.url)
                + "\",\"how\":\"" + LinkPreview.escape(c.how) + "\",\"found\":\"" + LinkPreview.escape(summary(list))
                + "\",\"ms\":" + ms + "}";
    }
}
