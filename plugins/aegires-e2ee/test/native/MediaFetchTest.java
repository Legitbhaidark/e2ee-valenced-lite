package aegires.e2ee;

import java.util.List;

/**
 * Media link rules and Tenor page reading (no network). From the plugin folder:
 *   javac -d /tmp/mf src/main/java/aegires/e2ee/LinkPreview.java src/main/java/aegires/e2ee/MediaFetch.java test/native/MediaFetchTest.java && java -cp /tmp/mf aegires.e2ee.MediaFetchTest
 */
public class MediaFetchTest {
    static int fails = 0;

    static void check(boolean ok, String msg) {
        if (ok) System.out.println("  ok  " + msg);
        else {
            fails++;
            System.out.println("  FAIL " + msg);
        }
    }

    static boolean refused(String url) {
        try {
            MediaFetch.resolve(url);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        // hosts
        for (String h : new String[] {"media.tenor.com", "media1.tenor.com", "c.tenor.com", "media.giphy.com", "media3.giphy.com", "i.giphy.com", "i.imgur.com", "cdn.discordapp.com", "media.discordapp.net"})
            check(MediaFetch.isMediaHost(h), "media host " + h);
        for (String h : new String[] {"tenor.com.evil.com", "media.tenor.com.evil.com", "mediax.tenor.com", "evil.com", "tenor.com", "discordapp.com", "xmedia.tenor.com", null})
            check(!MediaFetch.isMediaHost(h), "not a media host " + h);
        check(MediaFetch.isPageHost("tenor.com") && MediaFetch.isPageHost("www.giphy.com"), "page hosts");
        check(!MediaFetch.isPageHost("media.tenor.com") && !MediaFetch.isPageHost("tenor.com.evil.com") && !MediaFetch.isPageHost("evil.com"), "not page hosts");

        // links that must be refused before any network access
        check(refused("http://media.tenor.com/a.gif"), "http refused");
        check(refused("https://user@media.tenor.com/a.gif"), "user info refused");
        check(refused("https://media.tenor.com:8443/a.gif"), "port refused");
        check(refused("https://127.0.0.1/a.gif"), "loopback refused");
        check(refused("https://192.168.1.5/a.gif"), "private address refused");

        // ids
        check("17993702444062851269".equals(MediaFetch.tenorId("/view/law-by-mike-arrest-arrested-police-chase-police-gif-17993702444062851269")), "tenor id");
        check("123456".equals(MediaFetch.tenorId("/en/view/some-gif-123456")), "tenor id with locale");
        check(MediaFetch.tenorId("/view/nothing") == null && MediaFetch.tenorId("/NCEt.gif") == null, "no tenor id");
        check("3o7TKSjRrfIPjeiVyM".equals(MediaFetch.giphyId("/gifs/funny-cat-3o7TKSjRrfIPjeiVyM")), "giphy id with slug");
        check("l0HlBO7eyXzSZkJri".equals(MediaFetch.giphyId("/gifs/l0HlBO7eyXzSZkJri")), "giphy id alone");
        check("abc123XYZ".equals(MediaFetch.giphyId("/embed/abc123XYZ/")), "giphy embed id");
        check(MediaFetch.giphyId("/search/cats") == null && MediaFetch.giphyId("/gifs/a-b") == null, "no giphy id");

        // extensions
        check("gif".equals(MediaFetch.extFor("image/gif", "https://x/a")) && "mp4".equals(MediaFetch.extFor("video/mp4", "https://x/a")), "ext from type");
        check("gif".equals(MediaFetch.extFor("application/octet-stream", "https://media.tenor.com/a/b.gif?x=1")), "ext from link");
        check("bin".equals(MediaFetch.extFor("image/x-unknown", "https://media.tenor.com/a/b")), "ext fallback");

        // a Tenor-like page
        String id = "17993702444062851269";
        String html = "<html><head>"
            + "<meta property=\"og:title\" content=\"Law By Mike\">"
            + "<meta property=\"og:image\" content=\"https://media.tenor.com/AAAA1/law.png\">"
            + "<meta property=\"og:video\" content=\"https://media.tenor.com/AAAA2/law.mp4\">"
            + "<meta name=\"twitter:image\" content=\"https://media1.tenor.com/m/AAAA3/law.gif?a=1&amp;b=2\">"
            + "<meta property=\"og:image\" content=\"https://evil.example.com/steal.gif\">"
            + "<link rel=\"image_src\" href=\"https://c.tenor.com/AAAA4/tenor.gif\">"
            + "<script type=\"application/ld+json\">{\"@type\":\"ImageObject\",\"contentUrl\":\"https:\\/\\/media.tenor.com\\/AAAA5\\/law.gif\"}</script>"
            + "</head><body>"
            + "<script>var s={\"results\":[{\"id\":\"" + id + "\",\"media\":{\"gif\":\"https:\\u002F\\u002Fmedia.tenor.com\\u002FNEAR1\\u002Flaw.gif\",\"mp4\":\"https:\\u002F\\u002Fmedia.tenor.com\\u002FNEAR2\\u002Flaw.mp4\"}}]};</script>"
            + "<div>" + "x".repeat(6000) + "</div>"
            + "<script>var related=[{\"id\":\"999999999\",\"gif\":\"https://media.tenor.com/FAR1/other.gif\"}];</script>"
            + "</body></html>";
        List<MediaFetch.Cand> c = MediaFetch.extract(html, id);
        List<String> urls = new java.util.ArrayList<>();
        for (MediaFetch.Cand x : c) urls.add(x.url);
        check(urls.contains("https://media.tenor.com/AAAA1/law.png"), "og:image found");
        check(urls.contains("https://media1.tenor.com/m/AAAA3/law.gif?a=1&b=2"), "entities in a link decoded");
        check(urls.contains("https://c.tenor.com/AAAA4/tenor.gif"), "link rel=image_src found");
        check(urls.contains("https://media.tenor.com/AAAA5/law.gif"), "JSON-LD contentUrl found (escaped slashes)");
        check(urls.contains("https://media.tenor.com/NEAR1/law.gif") && urls.contains("https://media.tenor.com/NEAR2/law.mp4"), "links next to the id found (\\u002F escapes)");
        check(!urls.contains("https://evil.example.com/steal.gif"), "foreign host ignored");
        check(!urls.contains("https://media.tenor.com/FAR1/other.gif"), "unrelated GIF far from the id ignored");
        MediaFetch.Cand best = MediaFetch.choose(c, false);
        check(best != null && best.url.equals("https://media1.tenor.com/m/AAAA3/law.gif?a=1&b=2"), "tags win, GIF beats mp4: " + best);

        // tags without an animated file: fall back to the links next to the id
        String stillOnly = "<meta property=\"og:image\" content=\"https://media.tenor.com/S/still.png\">" + "<i>" + id + "</i><b>https://media.tenor.com/NEAR9/x.mp4</b>";
        MediaFetch.Cand near = MediaFetch.choose(MediaFetch.extract(stillOnly, id), false);
        check(near != null && near.url.equals("https://media.tenor.com/NEAR9/x.mp4") && near.how.equals("near-id"), "falls back to near-id: " + near);

        // a page with only a still picture gives nothing; a pasted direct picture link is allowed
        List<MediaFetch.Cand> stills = MediaFetch.extract("<meta property=\"og:image\" content=\"https://media.tenor.com/S/still.png\">", id);
        check(MediaFetch.choose(stills, false) == null, "still-only page: nothing chosen");
        check(MediaFetch.choose(stills, true) != null, "still allowed for a direct link");
        check(MediaFetch.extract("<html></html>", null).isEmpty() && MediaFetch.extract("", id).isEmpty(), "empty pages");
        check(MediaFetch.summary(MediaFetch.extract("", id)).equals("no media links found"), "summary of nothing");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
