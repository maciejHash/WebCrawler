package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Parsowanie argumentów wiersza poleceń i tekst podsumowania. */
class MainTest {

    @Test
    void startMessageNamesTheProgram() {
        assertTrue(Main.startMessage().startsWith("Web Crawler"));
    }

    @Test
    void onlyUrlGivesDefaults() {
        CrawlerConfig config = Main.parseArgs(new String[] {"https://Example.com"});

        // Adres jest znormalizowany przez CrawlerConfig.
        assertEquals("https://example.com/", config.startUrl());
        assertEquals(CrawlerConfig.DEFAULT_THREADS, config.threads());
        assertEquals(CrawlerConfig.DEFAULT_MAX_PAGES, config.maxPages());
        assertEquals(CrawlerConfig.DEFAULT_MAX_DEPTH, config.maxDepth());
        assertEquals(CrawlerConfig.DEFAULT_SAME_HOST_ONLY, config.sameHostOnly());
        assertEquals(CrawlerConfig.DEFAULT_DB_PATH, config.dbPath());
    }

    @Test
    void parsesAllOptionsInAnyOrder() {
        CrawlerConfig config = Main.parseArgs(new String[] {
                "--threads", "8", "--all-hosts", "https://example.com/start",
                "--max-pages", "50", "--max-depth", "0", "--db", "out.db"});

        assertEquals("https://example.com/start", config.startUrl());
        assertEquals(8, config.threads());
        assertEquals(50, config.maxPages());
        assertEquals(0, config.maxDepth());
        assertFalse(config.sameHostOnly());
        assertEquals("out.db", config.dbPath());
    }

    @Test
    void helpIsRecognizedEvenWithInvalidArguments() {
        assertTrue(Main.isHelpRequested(new String[] {"--threads", "abc", "--help"}));
        assertTrue(Main.isHelpRequested(new String[] {"-h"}));
        assertFalse(Main.isHelpRequested(new String[] {"https://example.com"}));
    }

    /** Argumenty rozdzielone spacjami → fragment oczekiwanego komunikatu. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ''                                             | Brak adresu startowego
            --threads 4                                    | Brak adresu startowego
            https://a.pl https://b.pl                      | więcej niż jeden adres
            https://a.pl --foo                             | Nieznana opcja: --foo
            https://a.pl -x                                | Nieznana opcja: -x
            https://a.pl --threads                         | Brak wartości po --threads
            https://a.pl --db --threads 4                  | Brak wartości po --db
            https://a.pl --threads abc                     | --threads: oczekiwano liczby całkowitej, podano: abc
            https://a.pl --max-pages 99999999999           | --max-pages: oczekiwano liczby całkowitej
            https://a.pl --threads 0                       | --threads: wartość musi być >= 1, podano: 0
            https://a.pl --max-pages -5                    | --max-pages: wartość musi być >= 1
            https://a.pl --max-depth -1                    | --max-depth: wartość musi być >= 0
            https://a.pl --threads 2 --threads 3           | Opcja --threads podana więcej niż raz
            ftp://example.com                              | Niepoprawny adres startowy
            """)
    void invalidArgumentsGiveReadableMessage(String argsLine, String expectedFragment) {
        String[] args = argsLine.isBlank() ? new String[0] : argsLine.trim().split("\\s+");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(args));
        assertTrue(e.getMessage().contains(expectedFragment),
                "komunikat: \"" + e.getMessage() + "\", oczekiwano fragmentu: \"" + expectedFragment + "\"");
    }

    @Test
    void summaryListsPagesAndStatuses() {
        Crawler.Stats stats = new Crawler.Stats(Map.of("VISITED", 3), 3, Duration.ofMillis(1500));

        String text = Main.summary(stats);

        assertTrue(text.contains("pobrane strony: 3"), text);
        assertTrue(text.contains("VISITED"), text);
    }
}
