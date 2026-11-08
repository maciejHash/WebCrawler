package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** extractLinks na HTML z pamięci i pozostałe metody statyczne - bez sieci i bez bazy. */
class CrawlerThreadTest {

    private static final String BASE = "https://example.com/dir/page.html";

    private static List<String> linksFrom(String body) {
        Document doc = Jsoup.parse("<html><body>" + body + "</body></html>", BASE);
        return CrawlerThread.extractLinks(doc);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # względne - rozwiązywane względem adresu strony
            other.html                       | https://example.com/dir/other.html
            ../up.html                       | https://example.com/up.html
            /root.html                       | https://example.com/root.html
            ?q=1                             | https://example.com/dir/page.html?q=1
            # bezwzględne, normalizowane przez UrlUtils
            https://Other.org:443/x          | https://other.org/x
            //cdn.example.com/lib            | https://cdn.example.com/lib
            # fragment usunięty; samo #top wskazuje bieżącą stronę
            other.html#section               | https://example.com/dir/other.html
            #top                             | https://example.com/dir/page.html
            """)
    void extractsAndNormalizesLink(String href, String expected) {
        assertEquals(List.of(expected), linksFrom("<a href=\"" + href + "\">x</a>"));
    }

    @ParameterizedTest(name = "[{index}] href=\"{0}\"")
    @ValueSource(strings = {
            "",
            "   ",
            "mailto:jan@example.com",
            "javascript:void(0)",
            "tel:+48123456789",
            "ftp://example.com/file.txt"
    })
    void dropsUnusableHref(String href) {
        assertTrue(linksFrom("<a href=\"" + href + "\">x</a>").isEmpty());
    }

    @Test
    void ignoresAnchorsWithoutHref() {
        assertTrue(linksFrom("<a name=\"top\">x</a><a>y</a>").isEmpty());
    }

    @Test
    void keepsDuplicatesInDocumentOrder() {
        List<String> links = linksFrom("""
                <a href="a.html">1</a>
                <a href="mailto:x@example.com">2</a>
                <a href="https://example.com/dir/a.html">3</a>
                <a href="b.html">4</a>
                <a href="a.html#again">5</a>
                """);

        assertEquals(List.of(
                "https://example.com/dir/a.html",
                "https://example.com/dir/a.html",
                "https://example.com/dir/b.html",
                "https://example.com/dir/a.html"), links);
    }

    @Test
    void emptyPageHasNoLinks() {
        assertTrue(linksFrom("<p>brak linków</p>").isEmpty());
    }

    private static List<String> linksFromPageWithMeta(String meta) {
        Document doc = Jsoup.parse("<html><head>" + meta + "</head><body>"
                + "<a href=\"a.html\">a</a> <a href=\"b.html\">b</a></body></html>", BASE);
        return CrawlerThread.extractLinks(doc);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "<meta name=\"robots\" content=\"nofollow\">",
            "<meta name=\"robots\" content=\"noindex, nofollow\">",
            "<meta name=\"robots\" content=\"NoIndex,NOFOLLOW\">",
            "<meta name=\"robots\" content=\"noindex nofollow\">",
            "<meta name=\"robots\" content=\"none\">",
            "<meta name=\"ROBOTS\" content=\"None\">",
            "<meta name=\"description\" content=\"x\"><meta name=\"robots\" content=\"nofollow\">"
    })
    void metaRobotsNofollowGivesNoLinks(String meta) {
        assertTrue(linksFromPageWithMeta(meta).isEmpty());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "",
            "<meta name=\"robots\" content=\"noindex\">",
            "<meta name=\"robots\" content=\"index, follow\">",
            "<meta name=\"robots\" content=\"nofollowing\">",
            "<meta name=\"googlebot\" content=\"nofollow\">",
            "<meta name=\"description\" content=\"nofollow\">",
            "<meta property=\"robots\" content=\"nofollow\">"
    })
    void otherMetaTagsKeepLinks(String meta) {
        assertEquals(List.of("https://example.com/dir/a.html", "https://example.com/dir/b.html"),
                linksFromPageWithMeta(meta));
    }

    @Test
    void metaRobotsInBodyIsAlsoRespected() {
        assertTrue(linksFrom("<meta name=\"robots\" content=\"nofollow\"><a href=\"a.html\">a</a>").isEmpty());
    }

    @ParameterizedTest(name = "delay {0} ms, Crawl-delay {1} ms -> {2} ms")
    @CsvSource(delimiter = '|', textBlock = """
            # brak Crawl-delay (0) - samo --delay
            500   | 0          | 500
            0     | 0          | 0
            # wygrywa dłuższa z dwóch przerw
            500   | 2000       | 2000
            3000  | 2000       | 3000
            # Crawl-delay najwyżej MAX_CRAWL_DELAY_MS (10 s), --delay bez limitu
            0     | 60000      | 10000
            500   | 9223372036854775807 | 10000
            20000 | 60000      | 20000
            """)
    void pauseIsLongerOfDelayAndCappedCrawlDelay(int delayMs, long crawlDelayMs, long expected) {
        assertEquals(expected, CrawlerThread.pauseAfterRequestMs(delayMs, crawlDelayMs));
    }

    // ---- ponawianie ----

    @ParameterizedTest(name = "HTTP {0} -> przejściowy: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            429 | true
            500 | true
            502 | true
            503 | true
            599 | true
            400 | false
            403 | false
            404 | false
            410 | false
            """)
    void httpStatusIsTransientOnlyFor429And5xx(int status, boolean expected) {
        HttpStatusException e = new HttpStatusException("HTTP error fetching URL", status, BASE);

        assertEquals(expected, CrawlerThread.isTransient(e));
    }

    @Test
    void timeoutAndRefusedConnectionAreTransient() {
        assertTrue(CrawlerThread.isTransient(new SocketTimeoutException("Read timed out")));
        assertTrue(CrawlerThread.isTransient(new ConnectException("Connection refused")));
    }

    @Test
    void otherErrorsAreNotTransient() {
        // Nieznany host czy zły typ treści nie zmienią się za kilka sekund.
        assertFalse(CrawlerThread.isTransient(new UnknownHostException("nie-ma-takiego-hosta.pl")));
        assertFalse(CrawlerThread.isTransient(
                new UnsupportedMimeTypeException("Unhandled content type", "application/pdf", BASE)));
        assertFalse(CrawlerThread.isTransient(new IOException("inny błąd")));
        assertFalse(CrawlerThread.isTransient(new IllegalArgumentException("zły adres przekierowania")));
    }

    @ParameterizedTest(name = "po {0} próbach -> {1} ms")
    @CsvSource(delimiter = '|', textBlock = """
            0          | 1000
            1          | 2000
            2          | 4000
            3          | 8000
            4          | 16000
            # 32 s - już powyżej limitu 30 s
            5          | 30000
            # wykładnik ograniczony - przesunięcie o >= 64 bity nie "zawija się" do krótkiej przerwy
            64         | 30000
            2147483647 | 30000
            """)
    void retryDelayDoublesUpToLimit(int attempts, long expectedMs) {
        assertEquals(expectedMs, CrawlerThread.retryDelayMs(attempts));
    }
}
