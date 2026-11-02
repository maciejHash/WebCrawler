package pl.webcrawler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Punkt wejścia programu: czyta argumenty wiersza poleceń, uruchamia {@link Crawler}
 * i wypisuje podsumowanie. Argumenty parsujemy ręcznie - jest ich kilka, więc
 * biblioteka do tego byłaby zbędną zależnością.
 */
public class Main {

    /** Kod wyjścia przy błędnych argumentach (konwencja: 2 = złe użycie programu). */
    static final int EXIT_USAGE = 2;
    /** Kod wyjścia przy błędzie bazy danych. */
    static final int EXIT_ERROR = 1;

    private static final Set<String> OPTIONS_WITH_VALUE =
            Set.of("--threads", "--max-pages", "--max-depth", "--db");

    /** Wydzielone z main, żeby test mógł sprawdzić treść bez przechwytywania System.out. */
    static String startMessage() {
        return "Web Crawler - start (Java " + Runtime.version() + ")";
    }

    static String usage() {
        return """
                Użycie: webcrawler <startUrl> [opcje]

                  <startUrl>        adres strony startowej (http lub https)
                  --threads N       liczba wątków (domyślnie %d)
                  --max-pages N     wspólny limit pobranych stron (domyślnie %d)
                  --max-depth N     maksymalna głębokość, strona startowa ma 0 (domyślnie %d)
                  --all-hosts       odwiedzaj też inne hosty (domyślnie tylko host startowy)
                  --db plik         plik bazy SQLite (domyślnie %s)
                  -h, --help        wyświetl tę pomoc

                Przykład:
                  mvn -q exec:java -Dexec.args="https://books.toscrape.com --threads 4 --max-pages 50"
                """.formatted(CrawlerConfig.DEFAULT_THREADS, CrawlerConfig.DEFAULT_MAX_PAGES,
                CrawlerConfig.DEFAULT_MAX_DEPTH, CrawlerConfig.DEFAULT_DB_PATH);
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

    /**
     * Zamienia argumenty na konfigurację. Pola, których nie da się ustawić z wiersza
     * poleceń (timeout, przerwa, User-Agent), dostają wartości domyślne.
     *
     * @throws IllegalArgumentException z komunikatem do pokazania użytkownikowi
     */
    static CrawlerConfig parseArgs(String[] args) {
        String startUrl = null;
        int threads = CrawlerConfig.DEFAULT_THREADS;
        int maxPages = CrawlerConfig.DEFAULT_MAX_PAGES;
        int maxDepth = CrawlerConfig.DEFAULT_MAX_DEPTH;
        boolean sameHostOnly = CrawlerConfig.DEFAULT_SAME_HOST_ONLY;
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
            if (arg.equals("--all-hosts")) {
                sameHostOnly = false;
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
                case "--db" -> dbPath = value;
            }
        }
        if (startUrl == null) {
            throw new IllegalArgumentException("Brak adresu startowego");
        }
        // Adres sprawdza i normalizuje konstruktor CrawlerConfig.
        return new CrawlerConfig(startUrl, threads, maxPages, maxDepth, sameHostOnly, dbPath,
                CrawlerConfig.DEFAULT_TIMEOUT_MS, CrawlerConfig.DEFAULT_DELAY_MS,
                CrawlerConfig.DEFAULT_USER_AGENT);
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

    public static void main(String[] args) {
        if (isHelpRequested(args)) {
            System.out.print(usage());
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
        try {
            Crawler.Stats stats = new Crawler(config).run();
            System.out.print(summary(stats));
        } catch (DatabaseException e) {
            Throwable cause = e.getCause();
            System.err.println("Błąd bazy danych: " + e.getMessage()
                    + (cause != null ? " (" + cause.getMessage() + ")" : ""));
            System.exit(EXIT_ERROR);
        }
    }

    static String summary(Crawler.Stats stats) {
        StringBuilder text = new StringBuilder();
        text.append("\nPodsumowanie\n");
        text.append("  pobrane strony: ").append(stats.pagesFetched()).append('\n');
        text.append("  czas: ").append(String.format("%.1f s", stats.duration().toMillis() / 1000.0)).append('\n');
        text.append("  linki w bazie według statusu:\n");
        for (Map.Entry<String, Integer> entry : stats.countByStatus().entrySet()) {
            text.append(String.format("    %-12s %d%n", entry.getKey(), entry.getValue()));
        }
        return text.toString();
    }
}
