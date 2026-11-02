package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Cały robot (pula wątków + baza) na lokalnej stronie z {@link #PAGES} podstronami.
 * Strona k linkuje do (k+1), (k+5) i (7k) modulo PAGES - gęsta sieć linków, więc wątki
 * często trafiają na te same adresy. Żadna strona nie linkuje do "/", dzięki czemu
 * seen strony startowej pokazuje, czy ponowne uruchomienie go nie zawyża.
 */
@Timeout(30)
class CrawlerTest {

    private static final int PAGES = 12;
    private static final int THREADS = 4;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;
    /** Ścieżka → liczba żądań; najważniejsza asercja: każda wartość równa 1. */
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Domyślnie HttpServer obsługuje żądania po kolei; pula pozwala wątkom robota
        // pobierać strony naprawdę równolegle.
        serverPool = Executors.newFixedThreadPool(THREADS);
        server.setExecutor(serverPool);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverPool.shutdownNow();
    }

    private static String path(int page) {
        return page == 0 ? "/" : "/p" + page;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        StringBuilder html = new StringBuilder("<html><body>");
        for (int k = 0; k < PAGES; k++) {
            if (path(k).equals(path)) {
                for (int target : new int[] {(k + 1) % PAGES, (k + 5) % PAGES, (7 * k) % PAGES}) {
                    if (target != 0) {
                        html.append("<a href=\"").append(path(target)).append("\">x</a>");
                    }
                }
            }
        }
        html.append("</body></html>");
        try {
            // Wolniejszy serwer: bez opóźnienia jeden wątek przeszedłby całą stronę, zanim
            // pozostałe skończą czekać na pracę (200 ms) - test nie sprawdzałby równoległości.
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private CrawlerConfig config(int maxPages) {
        return new CrawlerConfig(base + "/", THREADS, maxPages, 20, true,
                tempDir.resolve("crawl.db").toString(), 2_000, 0, "test-agent");
    }

    /** Osobne połączenie do sprawdzenia bazy - Crawler zamyka swoje po zakończeniu. */
    private DBConn.LinkRow row(int page) {
        try (DBConn db = new DBConn(tempDir.resolve("crawl.db").toString())) {
            db.connect();
            String url = base + path(page);
            return db.findByUrl(url).orElseThrow(() -> new AssertionError("brak w bazie: " + url));
        }
    }

    private void assertEachPageRequestedOnce() {
        assertEquals(PAGES, requests.size(), "żądane ścieżki: " + requests.keySet());
        requests.forEach((path, count) ->
                assertEquals(1, count.get(), "ścieżka " + path + " pobrana więcej niż raz"));
    }

    @Test
    void visitsEveryPageExactlyOnce() {
        Crawler.Stats stats = new Crawler(config(100)).run();

        assertEachPageRequestedOnce();
        assertEquals(PAGES, stats.pagesFetched());
        assertEquals(Map.of("VISITED", PAGES), stats.countByStatus());
        assertFalse(stats.duration().isNegative());

        // claimed_by pochodzi z ThreadFactory: crawler-1 ... crawler-THREADS.
        Set<String> workers = new TreeSet<>();
        for (int k = 0; k < PAGES; k++) {
            String claimedBy = row(k).claimedBy();
            assertTrue(claimedBy.matches("crawler-[1-" + THREADS + "]"), claimedBy);
            workers.add(claimedBy);
        }
        System.out.println("Strony odwiedziły wątki: " + workers);
    }

    @Test
    void sharedPageLimitIsExact() {
        Crawler.Stats stats = new Crawler(config(5)).run();

        int totalRequests = requests.values().stream().mapToInt(AtomicInteger::get).sum();
        assertEquals(5, totalRequests);
        assertEquals(5, stats.pagesFetched());
        assertEquals(5, stats.countByStatus().get("VISITED"));
        assertFalse(stats.countByStatus().containsKey("IN_PROGRESS"), stats.countByStatus().toString());
    }

    @Test
    void secondRunResumesWithoutRefetchingOrInflatingSeedSeen() {
        Crawler.Stats first = new Crawler(config(3)).run();
        Crawler.Stats second = new Crawler(config(100)).run();

        assertEquals(3, first.pagesFetched());
        assertEquals(PAGES - 3, second.pagesFetched(), "drugie uruchomienie pobiera tylko resztę");
        assertEachPageRequestedOnce();
        assertEquals(Map.of("VISITED", PAGES), second.countByStatus());
        assertEquals(1, row(0).seen(), "addSeed nie może zwiększać seen adresu startowego");
        assertEquals(0, row(0).depth());
    }
}
