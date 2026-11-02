package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Parsowanie argumentów wiersza poleceń, pomoc i tekst raportu. */
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
        assertEquals(CrawlerConfig.DEFAULT_DELAY_MS, config.delayMs());
        assertFalse(config.fresh(), "domyślnie wznawiamy poprzednią pracę");
    }

    @Test
    void parsesAllOptionsInAnyOrder() {
        CrawlerConfig config = Main.parseArgs(new String[] {
                "--threads", "8", "--all-hosts", "https://example.com/start",
                "--max-pages", "50", "--max-depth", "0", "--db", "out.db",
                "--fresh", "--delay", "0"});

        assertEquals("https://example.com/start", config.startUrl());
        assertEquals(8, config.threads());
        assertEquals(50, config.maxPages());
        assertEquals(0, config.maxDepth());
        assertFalse(config.sameHostOnly());
        assertEquals("out.db", config.dbPath());
        assertTrue(config.fresh());
        assertEquals(0, config.delayMs());
    }

    @Test
    void delayAcceptsLargeValue() {
        CrawlerConfig config = Main.parseArgs(new String[] {"https://a.pl", "--delay", "2500"});

        assertEquals(2500, config.delayMs());
    }

    @Test
    void usageDescribesNewOptionsAndResuming() {
        String usage = Main.usage();

        assertTrue(usage.contains("--fresh"), usage);
        assertTrue(usage.contains("--delay"), usage);
        assertTrue(usage.contains("Wznawianie"), usage);
        assertTrue(usage.contains("java -jar"), usage);
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
            https://a.pl --fresh --fresh                   | Opcja --fresh podana więcej niż raz
            https://a.pl --delay                           | Brak wartości po --delay
            https://a.pl --delay -1                        | --delay: wartość musi być >= 0, podano: -1
            https://a.pl --delay 1s                        | --delay: oczekiwano liczby całkowitej, podano: 1s
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
    void reportShowsAllSections() {
        List<DBConn.LinkRow> mostSeen = List.of(
                new DBConn.LinkRow(2, "https://a.pl/popular", 1, 7, "VISITED", "crawler-1", null, null),
                new DBConn.LinkRow(1, "https://a.pl/", 0, 2, "VISITED", "crawler-2", null, null));
        Map<String, Integer> byWorker = new LinkedHashMap<>();
        byWorker.put("crawler-2", 4);
        byWorker.put("crawler-10", 1);
        Crawler.Stats stats = new Crawler.Stats(Map.of("VISITED", 3, "NEW", 5), mostSeen, byWorker,
                3, Duration.ofMillis(1500), false);

        String text = Main.report(stats);

        assertTrue(text.contains("czas trwania: 1.5 s") || text.contains("czas trwania: 1,5 s"), text);
        assertTrue(text.contains("strony pobrane w tym uruchomieniu: 3"), text);
        // Statusy bez linków też są w raporcie, z zerem.
        assertTrue(text.matches("(?s).*VISITED\\s+3\\n.*"), text);
        assertTrue(text.matches("(?s).*IN_PROGRESS\\s+0\\n.*"), text);
        assertTrue(text.matches("(?s).* 1\\.\\s+7\\s+https://a\\.pl/popular\\n.*"), text);
        assertTrue(text.indexOf("crawler-2") < text.indexOf("crawler-10"), "kolejność z bazy: " + text);
        assertFalse(text.contains("przerwano"), text);
    }

    @Test
    void reportMentionsStopAndResume() {
        Crawler.Stats stats = new Crawler.Stats(Map.of(), List.of(), Map.of(), 0, Duration.ZERO, true);

        String text = Main.report(stats);

        assertTrue(text.contains("przerwano"), text);
        assertTrue(text.contains("bez --fresh"), text);
    }
}
