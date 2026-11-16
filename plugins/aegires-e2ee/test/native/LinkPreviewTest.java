package aegires.e2ee;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Link preview parsing and address rules (no network). From the plugin folder:
 *   javac -d /tmp/lp src/main/java/aegires/e2ee/LinkPreview.java test/native/LinkPreviewTest.java && java -cp /tmp/lp aegires.e2ee.LinkPreviewTest
 */
public class LinkPreviewTest {
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
            LinkPreview.checkedUrl(url);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<title>Fallback title</title>"
            + "<meta property=\"og:title\" content=\"Tom &amp; Jerry &#8211; the &quot;show&quot;\">"
            + "<meta property='og:description' content='Line one\n   line two &lt;b&gt;'>"
            + "<meta property=og:image content=/img/card.png>"
            + "<meta property=\"og:image:width\" content=\"1200\"><meta property=\"og:image:height\" content=\"630\">"
            + "<meta property=\"og:site_name\" content=\"Example News\">"
            + "</head><body><meta property=\"og:title\" content=\"IGNORED (after head)\"></body></html>";
        Map<String, String> r = LinkPreview.parse(html, "https://example.com/a/b");
        check("Tom & Jerry – the \"show\"".equals(r.get("title")), "title: entities decoded, og:title over <title>");
        check("Line one line two <b>".equals(r.get("description")), "description: whitespace collapsed, entities decoded");
        check("https://example.com/img/card.png".equals(r.get("image")), "relative image url made absolute");
        check("1200".equals(r.get("w")) && "630".equals(r.get("h")), "image size read");
        check("Example News".equals(r.get("site")), "site name");

        Map<String, String> t = LinkPreview.parse("<head><title> Only a title </title></head>", "https://news.example.org/x");
        check("Only a title".equals(t.get("title")) && "news.example.org".equals(t.get("site")) && !t.containsKey("image"), "falls back to <title> and the host");

        Map<String, String> http = LinkPreview.parse("<head><meta property=\"og:title\" content=\"x\"><meta property=\"og:image\" content=\"http://cdn.example.com/a.png\"></head>", "https://example.com/");
        check(!http.containsKey("image"), "a plain http image is dropped");

        check("".equals(LinkPreview.toJson(LinkPreview.parse("<head></head>", "https://example.com/"))), "page with no title and no description gives no embed");

        String json = LinkPreview.toJson(r);
        check(json.startsWith("{\"title\":\"Tom & Jerry – the \\\"show\\\"\"") && json.endsWith("\"w\":1200,\"h\":630}"), "json is escaped and carries the size");
        check(LinkPreview.escape("a\"b\\c\nd\u0001").equals("a\\\"b\\\\c\\nd\\u0001"), "json escaping of quotes, backslash, newline, control char");

        String longDesc = "x".repeat(1000);
        check(LinkPreview.clean(longDesc, 300).length() == 300, "long text is cut to the limit");

        byte[] latin = "<meta charset=\"iso-8859-1\"><title>café</title>".getBytes(StandardCharsets.ISO_8859_1);
        check(LinkPreview.decode(latin, "text/html").contains("café"), "charset from <meta> is honoured");
        check(LinkPreview.decode("hé".getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8").equals("hé"), "charset from the header is honoured");

        // address rules
        String[] blocked = {"127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.9.9", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "224.0.0.1", "255.255.255.255"};
        for (String ip : blocked) check(!LinkPreview.isPublic(InetAddress.getByName(ip)), "not public: " + ip);
        check(LinkPreview.isPublic(InetAddress.getByName("93.184.216.34")), "public: 93.184.216.34");
        check(LinkPreview.isPublic(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")), "public: IPv6 example");

        check(refused("http://example.com/"), "http refused");
        check(refused("https://127.0.0.1/"), "loopback literal refused");
        check(refused("https://192.168.1.1/admin"), "private literal refused");
        check(refused("https://[::1]/"), "IPv6 loopback refused");
        check(refused("https://user:pw@93.184.216.34/"), "user info refused");
        check(refused("https://93.184.216.34:8443/"), "non-443 port refused");
        check(refused("ftp://93.184.216.34/"), "other scheme refused");
        check(!refused("https://93.184.216.34/ok?x=1"), "public https literal accepted");

        if (fails > 0) {
            System.out.println("\n" + fails + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nlink preview tests passed.");
    }
}
