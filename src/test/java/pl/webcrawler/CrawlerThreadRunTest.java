package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Cała pętla run() na małej stronie z lokalnego HttpServer z JDK - bez internetu.
 * <pre>
 * /        → /a, /b, /a (duplikat), /doc.pdf, obcy host, mailto:
 * /a       → /, /b, /missing, /data
 * /b       → brak linków
 * /missing → 404
 * /data    → application/json (nie HTML)
 * </pre>
 */
@Timeout(20)
class CrawlerThreadRunTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private final Queue<String> requestedPaths = new ConcurrentLinkedQueue<>();
    private DBConn db;
    private final AtomicInteger pagesFetched = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        db = new DBConn(tempDir.resolve("crawl.db").toString());
        db.connect();
        db.createTables();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        db.disconnect();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        switch (path) {
            case "/" -> respond(exchange, 200, "text/html", """
                    <a href="/a">a</a> <a href="/b">b</a> <a href="/a">a again</a>
                    <a href="/doc.pdf">pdf</a> <a href="https://example.org/x">external</a>
                    <a href="mailto:jan@example.com">mail</a>""");
            case "/a" -> respond(exchange, 200, "text/html", """
                    <a href="/">home</a> <a href="/b">b</a>
                    <a href="/missing">404</a> <a href="/data">json</a>""");
            case "/b" -> respond(exchange, 200, "text/html", "<p>bez linków</p>");
            case "/data" -> respond(exchange, 200, "application/json", "{}");
            default -> respond(exchange, 404, "text/html", "nie ma");
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private CrawlerConfig config(int threads, int maxPages, int maxDepth) {
        return new CrawlerConfig(base + "/", threads, maxPages, maxDepth, true,
                tempDir.resolve("crawl.db").toString(), 2_000, 0, "test-agent");
    }

    /** Uruchamia wątki tak jak zrobi to Crawler i czeka, aż wszystkie same się zakończą. */
    private void crawl(CrawlerConfig config) throws Exception {
        db.insertRow(config.startUrl(), 0);
        ExecutorService pool = Executors.newFixedThreadPool(config.threads());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < config.threads(); i++) {
            futures.add(pool.submit(new CrawlerThread(db, config, pagesFetched)));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "wątki nie zakończyły pracy");
        for (Future<?> future : futures) {
            future.get(); // wyjątek z wątku wyszedłby tutaj
        }
    }

    private DBConn.LinkRow row(String path) {
        String url = path.startsWith("http") ? path : base + path;
        return db.findByUrl(url).orElseThrow(() -> new AssertionError("brak w bazie: " + url));
    }

    @Test
    void crawlsWholeSiteVisitingEachPageOnce() throws Exception {
        crawl(config(3, 100, 3));

        assertEquals("VISITED", row("/").status());
        assertEquals("VISITED", row("/a").status());
        assertEquals("VISITED", row("/b").status());
        assertEquals("ERROR", row("/missing").status());
        assertEquals("HTTP 404", row("/missing").error());
        assertEquals("ERROR", row("/data").status());
        assertTrue(row("/data").error().contains("application/json"), row("/data").error());
        assertEquals("SKIPPED", row("/doc.pdf").status());
        assertEquals("SKIPPED", row("https://example.org/x").status());

        // seen liczy każde wystąpienie: /a dwa razy na "/", /b na "/" i na /a,
        // "/" raz jako start i raz z /a.
        assertEquals(2, row("/a").seen());
        assertEquals(2, row("/b").seen());
        assertEquals(2, row("/").seen());
        assertEquals(2, row("/missing").depth(), "/missing znaleziony na /a, która ma głębokość 1");

        // Każda strona pobrana dokładnie raz, pominięte bez żądania HTTP.
        List<String> sorted = new ArrayList<>(requestedPaths);
        Collections.sort(sorted);
        assertEquals(List.of("/", "/a", "/b", "/data", "/missing"), sorted);
        assertEquals(5, pagesFetched.get());
        assertEquals(Map.of("VISITED", 3, "ERROR", 2, "SKIPPED", 2), db.countByStatus());
    }

    @Test
    void stopsAtSharedPageLimit() throws Exception {
        crawl(config(3, 2, 3));

        assertEquals(2, requestedPaths.size());
        assertEquals(2, pagesFetched.get());
        Map<String, Integer> counts = db.countByStatus();
        assertFalse(counts.containsKey("IN_PROGRESS"), "oddane linki muszą wrócić do NEW: " + counts);
        assertTrue(counts.get("NEW") > 0, "część linków zostaje na później: " + counts);
    }

    @Test
    void doesNotVisitLinksDeeperThanMaxDepth() throws Exception {
        crawl(config(2, 100, 1));

        assertEquals("VISITED", row("/a").status());
        // /missing i /data są na głębokości 2 - zapisane, ale nieodwiedzone.
        assertEquals("NEW", row("/missing").status());
        assertEquals("NEW", row("/data").status());
        assertEquals(3, requestedPaths.size());
    }

    @Test
    void interruptEndsLoopAndKeepsInterruptFlag() throws Exception {
        // Link IN_PROGRESS u "kogoś innego": wątek nie ma co przejąć, ale nie może skończyć,
        // więc czeka w pętli - dopóki go nie przerwiemy.
        db.insertRow(base + "/", 0);
        db.claimNextLink("other-worker", 3);

        AtomicBoolean flagAfterRun = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            new CrawlerThread(db, config(1, 100, 3), pagesFetched).run();
            flagAfterRun.set(Thread.currentThread().isInterrupted());
        }, "crawler-test");
        thread.start();
        Thread.sleep(3 * CrawlerThread.WAIT_FOR_WORK_MS);
        assertTrue(thread.isAlive(), "wątek powinien czekać na pracę");

        thread.interrupt();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertTrue(flagAfterRun.get(), "flaga przerwania musi zostać zachowana");
        assertTrue(requestedPaths.isEmpty());
    }
}
