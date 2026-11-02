package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** extractLinks na HTML z pamięci - bez sieci i bez bazy. */
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
}
