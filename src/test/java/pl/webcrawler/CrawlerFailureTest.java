package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
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
 * Wyjątki w wątkach robota nie mogą go zawiesić. Strona z lokalnego HttpServer:
 * <pre>
 * /     → /a, /bad, /b
 * /a    → /c
 * /bad  → /poison, /c
 * /b, /c, /poison → brak linków
 * </pre>
 * {@link FailingDBConn} rzuca wyjątek przy zapisie linku /poison, czyli w trakcie obsługi
 * strony /bad. Przed poprawką wątek ginął, /bad zostawała IN_PROGRESS, a pozostałe wątki
 * czekały na nią bez końca - stąd @Timeout: zawieszenie kończy test błędem, a nie wisi.
 */
@Timeout(15)
class CrawlerFailureTest {

    private static final Map<String, String> SITE = Map.of(
            "/", "<a href=\"/a\">a</a> <a href=\"/bad\">bad</a> <a href=\"/b\">b</a>",
            "/a", "<a href=\"/c\">c</a>",
            "/bad", "<a href=\"/poison\">poison</a> <a href=\"/c\">c</a>",
            "/b", "<p>bez linków</p>",
            "/c", "<p>bez linków</p>",
            "/poison", "<p>bez linków</p>");

    @TempDir
    Path tempDir;

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;
    private String dbPath;
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    /**
     * DBConn, który przy zapisie wybranego adresu rzuca podany wyjątek, a opcjonalnie
     * nie potrafi też oddać linku. Pozostałe metody działają normalnie.
     */
    private static class FailingDBConn extends DBConn {
        private final String failOnUrl;
        private final RuntimeException failure;
        private final boolean releaseFails;

        FailingDBConn(String dbPath, String failOnUrl, RuntimeException failure, boolean releaseFails) {
            super(dbPath);
            this.failOnUrl = failOnUrl;
            this.failure = failure;
            this.releaseFails = releaseFails;
        }

        // CrawlerThread zapisuje linki strony przez saveVisitedPage, a nie insertRow -
        // nadpisanie insertRow nie wstrzyknęłoby żadnego błędu i testy by nie przeszły.
        @Override
        public synchronized void saveVisitedPage(long id, List<String> links, int linkDepth) {
            if (links.contains(failOnUrl)) {
                throw failure;
            }
            super.saveVisitedPage(id, links, linkDepth);
        }

        @Override
        public synchronized void releaseLink(long id) {
            if (releaseFails) {
                throw new DatabaseException("symulowany błąd przy oddawaniu linku", new SQLException("disk I/O error"));
            }
            super.releaseLink(id);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newFixedThreadPool(4);
        server.setExecutor(serverPool);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        dbPath = tempDir.resolve("crawl.db").toString();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverPool.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        try {
            // Krótkie opóźnienie, żeby kilka wątków naprawdę pracowało naraz.
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String body = SITE.get(path);
        byte[] bytes = ("<html><body>" + (body != null ? body : "nie ma") + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(body != null ? 200 : 404, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private CrawlerConfig config(int threads) {
        return new CrawlerConfig(base + "/", threads, 100, 5, true, dbPath, false, 2_000, 0, "test-agent", true);
    }

    private Crawler crawlerFailingOnPoison(int threads, RuntimeException failure, boolean releaseFails) {
        return new Crawler(config(threads), new FailingDBConn(dbPath, base + "/poison", failure, releaseFails));
    }

    /** Osobne połączenie do sprawdzenia bazy - Crawler zamyka swoje po zakończeniu. */
    private DBConn.LinkRow row(String path) {
        try (DBConn db = new DBConn(dbPath)) {
            db.connect();
            return db.findByUrl(base + path).orElseThrow(() -> new AssertionError("brak w bazie: " + path));
        }
    }

    private Map<String, Integer> countByStatus() {
        try (DBConn db = new DBConn(dbPath)) {
            db.connect();
            return db.countByStatus();
        }
    }

    private static UncheckedIOException pageFailure() {
        // Taki wyjątek potrafi rzucić jsoup przy czytaniu treści strony.
        return new UncheckedIOException("symulowany błąd parsowania", new IOException("zerwane połączenie"));
    }

    @Test
    void unexpectedPageErrorDoesNotHangCrawler() {
        Crawler.Stats stats = crawlerFailingOnPoison(4, pageFailure(), false).run();

        // run() w ogóle wróciło (inaczej @Timeout), a strona z błędem ma status końcowy.
        assertEquals(Map.of("VISITED", 4, "ERROR", 1), stats.countByStatus());
        assertFalse(stats.stopped(), "błąd jednej strony nie zatrzymuje robota");
        assertEquals("ERROR", row("/bad").status());
        assertEquals("UncheckedIOException: symulowany błąd parsowania", row("/bad").error());
    }

    @Test
    void pageErrorDoesNotStopTheThread() {
        // Jeden wątek: /bad jest trzecią stroną w kolejce, /b i /c po niej obsługuje ten sam wątek.
        Crawler.Stats stats = crawlerFailingOnPoison(1, pageFailure(), false).run();

        assertEquals("ERROR", row("/bad").status());
        for (String path : new String[] {"/", "/a", "/b", "/c"}) {
            assertEquals("VISITED", row(path).status(), path);
            assertEquals(1, requests.get(path).get(), "liczba żądań dla " + path);
        }
        assertEquals(5, stats.pagesFetched());
        assertEquals(Map.of("crawler-1", 5), stats.pagesByWorker());
    }

    @Test
    void databaseErrorInThreadPropagatesFromRun() {
        DatabaseException failure =
                new DatabaseException("symulowany błąd zapisu", new SQLException("disk I/O error"));
        Crawler crawler = crawlerFailingOnPoison(4, failure, false);

        DatabaseException thrown = assertThrows(DatabaseException.class, crawler::run);

        // Ten sam obiekt, który rzucił DBConn w wątku - Main zamieni go na kod wyjścia 1.
        assertSame(failure, thrown);
        // Link, przy którym wystąpił błąd, wrócił do NEW; nic nie zostało IN_PROGRESS.
        assertEquals("NEW", row("/bad").status());
        assertFalse(countByStatus().containsKey("IN_PROGRESS"), countByStatus().toString());
    }

    @Test
    void failedReleaseIsIgnoredAndNextRunResumes() {
        DatabaseException failure =
                new DatabaseException("symulowany błąd zapisu", new SQLException("disk I/O error"));

        DatabaseException thrown = assertThrows(DatabaseException.class,
                crawlerFailingOnPoison(4, failure, true)::run);

        // Zgłoszony jest pierwotny błąd, a nie ten z nieudanego releaseLink.
        assertSame(failure, thrown);
        // Linku nie dało się oddać - zostaje IN_PROGRESS do następnego uruchomienia.
        assertEquals("IN_PROGRESS", row("/bad").status());

        // Następne uruchomienie (już ze sprawną bazą) przywraca go do NEW i kończy pracę.
        Crawler.Stats resumed = new Crawler(config(4)).run();

        assertEquals("VISITED", row("/bad").status());
        assertEquals("VISITED", row("/poison").status());
        assertFalse(resumed.countByStatus().containsKey("IN_PROGRESS"), resumed.countByStatus().toString());
        assertFalse(resumed.countByStatus().containsKey("NEW"), resumed.countByStatus().toString());
    }
}
