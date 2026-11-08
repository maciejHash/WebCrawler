package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Parsowanie argumentów wiersza poleceń, pomoc i tekst raportu. */
class MainTest {

    @Test
    void startMessageShowsProgramAndJavaVersion() {
        String message = Main.startMessage();

        assertTrue(message.startsWith("Web Crawler " + AppVersion.get()), message);
        assertTrue(message.contains("Java " + Runtime.version()), message);
    }

    @Test
    void versionMessageShowsVersionFromPom() {
        assertEquals("Web Crawler " + AppVersion.get(), Main.versionMessage());
    }

    @Test
    void versionIsRecognizedEvenWithInvalidArguments() {
        assertTrue(Main.isVersionRequested(new String[] {"--version"}));
        assertTrue(Main.isVersionRequested(new String[] {"--threads", "abc", "--version"}));
        assertTrue(Main.isVersionRequested(new String[] {"--foo", "--version", "ftp://x"}));
        assertFalse(Main.isVersionRequested(new String[] {"https://example.com"}));
        // --version nie jest zwykłą opcją, więc parseArgs ją odrzuca - main sprawdza ją wcześniej.
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[] {"https://example.com", "--version"}));
    }

    @Test
    void defaultUserAgentContainsVersionAndIsValidAscii() {
        String expected = "pl.webcrawler/" + AppVersion.get() + " (student project)";
        assertEquals(expected, CrawlerConfig.DEFAULT_USER_AGENT);

        // Konstruktor CrawlerConfig sprawdza znaki User-Agent - domyślna wartość musi przejść.
        CrawlerConfig config = Main.parseArgs(new String[] {"https://example.com"});
        assertEquals(expected, config.userAgent());
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
        assertEquals(CrawlerConfig.DEFAULT_TIMEOUT_MS, config.timeoutMs());
        assertEquals(CrawlerConfig.DEFAULT_USER_AGENT, config.userAgent());
        assertFalse(config.fresh(), "domyślnie wznawiamy poprzednią pracę");
        assertTrue(config.respectRobots(), "domyślnie robots.txt jest respektowany");
        assertEquals(CrawlerConfig.DEFAULT_MAX_RETRIES, config.maxRetries());
        assertEquals(2, config.maxRetries(), "domyślnie dwa ponowienia, czyli najwyżej trzy próby");
    }

    @Test
    void retriesAcceptsZeroAndLargeValues() {
        assertEquals(0, Main.parseArgs(new String[] {"https://a.pl", "--retries", "0"}).maxRetries(),
                "0 = bez ponawiania");
        assertEquals(5, Main.parseArgs(new String[] {"--retries", "5", "https://a.pl"}).maxRetries());
        // Przerwa i tak nie przekroczy 30 s - retryDelayMs ogranicza wykładnik.
        assertEquals(Integer.MAX_VALUE, Main.parseArgs(
                new String[] {"https://a.pl", "--retries", String.valueOf(Integer.MAX_VALUE)}).maxRetries());
    }

    @Test
    void negativeRetriesAreRejectedAlsoByConfig() {
        // Ta sama granica w CrawlerConfig - na wypadek tworzenia konfiguracji z pominięciem Main.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CrawlerConfig("https://a.pl", 1, 1, 0, true, "x.db", false, 1_000, 0, "agent", true, -1));
        assertTrue(e.getMessage().contains("maxRetries musi być >= 0"), e.getMessage());
    }

    @Test
    void parsesAllOptionsInAnyOrder() {
        CrawlerConfig config = Main.parseArgs(new String[] {
                "--threads", "8", "--all-hosts", "https://example.com/start",
                "--max-pages", "50", "--max-depth", "0", "--db", "out.db",
                "--fresh", "--delay", "0", "--timeout", "2500", "--ignore-robots", "--retries", "4",
                // Jeden argument ze spacjami - tak przekaże go powłoka dla wartości w cudzysłowie.
                "--user-agent", "MojRobot/2.0 (+https://example.com/bot)"});

        assertEquals("https://example.com/start", config.startUrl());
        assertEquals(8, config.threads());
        assertEquals(50, config.maxPages());
        assertEquals(0, config.maxDepth());
        assertFalse(config.sameHostOnly());
        assertEquals("out.db", config.dbPath());
        assertTrue(config.fresh());
        assertEquals(0, config.delayMs());
        assertEquals(2500, config.timeoutMs());
        assertEquals("MojRobot/2.0 (+https://example.com/bot)", config.userAgent());
        assertFalse(config.respectRobots());
        assertEquals(4, config.maxRetries());
    }

    private static CrawlerConfig configWith(int timeoutMs, int delayMs, boolean respectRobots) {
        return new CrawlerConfig("https://a.pl", 1, 1, 0, true, "x.db", false, timeoutMs, delayMs,
                "test-agent", respectRobots, CrawlerConfig.DEFAULT_MAX_RETRIES);
    }

    @Test
    void shutdownWaitCoversRobotsFetchPageFetchAndLongestPause() {
        long margin = Main.SHUTDOWN_MARGIN_MS;
        // robots.txt + strona (2 × timeout) + przerwa, która może urosnąć do MAX_CRAWL_DELAY_MS.
        assertEquals(2 * 2_000 + CrawlerConfig.MAX_CRAWL_DELAY_MS + margin,
                Main.shutdownWaitMs(configWith(2_000, 500, true)));
        // --delay dłuższe niż limit Crawl-delay - liczy się --delay.
        assertEquals(2 * 2_000 + 20_000 + margin, Main.shutdownWaitMs(configWith(2_000, 20_000, true)));
        // --ignore-robots: bez pobierania robots.txt i bez Crawl-delay.
        assertEquals(2_000 + 500 + margin, Main.shutdownWaitMs(configWith(2_000, 500, false)));
        // Bardzo duże wartości nie przekraczają zakresu.
        assertTrue(Main.shutdownWaitMs(configWith(Integer.MAX_VALUE, Integer.MAX_VALUE, true)) > 0);
    }

    @Test
    void timeoutAcceptsMinimalValue() {
        CrawlerConfig config = Main.parseArgs(new String[] {"https://a.pl", "--timeout", "1"});

        assertEquals(1, config.timeoutMs());
    }

    @Test
    void userAgentAcceptsWholePrintableAsciiRange() {
        // Wszystkie znaki od spacji (0x20) do tyldy (0x7E) - granice zakresu z CrawlerConfig.
        StringBuilder userAgent = new StringBuilder("Robot");
        for (char c = ' '; c <= '~'; c++) {
            userAgent.append(c);
        }

        CrawlerConfig config = Main.parseArgs(
                new String[] {"https://a.pl", "--user-agent", userAgent.toString()});

        assertEquals(userAgent.toString(), config.userAgent());
    }

    /** User-Agent → fragment oczekiwanego komunikatu. Znaki sterujące wpisane jako zwykłe literały Javy. */
    static Stream<Arguments> invalidUserAgents() {
        return Stream.of(
                Arguments.of("", "Brak User-Agent"),
                Arguments.of("   ", "Brak User-Agent"),
                Arguments.of("Robot-ł", "niedozwolony znak U+0142 na pozycji 7"),
                Arguments.of("Robot\u00a0x", "niedozwolony znak U+00A0"),       // twarda spacja
                Arguments.of("Robot\tx", "niedozwolony znak U+0009"),
                Arguments.of("Robot\r\nX-Evil: 1", "niedozwolony znak U+000D"), // próba dopisania nagłówka
                Arguments.of("Robot\u007f", "niedozwolony znak U+007F"),         // DEL, tuż za tyldą
                Arguments.of("Robot \uD83D\uDE00", "niedozwolony znak U+1F600")); // emoji
    }

    @ParameterizedTest
    @MethodSource("invalidUserAgents")
    void invalidUserAgentIsRejected(String userAgent, String expectedFragment) {
        String[] args = {"https://a.pl", "--user-agent", userAgent};

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(args));
        assertTrue(e.getMessage().contains(expectedFragment),
                "komunikat: \"" + e.getMessage() + "\", oczekiwano fragmentu: \"" + expectedFragment + "\"");
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
        assertTrue(usage.contains("--timeout"), usage);
        assertTrue(usage.contains("--user-agent"), usage);
        assertTrue(usage.contains("--version"), usage);
        assertTrue(usage.contains("--ignore-robots"), usage);
        assertTrue(usage.contains("--retries N"), usage);
        assertTrue(usage.contains("(domyślnie " + CrawlerConfig.DEFAULT_MAX_RETRIES + ","), usage);
        assertTrue(usage.contains(CrawlerConfig.DEFAULT_USER_AGENT), usage);
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
            https://a.pl --ignore-robots --ignore-robots   | Opcja --ignore-robots podana więcej niż raz
            https://a.pl --ignore-robots=yes               | Nieznana opcja: --ignore-robots=yes
            https://a.pl --delay                           | Brak wartości po --delay
            https://a.pl --delay -1                        | --delay: wartość musi być >= 0, podano: -1
            https://a.pl --delay 1s                        | --delay: oczekiwano liczby całkowitej, podano: 1s
            https://a.pl --timeout                         | Brak wartości po --timeout
            https://a.pl --timeout --fresh                 | Brak wartości po --timeout
            https://a.pl --timeout 0                       | --timeout: wartość musi być >= 1, podano: 0
            https://a.pl --timeout -500                    | --timeout: wartość musi być >= 1, podano: -500
            https://a.pl --timeout 5s                      | --timeout: oczekiwano liczby całkowitej, podano: 5s
            https://a.pl --timeout 1000 --timeout 2000     | Opcja --timeout podana więcej niż raz
            https://a.pl --user-agent                      | Brak wartości po --user-agent
            https://a.pl --user-agent --fresh              | Brak wartości po --user-agent
            https://a.pl --user-agent A --user-agent B     | Opcja --user-agent podana więcej niż raz
            https://a.pl --retries                         | Brak wartości po --retries
            https://a.pl --retries --fresh                 | Brak wartości po --retries
            https://a.pl --retries -1                      | --retries: wartość musi być >= 0, podano: -1
            https://a.pl --retries dwa                     | --retries: oczekiwano liczby całkowitej, podano: dwa
            https://a.pl --retries 1.5                     | --retries: oczekiwano liczby całkowitej, podano: 1.5
            https://a.pl --retries 1 --retries 2           | Opcja --retries podana więcej niż raz
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
                new DBConn.LinkRow(2, "https://a.pl/popular", 1, 7, "VISITED", "crawler-1", null, null, 0, null),
                new DBConn.LinkRow(1, "https://a.pl/", 0, 2, "VISITED", "crawler-2", null, null, 0, null));
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
