package pl.webcrawler;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

/**
 * Pliki robots.txt odwiedzanych hostów, każdy pobrany najwyżej raz na uruchomienie.
 * Jedną instancję tworzy {@link Crawler} i dostają ją wszystkie wątki.
 * robots.txt nie trafia do tabeli links i nie liczy się do limitu maxPages.
 */
public class RobotsCache {

    /** RFC 9309: robot ma przeczytać co najmniej 500 KiB pliku; dalszą część jsoup ucina. */
    static final int MAX_ROBOTS_BYTES = 500 * 1024;

    private final String userAgent;
    private final int timeoutMs;
    /** Klucz: scheme://host[:port]. Zwykła HashMap - używa jej tylko metoda synchronized. */
    private final Map<String, RobotsTxt> byHost = new HashMap<>();

    /**
     * @param userAgent wysyłany przy pobieraniu i używany do wyboru grupy w pliku
     * @param timeoutMs limit czasu pobierania pliku - ten sam co dla stron
     */
    public RobotsCache(String userAgent, int timeoutMs) {
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Reguły robots.txt hosta, na którym leży url. Pierwsze wywołanie dla hosta pobiera plik,
     * kolejne zwracają zapamiętany wynik - także "wszystko zabronione" po błędzie serwera.
     * <p>
     * synchronized obejmuje całe pobieranie - celowo, dla prostoty. Dzięki temu dwa wątki nie
     * pobiorą robots.txt tego samego hosta naraz i nie trzeba osobnej blokady na każdy host.
     * Zwykle odwiedzamy jeden host, więc pozostałe wątki czekają tylko raz, na początku pracy.
     * Przy --all-hosts wątek czeka też, gdy inny pobiera robots.txt innego hosta - akceptujemy to.
     *
     * @param url adres po normalizacji (z bazy)
     * @throws InterruptedException gdy wątek przerwano w trakcie pobierania; wyniku nie zapamiętujemy,
     *                              bo przerwanie nie mówi nic o hoście
     */
    public synchronized RobotsTxt rulesFor(String url) throws InterruptedException {
        String host = hostKey(url);
        RobotsTxt robots = byHost.get(host);
        if (robots == null) {
            robots = download(host + "/robots.txt");
            byHost.put(host, robots);
        }
        return robots;
    }

    /**
     * Pobiera i interpretuje plik: 2xx - reguły z pliku, 4xx - pliku nie ma, więc wszystko wolno,
     * 5xx, timeout, błąd sieci - nie wiadomo, na co host pozwala, więc (jak każe RFC 9309)
     * zakładamy, że na nic.
     */
    private RobotsTxt download(String robotsUrl) throws InterruptedException {
        try {
            Connection.Response response = Jsoup.connect(robotsUrl)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)   // robots.txt to text/plain, a nie HTML
                    .ignoreHttpErrors(true)    // kod odpowiedzi sprawdzamy sami, zamiast łapać wyjątek
                    .maxBodySize(MAX_ROBOTS_BYTES)
                    .execute();
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                // body() czyta treść dopiero teraz - zerwane połączenie w trakcie też trafi do catch.
                RobotsTxt robots = RobotsTxt.parse(response.body(), userAgent);
                log("pobrano " + robotsUrl);
                return robots;
            }
            if (status >= 400 && status < 500) {
                log(robotsUrl + ": HTTP " + status + " - brak ograniczeń");
                return RobotsTxt.allowAll();
            }
            // 5xx (i nietypowe kody, np. 3xx bez adresu docelowego).
            logError(robotsUrl + ": HTTP " + status + " - host pominięty do końca uruchomienia");
            return RobotsTxt.disallowAll();
        } catch (IOException | RuntimeException e) {
            // jsoup przy przerwaniu rzuca IOException i przywraca flagę - po niej poznajemy przerwanie.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Przerwano pobieranie " + robotsUrl);
            }
            logError(robotsUrl + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " - host pominięty do końca uruchomienia");
            return RobotsTxt.disallowAll();
        }
    }

    /**
     * scheme://host[:port] - robots.txt dotyczy dokładnie jednego hosta i portu.
     * Adres jest znormalizowany, więc port domyślny już usunięto, a host ma małe litery.
     */
    private static String hostKey(String url) {
        URI uri = URI.create(url);
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port;
    }

    private static void log(String message) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + message);
    }

    private static void logError(String message) {
        System.err.println("[" + Thread.currentThread().getName() + "] " + message);
    }
}
