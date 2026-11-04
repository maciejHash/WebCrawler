package pl.webcrawler;

/**
 * Ustawienia jednego uruchomienia robota. Record, bo konfiguracja po utworzeniu
 * się nie zmienia, a wątki mogą ją bezpiecznie współdzielić.
 *
 * @param startUrl     adres pierwszej strony (zapisywany po normalizacji)
 * @param threads      liczba wątków w puli
 * @param maxPages     wspólny dla wszystkich wątków limit pobranych stron
 * @param maxDepth     maksymalna głębokość; strona startowa ma głębokość 0
 * @param sameHostOnly czy odwiedzać tylko strony z hosta startowego
 * @param dbPath       ścieżka do pliku bazy SQLite
 * @param fresh        true: usuń poprzednie dane z bazy i zacznij od nowa;
 *                     false: wznów pracę zapisaną w bazie
 * @param timeoutMs    limit czasu jednego żądania HTTP
 * @param delayMs      przerwa po każdym żądaniu, żeby nie przeciążać serwera
 * @param userAgent    nagłówek User-Agent wysyłany z każdym żądaniem
 * @param respectRobots czy pobierać robots.txt każdego hosta i pomijać strony, których zabrania
 */
public record CrawlerConfig(String startUrl, int threads, int maxPages, int maxDepth,
                            boolean sameHostOnly, String dbPath, boolean fresh, int timeoutMs,
                            int delayMs, String userAgent, boolean respectRobots) {

    public static final int DEFAULT_THREADS = 4;
    public static final int DEFAULT_MAX_PAGES = 100;
    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final boolean DEFAULT_SAME_HOST_ONLY = true;
    public static final String DEFAULT_DB_PATH = "crawler.db";
    public static final boolean DEFAULT_FRESH = false;
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_DELAY_MS = 500;
    // Wersja z pom.xml, żeby administrator serwera wiedział, która wersja robota go odwiedza.
    // Tylko drukowalne ASCII - nagłówki HTTP nie powinny zawierać innych znaków (sprawdza konstruktor).
    public static final String DEFAULT_USER_AGENT = "pl.webcrawler/" + AppVersion.get() + " (student project)";
    public static final boolean DEFAULT_RESPECT_ROBOTS = true;
    /**
     * Górny limit Crawl-delay z robots.txt. Plik pisze właściciel serwera, nie użytkownik - wartość
     * typu "Crawl-delay: 3600" zatrzymałaby wątek na godzinę po każdej stronie i opóźniła Ctrl+C.
     * Nie ogranicza --delay: tę wartość użytkownik wybrał sam.
     */
    public static final int MAX_CRAWL_DELAY_MS = 10_000;

    /**
     * Sprawdza wartości od razu przy tworzeniu, żeby błąd w argumentach wyszedł na starcie,
     * a nie w połowie pracy wątków. Adres startowy jest normalizowany tak samo jak
     * każdy inny link, żeby trafił do bazy w tej samej postaci.
     */
    public CrawlerConfig {
        if (startUrl == null) {
            throw new IllegalArgumentException("Brak adresu startowego");
        }
        String original = startUrl;
        startUrl = UrlUtils.normalize(startUrl).orElseThrow(() ->
                new IllegalArgumentException("Niepoprawny adres startowy (wymagany http/https): " + original));
        requireAtLeast("threads", threads, 1);
        requireAtLeast("maxPages", maxPages, 1);
        requireAtLeast("maxDepth", maxDepth, 0);
        requireAtLeast("timeoutMs", timeoutMs, 1);
        requireAtLeast("delayMs", delayMs, 0);
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("Brak ścieżki do bazy");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("Brak User-Agent");
        }
        requirePrintableAscii(userAgent);
    }

    /**
     * Nagłówki HTTP powinny zawierać tylko drukowalne ASCII. Znak nowej linii pozwoliłby
     * dopisać do żądania własny nagłówek, a polskie litery biblioteka HTTP mogłaby
     * zakodować różnie albo odrzucić dopiero przy pierwszym żądaniu - lepiej zgłosić to na starcie.
     */
    private static void requirePrintableAscii(String userAgent) {
        for (int i = 0; i < userAgent.length(); i++) {
            char c = userAgent.charAt(i);
            if (c < ' ' || c > '~') {
                throw new IllegalArgumentException(String.format(
                        "User-Agent może zawierać tylko drukowalne znaki ASCII (od spacji do ~),"
                                + " niedozwolony znak U+%04X na pozycji %d",
                        userAgent.codePointAt(i), i + 1));
            }
        }
    }

    private static void requireAtLeast(String name, int value, int min) {
        if (value < min) {
            throw new IllegalArgumentException(name + " musi być >= " + min + ", podano: " + value);
        }
    }
}
