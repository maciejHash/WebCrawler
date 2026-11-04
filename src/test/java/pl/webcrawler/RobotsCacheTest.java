package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Pobieranie robots.txt z lokalnego HttpServer: reakcja na kody odpowiedzi, timeout i błąd sieci
 * oraz to, że każdy host jest pobierany najwyżej raz - licznik żądań po stronie serwera.
 */
@Timeout(20)
class RobotsCacheTest {

    private static final String AGENT = "test-agent/1.0";
    private static final int TIMEOUT_MS = 2_000;

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;

    private final AtomicInteger robotsRequests = new AtomicInteger();
    private final Queue<String> receivedUserAgents = new ConcurrentLinkedQueue<>();
    /** Odliczany przy każdym żądaniu - test wie, że żądanie dotarło do serwera. */
    private final CountDownLatch requestArrived = new CountDownLatch(1);
    /** Dopóki ustawione, serwer nie odpowiada - do testów timeoutu i przerwania. */
    private volatile boolean hang;
    /** Zwalnia "wiszące" odpowiedzi w tearDown, żeby nie blokowały zamykania serwera. */
    private final CountDownLatch releaseHanging = new CountDownLatch(1);
    private volatile long responseDelayMs;
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void setUp() throws IOException {
        server = startServer();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        releaseHanging.countDown();
        server.stop(0);
        serverPool.shutdownNow();
    }

    private HttpServer startServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (serverPool == null) {
            // Kilka wątków: żądanie "wiszące" nie może blokować następnych.
            serverPool = Executors.newFixedThreadPool(8);
        }
        httpServer.setExecutor(serverPool);
        httpServer.createContext("/", this::handle);
        httpServer.start();
        return httpServer;
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().equals("/robots.txt")) {
            robotsRequests.incrementAndGet();
        }
        receivedUserAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
        requestArrived.countDown();
        try {
            if (hang) {
                releaseHanging.await(15, TimeUnit.SECONDS);
            }
            Thread.sleep(responseDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void successfulResponseIsParsedAndFetchedWithOurUserAgent() throws Exception {
        body = """
                User-agent: test-agent
                Disallow: /private
                Crawl-delay: 2
                """;

        RobotsTxt robots = new RobotsCache(AGENT, TIMEOUT_MS).rulesFor(base + "/page");

        assertFalse(robots.isAllowed("/private/x"));
        assertTrue(robots.isAllowed("/public"));
        assertEquals(OptionalLong.of(2_000), robots.crawlDelayMs());
        assertEquals(1, robotsRequests.get());
        assertEquals(List.of(AGENT), List.copyOf(receivedUserAgents));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404, 410})
    void clientErrorMeansNoRestrictions(int code) throws Exception {
        status = code;
        // Treść strony błędu nie jest czytana jako robots.txt.
        body = "User-agent: *\nDisallow: /\n";

        RobotsTxt robots = new RobotsCache(AGENT, TIMEOUT_MS).rulesFor(base + "/");

        assertTrue(robots.isAllowed("/"));
        assertTrue(robots.isAllowed("/anything?q=1"));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {500, 502, 503})
    void serverErrorBlocksWholeHost(int code) throws Exception {
        status = code;

        RobotsTxt robots = new RobotsCache(AGENT, TIMEOUT_MS).rulesFor(base + "/");

        assertFalse(robots.isAllowed("/"));
        assertFalse(robots.isAllowed("/anything?q=1"));
    }

    @Test
    void serverErrorIsRememberedUntilEndOfRun() throws Exception {
        status = 503;
        RobotsCache cache = new RobotsCache(AGENT, TIMEOUT_MS);
        cache.rulesFor(base + "/");

        // Serwer już działa poprawnie, ale w tym uruchomieniu pliku nie pobieramy ponownie.
        status = 200;
        RobotsTxt later = cache.rulesFor(base + "/other");

        assertFalse(later.isAllowed("/other"));
        assertEquals(1, robotsRequests.get());
    }

    @Test
    void timeoutBlocksWholeHost() throws Exception {
        hang = true;

        // Krótki limit czasu z testu - serwer nie odpowie wcale, więc nie czekamy sekundami.
        RobotsTxt robots = new RobotsCache(AGENT, 200).rulesFor(base + "/");

        assertFalse(robots.isAllowed("/"));
        assertEquals(1, robotsRequests.get());
    }

    @Test
    void networkErrorBlocksWholeHost() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        // Na tym porcie nikt już nie słucha - połączenie zostanie odrzucone.
        RobotsTxt robots = new RobotsCache(AGENT, TIMEOUT_MS).rulesFor("http://127.0.0.1:" + closedPort + "/");

        assertFalse(robots.isAllowed("/"));
    }

    @Test
    void fetchedOnceEvenWhenManyThreadsAskAtOnce() throws Exception {
        // Wolna odpowiedź: pozostałe wątki pytają, zanim pierwszy skończy pobieranie.
        responseDelayMs = 100;
        body = "User-agent: *\nDisallow: /private\n";
        RobotsCache cache = new RobotsCache(AGENT, TIMEOUT_MS);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RobotsTxt>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String url = base + "/page" + i;
            results.add(pool.submit(() -> {
                start.await();
                return cache.rulesFor(url);
            }));
        }

        start.countDown();
        RobotsTxt first = results.get(0).get(10, TimeUnit.SECONDS);
        for (Future<RobotsTxt> result : results) {
            assertSame(first, result.get(10, TimeUnit.SECONDS), "wszystkie wątki dostają ten sam obiekt");
        }
        pool.shutdown();

        assertEquals(1, robotsRequests.get(), "robots.txt pobrany więcej niż raz");
        assertFalse(first.isAllowed("/private"));
    }

    @Test
    void eachHostAndPortHasOwnRobotsTxt() throws Exception {
        HttpServer second = startServer();
        try {
            String otherBase = "http://127.0.0.1:" + second.getAddress().getPort();
            RobotsCache cache = new RobotsCache(AGENT, TIMEOUT_MS);

            cache.rulesFor(base + "/a");
            cache.rulesFor(otherBase + "/b");
            cache.rulesFor(base + "/c?q=1");
            cache.rulesFor(otherBase + "/");

            assertEquals(2, robotsRequests.get(), "jeden robots.txt na każdy port");
        } finally {
            second.stop(0);
        }
    }

    @Test
    void interruptedFetchThrowsAndIsNotRemembered() throws Exception {
        hang = true;
        RobotsCache cache = new RobotsCache(AGENT, TIMEOUT_MS);
        AtomicBoolean gotInterrupted = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                cache.rulesFor(base + "/");
            } catch (InterruptedException e) {
                gotInterrupted.set(true);
            }
        });
        thread.start();
        assertTrue(requestArrived.await(5, TimeUnit.SECONDS), "żądanie nie dotarło do serwera");

        thread.interrupt();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertTrue(gotInterrupted.get(), "przerwanie musi wyjść jako InterruptedException");

        // Przerwanie nie mówi nic o hoście, więc następne pytanie pobiera plik od nowa.
        hang = false;
        status = 404;
        assertTrue(cache.rulesFor(base + "/").isAllowed("/"));
        assertEquals(2, robotsRequests.get());
    }
}
