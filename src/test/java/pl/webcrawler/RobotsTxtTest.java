package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Parsowanie robots.txt i dopasowanie reguł - sama logika, bez sieci. */
class RobotsTxtTest {

    private static final String AGENT = "pl.webcrawler/1.2.0 (student project)";

    private static boolean allowed(String robotsTxt, String path) {
        return RobotsTxt.parse(robotsTxt, AGENT).isAllowed(path);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            pl.webcrawler/1.2.0 (student project)   | pl.webcrawler
            MojRobot/2.0 (+https://example.com/bot) | MojRobot
            MojRobot (+https://example.com/bot)     | MojRobot
            test-agent                              | test-agent
            """)
    void productTokenIsPartBeforeSlashOrSpace(String userAgent, String expected) {
        assertEquals(expected, RobotsTxt.productToken(userAgent));
    }

    /** Opis, treść pliku, ścieżka, czy wolno. Ścieżka /x jest zabroniona tylko w "naszej" grupie. */
    static Stream<Arguments> groupSelection() {
        return Stream.of(
                Arguments.of("nasza grupa zamiast *", """
                        User-agent: *
                        Disallow: /
                        User-agent: pl.webcrawler
                        Disallow: /x
                        """, "/y", true),
                Arguments.of("nasza grupa - jej reguły obowiązują", """
                        User-agent: *
                        Disallow: /
                        User-agent: pl.webcrawler
                        Disallow: /x
                        """, "/x", false),
                Arguments.of("nazwa grupy bez względu na wielkość liter", """
                        User-agent: PL.WebCrawler
                        Disallow: /x
                        """, "/x", false),
                Arguments.of("brak naszej grupy - grupa *", """
                        User-agent: Googlebot
                        Disallow: /
                        User-agent: *
                        Disallow: /x
                        """, "/x", false),
                Arguments.of("grupa innego robota nas nie dotyczy", """
                        User-agent: Googlebot
                        Disallow: /
                        """, "/x", true),
                Arguments.of("kilka linii User-agent to jedna grupa", """
                        User-agent: Googlebot
                        User-agent: pl.webcrawler
                        Disallow: /x
                        """, "/x", false),
                Arguments.of("User-agent po regułach zaczyna nową grupę", """
                        User-agent: pl.webcrawler
                        Disallow: /x
                        User-agent: Googlebot
                        Disallow: /y
                        """, "/y", true),
                Arguments.of("dwie grupy o naszej nazwie łączą się", """
                        User-agent: pl.webcrawler
                        Disallow: /a
                        User-agent: Googlebot
                        Disallow: /
                        User-agent: pl.webcrawler
                        Disallow: /x
                        """, "/x", false),
                Arguments.of("nasza grupa z pustym Disallow pozwala na wszystko mimo *", """
                        User-agent: *
                        Disallow: /
                        User-agent: pl.webcrawler
                        Disallow:
                        """, "/x", true),
                Arguments.of("token z wersją w pliku nie pasuje", """
                        User-agent: pl.webcrawler/1.2.0
                        Disallow: /x
                        """, "/x", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groupSelection")
    void choosesOwnGroupThenStarThenNone(String description, String robotsTxt, String path, boolean expected) {
        assertEquals(expected, allowed(robotsTxt, path));
    }

    @ParameterizedTest(name = "{0} ~ {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            # zwykły wzorzec pasuje do początku ścieżki
            /           | /anything          | true
            /shop       | /shop              | true
            /shop       | /shopping          | true
            /shop       | /sho               | false
            /shop/      | /shop              | false
            /Shop       | /shop              | false
            /search?q=  | /search?q=java     | true
            /search?q=  | /search            | false
            # * - dowolny ciąg znaków, także pusty
            /*.php      | /index.php         | true
            /*.php      | /dir/a.php?x=1     | true
            /a*b*c      | /a-b-c             | true
            /a*b*c      | /abc               | true
            /a*b*c      | /acb               | false
            /a**b       | /a-b               | true
            /tmp*       | /tmp               | true
            *           | /x                 | true
            # $ na końcu - koniec adresu
            /*.php$     | /index.php         | true
            /*.php$     | /index.php?x=1     | false
            /*.php$     | /index.phps        | false
            /exact$     | /exact             | true
            /exact$     | /exact/more        | false
            /a*b$       | /ab/b              | true
            /a*b$       | /abx               | false
            /$          | /                  | true
            /$          | /a                 | false
            """)
    void matchesWildcardsAndEndAnchor(String pattern, String path, boolean expected) {
        assertEquals(expected, RobotsTxt.matches(pattern, path));
    }

    private static final String RULES = """
            User-agent: *
            Disallow: /shop
            Allow: /shop/public
            Disallow: /shop/public/secret
            Allow: /page
            Disallow: /page
            Disallow: /item
            Allow: /item
            Disallow: /*.pdf$
            Allow: /docs/*.pdf$
            """;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # wygrywa najdłuższy pasujący wzorzec, niezależnie od kolejności w pliku
            /shop/cart              | false
            /shop/public/list       | true
            /shop/public/secret/x   | false
            /docs/a.pdf             | true
            /files/a.pdf            | false
            /files/a.pdf?download=1 | true
            # remis długości - wygrywa Allow, w obu kolejnościach reguł
            /page                   | true
            /item                   | true
            # żadna reguła nie pasuje - wolno
            /other                  | true
            """)
    void longestMatchWinsAndTieGoesToAllow(String path, boolean expected) {
        assertEquals(expected, allowed(RULES, path));
    }

    /** Opis, treść pliku, ścieżka, czy wolno. */
    static Stream<Arguments> malformedOrUnusualFiles() {
        return Stream.of(
                Arguments.of("pusty plik", "", "/private", true),
                Arguments.of("strona HTML zamiast robots.txt", "<html><body>Nie ma</body></html>", "/private", true),
                Arguments.of("pusty Disallow niczego nie blokuje", "User-agent: *\nDisallow:\n", "/private", true),
                Arguments.of("pusty Disallow z komentarzem", "User-agent: *\nDisallow: # nic\n", "/private", true),
                Arguments.of("pusty Allow niczego nie odblokowuje", "User-agent: *\nDisallow: /\nAllow:\n", "/x", false),
                Arguments.of("BOM na początku", "﻿User-agent: *\nDisallow: /private\n", "/private", false),
                Arguments.of("końce linii CRLF", "User-agent: *\r\nDisallow: /private\r\n", "/private", false),
                Arguments.of("końce linii CR", "User-agent: *\rDisallow: /private\r", "/private", false),
                Arguments.of("komentarze na końcu linii",
                        "# robots\nUser-agent: * # wszyscy\nDisallow: /private # tajne\n", "/private", false),
                Arguments.of("zakomentowana reguła", "User-agent: *\n# Disallow: /private\n", "/private", true),
                Arguments.of("puste linie w grupie", "User-agent: *\n\n\nDisallow: /private\n", "/private", false),
                Arguments.of("nieznane dyrektywy pominięte",
                        "User-agent: *\nSitemap: https://example.com/s.xml\nHost: example.com\nNoindex: /x\n"
                                + "Disallow: /private\n", "/private", false),
                Arguments.of("nieznana dyrektywa niczego nie blokuje", "User-agent: *\nNoindex: /x\n", "/x", true),
                Arguments.of("linie bez dwukropka pominięte", "User-agent *\nDisallow /private\n", "/private", true),
                Arguments.of("reguły przed User-agent nie należą do grupy",
                        "Disallow: /private\nUser-agent: *\nDisallow: /other\n", "/private", true),
                Arguments.of("wielkie litery i spacje przy dwukropku",
                        "USER-AGENT : *\nDISALLOW :   /private  \n", "/private", false),
                Arguments.of("dwukropek w wartości", "User-agent: *\nDisallow: /a:b\n", "/a:b", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedOrUnusualFiles")
    void skipsGarbageAndUnderstandsTheRest(String description, String robotsTxt, String path, boolean expected) {
        assertEquals(expected, allowed(robotsTxt, path));
    }

    @ParameterizedTest(name = "Crawl-delay: \"{0}\" -> {1} ms")
    @CsvSource(delimiter = '|', textBlock = """
            2       | 2000
            0.5     | 500
            1.25    | 1250
            0       | 0
            '  3  ' | 3000
            # niepoprawny zapis - jak brak Crawl-delay
            abc     |
            -1      |
            '1,5'   |
            2s      |
            ''      |
            """)
    void crawlDelayInSecondsBecomesMilliseconds(String value, Long expectedMs) {
        RobotsTxt robots = RobotsTxt.parse("User-agent: *\nCrawl-delay: " + value + "\n", AGENT);

        OptionalLong expected = expectedMs == null ? OptionalLong.empty() : OptionalLong.of(expectedMs);
        assertEquals(expected, robots.crawlDelayMs());
    }

    @Test
    void crawlDelayComesFromChosenGroupOnly() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Crawl-delay: 5
                User-agent: pl.webcrawler
                Disallow: /x
                """, AGENT);

        assertEquals(OptionalLong.empty(), robots.crawlDelayMs(), "nasza grupa nie podaje Crawl-delay");
    }

    @Test
    void firstValidCrawlDelayInGroupWins() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: pl.webcrawler
                Crawl-delay: zaraz
                Crawl-delay: 1.5
                Crawl-delay: 7
                """, AGENT);

        assertEquals(OptionalLong.of(1500), robots.crawlDelayMs());
    }

    @Test
    void noGroupAtAllAllowsEverythingWithoutDelay() {
        RobotsTxt robots = RobotsTxt.parse("Sitemap: https://example.com/sitemap.xml\n", AGENT);

        assertTrue(robots.isAllowed("/anything?q=1"));
        assertEquals(OptionalLong.empty(), robots.crawlDelayMs());
    }

    @Test
    void allowAllAndDisallowAll() {
        assertTrue(RobotsTxt.allowAll().isAllowed("/"));
        assertTrue(RobotsTxt.allowAll().isAllowed("/a/b?c=d"));
        assertFalse(RobotsTxt.disallowAll().isAllowed("/"));
        assertFalse(RobotsTxt.disallowAll().isAllowed("/a/b?c=d"));
        assertEquals(OptionalLong.empty(), RobotsTxt.disallowAll().crawlDelayMs());
    }
}
