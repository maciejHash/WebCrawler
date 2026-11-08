package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
 * /flaky   → 503 przy pierwszych {@link #FLAKY_FAILURES} żądaniach, potem strona bez linków
 * /down    → zawsze 503
 * /robots.txt → {@link #robotsTxt} albo 404, gdy null
 * </pre>
 * Testy ponawiania podają własny zegar, więc nie czekają sekundami na termin ponowienia.
 */
@Timeout(20)
class CrawlerThreadRunTest {

    private static final int FLAKY_FAILURES = 2;
    /** Dowolny punkt startowy sztucznego zegara (ms od epoki). */
    private static final long START_MS = 1_700_000_000_000L;

    private static final String AGENT = "test-agent";
    private static final int TIMEOUT_MS = 2_000;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    /** Ścieżki pobranych stron - bez /robots.txt, który ma osobny licznik. */
    private final Queue<String> requestedPaths = new ConcurrentLinkedQueue<>();
    private final AtomicInteger robotsRequests = new AtomicInteger();
    private volatile String robotsTxt;
    /** Dopóki ustawione, serwer nie odpowiada na /robots.txt - do testu przerwania. */
    private volatile boolean robotsHangs;
    private final CountDownLatch robotsRequested = new CountDownLatch(1);
    private final CountDownLatch releaseRobots = new CountDownLatch(1);
    private DBConn db;
    // Te same wspólne obiekty, które Crawler daje wszystkim wątkom jednego uruchomienia.
    private final RobotsCache robots = new RobotsCache(AGENT, TIMEOUT_MS);
    private final AtomicInteger pagesFetched = new AtomicInteger();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicReference<DatabaseException> dbFailure = new AtomicReference<>();
    private final AtomicInteger flakyRequests = new AtomicInteger();
    /**
     * Zegar, który przy każdym odczycie przesuwa się o najdłuższą możliwą przerwę przed
     * ponowieniem - każdy link czekający na ponowienie jest więc gotowy przy następnym przejęciu.
     */
    private final AtomicLong fastClockNow = new AtomicLong(START_MS);
    private final LongSupplier fastClock = () -> fastClockNow.addAndGet(CrawlerThread.MAX_RETRY_DELAY_MS);

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
        // Najpierw zwalniamy "wiszącą" odpowiedź - stop() czeka na wątek, który ją obsługuje.
        releaseRobots.countDown();
        server.stop(0);
        db.disconnect();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/robots.txt")) {
            handleRobots(exchange);
            return;
        }
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
            case "/flaky" -> {
                if (flakyRequests.incrementAndGet() <= FLAKY_FAILURES) {
                    respond(exchange, 503, "text/html", "chwilowa awaria");
                } else {
                    respond(exchange, 200, "text/html", "<p>w końcu działa</p>");
                }
            }
            case "/down" -> respond(exchange, 503, "text/html", "awaria");
            default -> respond(exchange, 404, "text/html", "nie ma");
        }
    }

    private void handleRobots(HttpExchange exchange) throws IOException {
        robotsRequests.incrementAndGet();
        robotsRequested.countDown();
        if (robotsHangs) {
            try {
                releaseRobots.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String rules = robotsTxt;
        respond(exchange, rules != null ? 200 : 404, "text/plain", rules != null ? rules : "");
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
        return config("/", threads, maxPages, maxDepth, CrawlerConfig.DEFAULT_MAX_RETRIES);
    }

    private CrawlerConfig config(String startPath, int threads, int maxPages, int maxDepth, int maxRetries) {
        return new CrawlerConfig(base + startPath, threads, maxPages, maxDepth, true,
                tempDir.resolve("crawl.db").toString(), false, TIMEOUT_MS, 0, AGENT, true, maxRetries);
    }

    /** Uruchamia wątki tak jak zrobi to Crawler i czeka, aż wszystkie same się zakończą. */
    private void crawl(CrawlerConfig config) throws Exception {
        crawl(config, System::currentTimeMillis);
    }

    /** Jak {@link #crawl(CrawlerConfig)}, ale wątki odczytują czas z podanego zegara. */
    private void crawl(CrawlerConfig config, LongSupplier clock) throws Exception {
        db.insertRow(config.startUrl(), 0);
        ExecutorService pool = Executors.newFixedThreadPool(config.threads());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < config.threads(); i++) {
            futures.add(pool.submit(
                    new CrawlerThread(db, config, robots, pagesFetched, stopRequested, dbFailure, clock)));
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

    /** Ile żądań HTTP dostał serwer dla podanej ścieżki - "pobrano N razy" sprawdzamy po stronie serwera. */
    private long requestCount(String path) {
        return requestedPaths.stream().filter(path::equals).count();
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
        // robots.txt: jeden raz dla 3 wątków, poza limitem stron i poza tabelą links.
        assertEquals(1, robotsRequests.get());
        assertTrue(db.findByUrl(base + "/robots.txt").isEmpty());
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
    void stopFlagEndsLoopBeforeTakingAnyLink() {
        db.insertRow(base + "/", 0);
        stopRequested.set(true);

        new CrawlerThread(db, config(1, 100, 3), robots, pagesFetched, stopRequested, dbFailure).run();

        assertTrue(requestedPaths.isEmpty(), "po zatrzymaniu nie wolno pobierać stron");
        // Link nie został przejęty - czeka w bazie na następne uruchomienie.
        assertEquals("NEW", row("/").status());
        assertEquals(0, pagesFetched.get());
    }

    @Test
    void stopFlagLetsWaitingThreadFinish() throws Exception {
        // Jak w teście przerwania: wątek czeka na pracę, bo link jest IN_PROGRESS gdzie indziej.
        db.insertRow(base + "/", 0);
        db.claimNextLink("other-worker", 3, System.currentTimeMillis());
        Thread thread = new Thread(
                new CrawlerThread(db, config(1, 100, 3), robots, pagesFetched, stopRequested, dbFailure),
                "crawler-test");
        thread.start();
        Thread.sleep(3 * CrawlerThread.WAIT_FOR_WORK_MS);
        assertTrue(thread.isAlive(), "wątek powinien czekać na pracę");

        stopRequested.set(true);
        thread.join(5_000);

        assertFalse(thread.isAlive(), "flaga stop musi zakończyć oczekiwanie bez przerywania wątku");
    }

    @Test
    void interruptEndsLoopAndKeepsInterruptFlag() throws Exception {
        // Link IN_PROGRESS u "kogoś innego": wątek nie ma co przejąć, ale nie może skończyć,
        // więc czeka w pętli - dopóki go nie przerwiemy.
        db.insertRow(base + "/", 0);
        db.claimNextLink("other-worker", 3, System.currentTimeMillis());

        AtomicBoolean flagAfterRun = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            new CrawlerThread(db, config(1, 100, 3), robots, pagesFetched, stopRequested, dbFailure).run();
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

    @Test
    void interruptWhileFetchingRobotsTxtReturnsLinkToNew() throws Exception {
        robotsHangs = true;
        db.insertRow(base + "/", 0);
        AtomicBoolean flagAfterRun = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            new CrawlerThread(db, config(1, 100, 3), robots, pagesFetched, stopRequested, dbFailure).run();
            flagAfterRun.set(Thread.currentThread().isInterrupted());
        }, "crawler-test");
        thread.start();
        assertTrue(robotsRequested.await(5, TimeUnit.SECONDS), "wątek nie zaczął pobierać robots.txt");

        thread.interrupt();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertTrue(flagAfterRun.get(), "flaga przerwania musi zostać zachowana");
        // Przerwanie nie jest decyzją robots.txt: link wraca do kolejki, a nie dostaje SKIPPED.
        assertEquals("NEW", row("/").status());
        assertNull(row("/").claimedBy());
        assertTrue(requestedPaths.isEmpty());
        assertEquals(0, pagesFetched.get());
    }

    @Test
    void crawlDelayFromRobotsTxtIsUsedAsPauseAfterEachRequest() throws Exception {
        // Krótki Crawl-delay, żeby test nie czekał sekundami; --delay w config(...) to 0.
        robotsTxt = "User-agent: *\nCrawl-delay: 0.15\n";
        long start = System.nanoTime();

        // Jeden wątek i limit 2 stron: dwa żądania, po każdym przerwa 150 ms.
        crawl(config(1, 2, 3));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(2, requestedPaths.size());
        assertTrue(elapsedMs >= 2 * 150, "przerwy z Crawl-delay nie było, czas: " + elapsedMs + " ms");
    }

    // ---- ponawianie błędów przejściowych ----

    @Test
    void transientErrorIsRetriedUntilPageLoads() throws Exception {
        crawl(config("/flaky", 2, 100, 0, 2), fastClock);

        assertEquals(3, requestCount("/flaky"), "503, 503, a za trzecim razem 200");
        DBConn.LinkRow row = row("/flaky");
        assertEquals("VISITED", row.status());
        assertEquals(2, row.attempts());
        assertNull(row.error(), "udana próba czyści opis ostatniego błędu");
        assertEquals(3, pagesFetched.get(), "każda próba to osobne żądanie HTTP");
    }

    @Test
    void nonTransientErrorIsNotRetried() throws Exception {
        crawl(config("/missing", 2, 100, 0, 2), fastClock);

        assertEquals(1, requestCount("/missing"));
        DBConn.LinkRow row = row("/missing");
        assertEquals("ERROR", row.status());
        assertEquals("HTTP 404", row.error(), "po jednej próbie bez dopisku o liczbie prób");
        assertEquals(0, row.attempts());
        assertNull(row.retryAt());
    }

    @Test
    void zeroRetriesMarksTransientErrorAtOnce() throws Exception {
        crawl(config("/down", 2, 100, 0, 0), fastClock);

        assertEquals(1, requestCount("/down"));
        DBConn.LinkRow row = row("/down");
        assertEquals("ERROR", row.status());
        assertEquals("HTTP 503", row.error());
        assertEquals(0, row.attempts());
    }

    @Test
    void persistentTransientErrorEndsAsErrorAfterAllAttempts() throws Exception {
        crawl(config("/down", 2, 100, 0, 2), fastClock);

        assertEquals(3, requestCount("/down"), "pierwsza próba + 2 ponowienia");
        DBConn.LinkRow row = row("/down");
        assertEquals("ERROR", row.status());
        assertEquals("HTTP 503 (po 3 próbach)", row.error());
        assertEquals(2, row.attempts(), "ponowienia zaplanowane przed ostatnią próbą");
        assertEquals(3, pagesFetched.get());
    }

    @Test
    void firstTransientFailureSchedulesRetryAfterBaseDelay() throws Exception {
        // Zegar stoi w miejscu, a limit 1 strony kończy wątek zaraz po pierwszej próbie.
        crawl(config("/down", 1, 1, 0, 2), () -> START_MS);

        assertEquals(1, requestCount("/down"));
        DBConn.LinkRow row = row("/down");
        assertEquals("NEW", row.status(), "link czeka na ponowienie, nie ma statusu końcowego");
        assertNull(row.claimedBy());
        assertEquals(1, row.attempts());
        assertEquals("HTTP 503", row.error());
        assertEquals(START_MS + CrawlerThread.RETRY_BASE_DELAY_MS, row.retryAt());
    }

    @Test
    void retriesCountTowardsSharedPageLimit() throws Exception {
        crawl(config("/down", 2, 2, 0, 5), fastClock);

        assertEquals(2, requestCount("/down"));
        assertEquals(2, pagesFetched.get());
        // Limit wyczerpany w trakcie ponawiania - link czeka w bazie na następne uruchomienie.
        DBConn.LinkRow row = row("/down");
        assertEquals("NEW", row.status());
        assertEquals(2, row.attempts());
    }

    @Test
    void threadWaitsForRetryTimeInsteadOfFinishing() throws Exception {
        AtomicLong now = new AtomicLong(START_MS);
        db.insertRow(base + "/b", 0);
        LinkRecord link = db.claimNextLink("other-worker", 0, now.get()).orElseThrow();
        db.scheduleRetry(link.id(), "HTTP 503", now.get() + 1_000);
        Thread thread = new Thread(new CrawlerThread(db, config("/b", 1, 100, 0, 2), robots,
                pagesFetched, stopRequested, dbFailure, now::get), "crawler-test");
        thread.start();
        Thread.sleep(3 * CrawlerThread.WAIT_FOR_WORK_MS);
        assertTrue(thread.isAlive(), "przed terminem ponowienia wątek ma czekać, a nie kończyć pracę");
        assertTrue(requestedPaths.isEmpty(), "przed terminem ponowienia nie wolno pobierać strony");

        now.addAndGet(1_000);
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertEquals(List.of("/b"), List.copyOf(requestedPaths));
        DBConn.LinkRow row = row("/b");
        assertEquals("VISITED", row.status());
        assertEquals(1, row.attempts());
        assertEquals("crawler-test", row.claimedBy());
    }
}
