package pl.webcrawler;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Punkt wejścia programu: czyta argumenty wiersza poleceń, uruchamia {@link Crawler}
 * i wypisuje raport. Argumenty parsujemy ręcznie - jest ich kilka, więc
 * biblioteka do tego byłaby zbędną zależnością.
 */
public class Main {

    /** Kod wyjścia przy błędnych argumentach (konwencja: 2 = złe użycie programu). */
    static final int EXIT_USAGE = 2;
    /** Kod wyjścia przy błędzie bazy danych. */
    static final int EXIT_ERROR = 1;

    /** Zapas ponad timeout i przerwę, który shutdown hook daje wątkom na zapis do bazy. */
    static final long SHUTDOWN_MARGIN_MS = 5_000;

    /** Kolejność statusów w raporcie - taka, jak przechodzi przez nie link. */
    static final List<String> STATUSES = List.of("NEW", "IN_PROGRESS", "VISITED", "ERROR", "SKIPPED");

    private static final Set<String> OPTIONS_WITH_VALUE =
            Set.of("--threads", "--max-pages", "--max-depth", "--db", "--delay", "--timeout", "--user-agent");

    /** Wydzielone z main, żeby test mógł sprawdzić treść bez przechwytywania System.out. */
    static String startMessage() {
        return "Web Crawler " + AppVersion.get() + " - start (Java " + Runtime.version() + ")";
    }

    /** Tekst wypisywany przez --version. */
    static String versionMessage() {
        return "Web Crawler " + AppVersion.get();
    }

    static String usage() {
        return """
                Użycie: java -jar web-crawler.jar <startUrl> [opcje]

                  <startUrl>        adres strony startowej (http lub https)
                  --threads N       liczba wątków (domyślnie %d)
                  --max-pages N     wspólny limit stron pobranych w tym uruchomieniu (domyślnie %d)
                  --max-depth N     maksymalna głębokość, strona startowa ma 0 (domyślnie %d)
                  --delay MS        przerwa po każdym żądaniu w milisekundach, żeby nie
                                    przeciążać serwera (domyślnie %d, 0 = bez przerwy)
                  --timeout MS      limit czasu jednego żądania w milisekundach (domyślnie %d)
                  --user-agent TEKST
                                    nagłówek User-Agent, tylko drukowalne znaki ASCII
                                    (domyślnie "%s")
                  --all-hosts       odwiedzaj też inne hosty (domyślnie tylko host startowy)
                  --db plik         plik bazy SQLite (domyślnie %s)
                  --fresh           usuń dane z bazy i zacznij od nowa
                  -h, --help        wyświetl tę pomoc
                  --version         wyświetl wersję programu

                Wznawianie: bez --fresh robot kontynuuje pracę zapisaną w bazie - strony
                już odwiedzone nie są pobierane ponownie, a linki przerwane w trakcie
                wracają do kolejki. Ctrl+C zatrzymuje robota łagodnie: wątki kończą
                bieżące strony, wypisywany jest raport, a reszta czeka na następne uruchomienie.

                Przykłady:
                  java -jar target/web-crawler.jar https://books.toscrape.com --threads 4 --max-pages 50
                  java -jar target/web-crawler.jar https://books.toscrape.com --fresh --delay 1000
                  java -jar target/web-crawler.jar https://books.toscrape.com --timeout 3000 \\
                      --user-agent "MojRobot/1.0 (+https://example.com/bot)"
                """.formatted(CrawlerConfig.DEFAULT_THREADS, CrawlerConfig.DEFAULT_MAX_PAGES,
                CrawlerConfig.DEFAULT_MAX_DEPTH, CrawlerConfig.DEFAULT_DELAY_MS,
                CrawlerConfig.DEFAULT_TIMEOUT_MS, CrawlerConfig.DEFAULT_USER_AGENT,
                CrawlerConfig.DEFAULT_DB_PATH);
    }

    /** --help ma pierwszeństwo przed walidacją: pomoc ma działać nawet przy błędnych argumentach. */
    static boolean isHelpRequested(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                return true;
            }
        }
        return false;
    }

    /** --version, tak jak --help, działa niezależnie od pozostałych (nawet błędnych) argumentów. */
    static boolean isVersionRequested(String[] args) {
        for (String arg : args) {
            if (arg.equals("--version")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zamienia argumenty na konfigurację. Opcje pominięte dostają wartości domyślne
     * z {@link CrawlerConfig}; tam też jest walidacja niezależna od wiersza poleceń
     * (adres startowy, znaki w User-Agent).
     *
     * @throws IllegalArgumentException z komunikatem do pokazania użytkownikowi
     */
    static CrawlerConfig parseArgs(String[] args) {
        String startUrl = null;
        int threads = CrawlerConfig.DEFAULT_THREADS;
        int maxPages = CrawlerConfig.DEFAULT_MAX_PAGES;
        int maxDepth = CrawlerConfig.DEFAULT_MAX_DEPTH;
        int delayMs = CrawlerConfig.DEFAULT_DELAY_MS;
        int timeoutMs = CrawlerConfig.DEFAULT_TIMEOUT_MS;
        String userAgent = CrawlerConfig.DEFAULT_USER_AGENT;
        boolean sameHostOnly = CrawlerConfig.DEFAULT_SAME_HOST_ONLY;
        boolean fresh = CrawlerConfig.DEFAULT_FRESH;
        String dbPath = CrawlerConfig.DEFAULT_DB_PATH;

        Set<String> usedOptions = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                if (startUrl != null) {
                    throw new IllegalArgumentException(
                            "Podano więcej niż jeden adres startowy: " + startUrl + ", " + arg);
                }
                startUrl = arg;
                continue;
            }
            // Powtórzona opcja to najpewniej pomyłka - lepiej ją zgłosić niż po cichu wziąć ostatnią.
            if (!usedOptions.add(arg)) {
                throw new IllegalArgumentException("Opcja " + arg + " podana więcej niż raz");
            }
            // Opcje bez wartości (przełączniki).
            if (arg.equals("--all-hosts")) {
                sameHostOnly = false;
                continue;
            }
            if (arg.equals("--fresh")) {
                fresh = true;
                continue;
            }
            if (!OPTIONS_WITH_VALUE.contains(arg)) {
                throw new IllegalArgumentException("Nieznana opcja: " + arg);
            }
            String value = valueAfter(args, i);
            i++; // wartość opcji już przeczytana - pętla ma ją pominąć
            switch (arg) {
                case "--threads" -> threads = intValue(arg, value, 1);
                case "--max-pages" -> maxPages = intValue(arg, value, 1);
                case "--max-depth" -> maxDepth = intValue(arg, value, 0);
                case "--delay" -> delayMs = intValue(arg, value, 0);
                case "--timeout" -> timeoutMs = intValue(arg, value, 1);
                case "--db" -> dbPath = value;
                case "--user-agent" -> userAgent = value;
            }
        }
        if (startUrl == null) {
            throw new IllegalArgumentException("Brak adresu startowego");
        }
        // Adres i User-Agent sprawdza konstruktor CrawlerConfig.
        return new CrawlerConfig(startUrl, threads, maxPages, maxDepth, sameHostOnly, dbPath, fresh,
                timeoutMs, delayMs, userAgent);
    }

    /** Wartość opcji stojącej na pozycji optionIndex, czyli następny argument. */
    private static String valueAfter(String[] args, int optionIndex) {
        String option = args[optionIndex];
        int valueIndex = optionIndex + 1;
        // "--db --threads 4" - brak wartości, a nie plik o nazwie "--threads".
        if (valueIndex >= args.length || args[valueIndex].startsWith("--")) {
            throw new IllegalArgumentException("Brak wartości po " + option);
        }
        return args[valueIndex];
    }

    private static int intValue(String option, String value, int min) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    option + ": oczekiwano liczby całkowitej, podano: " + value);
        }
        if (number < min) {
            throw new IllegalArgumentException(option + ": wartość musi być >= " + min + ", podano: " + value);
        }
        return number;
    }

    /**
     * Błędne argumenty kończą program kodem {@value #EXIT_USAGE}, błąd bazy kodem
     * {@value #EXIT_ERROR}; poprawne zakończenie (także po Ctrl+C) - kodem 0.
     */
    public static void main(String[] args) {
        if (isHelpRequested(args)) {
            System.out.print(usage());
            return;
        }
        if (isVersionRequested(args)) {
            System.out.println(versionMessage());
            return;
        }
        CrawlerConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Błąd: " + e.getMessage());
            System.err.println();
            System.err.print(usage());
            System.exit(EXIT_USAGE);
            return;
        }

        System.out.println(startMessage());
        System.out.println("Konfiguracja: " + config);

        Crawler crawler = new Crawler(config);
        // Odliczany dopiero po wypisaniu raportu. Shutdown hook na niego czeka, bo JVM
        // kończy działanie zaraz po zakończeniu hooków - bez czekania raport by przepadł,
        // a strony w trakcie pobierania zostałyby w stanie IN_PROGRESS.
        CountDownLatch finished = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> onShutdown(crawler, config, finished), "shutdown-hook"));

        int exitCode = 0;
        try {
            System.out.print(report(crawler.run()));
        } catch (DatabaseException e) {
            Throwable cause = e.getCause();
            System.err.println("Błąd bazy danych: " + e.getMessage()
                    + (cause != null ? " (" + cause.getMessage() + ")" : ""));
            exitCode = EXIT_ERROR;
        } finally {
            finished.countDown();
        }
        // System.exit dopiero po countDown: exit uruchamia hook, a ten czekałby na
        // wątek main, który czekałby w exit na hook - zakleszczenie.
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Uruchamiany przez JVM przy Ctrl+C (SIGINT), kill (SIGTERM), ale też przy zwykłym
     * zakończeniu programu - wtedy praca jest już skończona i nie ma nic do zrobienia.
     */
    private static void onShutdown(Crawler crawler, CrawlerConfig config, CountDownLatch finished) {
        if (finished.getCount() == 0) {
            return;
        }
        log("zatrzymywanie - wątki kończą bieżące strony...");
        crawler.requestStop();
        // Najgorszy przypadek: wątek właśnie zaczął pobierać stronę (timeout), a potem
        // odczeka przerwę (delay). Gdyby i tak nie zdążył, linki IN_PROGRESS wrócą
        // do NEW przy następnym uruchomieniu (resetInProgress).
        // Rzutowanie na long przed dodawaniem: suma dwóch dużych int-ów (np. --timeout
        // i --delay bliskie 2^31) przekroczyłaby zakres int i dała czas ujemny.
        long waitMs = (long) config.timeoutMs() + config.delayMs() + SHUTDOWN_MARGIN_MS;
        try {
            if (!finished.await(waitMs, TimeUnit.MILLISECONDS)) {
                log("wątki nie zakończyły się w " + waitMs + " ms - wyjście bez raportu");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String report(Crawler.Stats stats) {
        StringBuilder text = new StringBuilder();
        text.append("\nRaport\n");
        text.append(String.format("  czas trwania: %.1f s%n", stats.duration().toMillis() / 1000.0));
        text.append("  strony pobrane w tym uruchomieniu: ").append(stats.pagesFetched()).append('\n');
        if (stats.stopped()) {
            text.append("  przerwano przed końcem - uruchom ponownie bez --fresh, aby kontynuować\n");
        }

        text.append("\n  Linki w bazie według statusu:\n");
        // Wszystkie statusy, także z zerem - od razu widać, że np. nic nie zostało IN_PROGRESS.
        for (String status : STATUSES) {
            text.append(String.format("    %-12s %d%n", status, stats.countByStatus().getOrDefault(status, 0)));
        }

        text.append("\n  ").append(Crawler.MOST_SEEN_LIMIT).append(" najczęściej znajdowanych linków (seen):\n");
        int position = 1;
        for (DBConn.LinkRow row : stats.mostSeen()) {
            text.append(String.format("    %2d. %5d  %s%n", position++, row.seen(), row.url()));
        }

        text.append("\n  Strony pobrane przez wątki (VISITED + ERROR, cała baza):\n");
        if (stats.pagesByWorker().isEmpty()) {
            text.append("    brak\n");
        }
        for (Map.Entry<String, Integer> entry : stats.pagesByWorker().entrySet()) {
            text.append(String.format("    %-12s %d%n", entry.getKey(), entry.getValue()));
        }
        return text.toString();
    }

    private static void log(String message) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + message);
    }
}
