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
import java.util.LinkedHashMap;
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
 * Test end-to-end: {@link Crawler} z 4 wątkami na małej witrynie z lokalnego HttpServer.
 * <pre>
 * /            → /about, /blog, /blog, /about#team, /contact
 * /about       → /, /team, #top (ta sama strona), http://localhost:PORT/external (inny host)
 * /team        → /about, /, /missing (404)
 * /blog        → /blog/post1 ×3 (raz z #comments), /blog/post2, /files/report.pdf, /
 * /blog/post1  → /blog, post2 (względny), ../contact (względny)
 * /blog/post2  → /blog/post1, /, /team
 * /contact     → /, /files/report.pdf, mailto: (odrzucany przez UrlUtils)
 * </pre>
 * Cykle: / ↔ /about, /blog ↔ /blog/post1 ↔ /blog/post2 → / itd.
 * Link do innego hosta wskazuje ten sam serwer (localhost zamiast 127.0.0.1), więc gdyby
 * robot go pobrał, licznik żądań by to pokazał. To samo dotyczy pliku .pdf.
 */
@Timeout(30)
class CrawlerEndToEndTest {

    private static final int THREADS = 4;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;
    private String externalUrl;
    /** Ścieżka → treść HTML; ścieżki spoza mapy dostają 404. */
    private Map<String, String> site;
    /** Ścieżka → liczba żądań, także dla 404 i ścieżek, których robot nie powinien pobrać. */
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        externalUrl = "http://localhost:" + port + "/external";
        site = buildSite();

        serverPool = Executors.newFixedThreadPool(THREADS);
        server.setExecutor(serverPool);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverPool.shutdownNow();
    }

    private Map<String, String> buildSite() {
        return Map.of(
                "/", """
                        <a href="/about">O nas</a> <a href="/blog">Blog</a>
                        <a href="/blog">Blog jeszcze raz</a> <a href="/about#team">Zespół</a>
                        <a href="/contact">Kontakt</a>""",
                "/about", """
                        <a href="/">Start</a> <a href="/team">Zespół</a>
                        <a href="#top">Do góry</a> <a href="%s">Obcy host</a>""".formatted(externalUrl),
                "/team", """
                        <a href="/about">O nas</a> <a href="/">Start</a>
                        <a href="/missing">Zepsuty link</a>""",
                "/blog", """
                        <a href="/blog/post1">Wpis 1</a> <a href="/blog/post2">Wpis 2</a>
                        <a href="/blog/post1">Wpis 1 (czytaj dalej)</a>
                        <a href="/blog/post1#comments">Komentarze</a>
                        <a href="/files/report.pdf">Raport</a> <a href="/">Start</a>""",
                "/blog/post1", """
                        <a href="/blog">Blog</a> <a href="post2">Następny</a>
                        <a href="../contact">Kontakt</a>""",
                "/blog/post2", """
                        <a href="/blog/post1">Poprzedni</a> <a href="/">Start</a>
                        <a href="/team">Zespół</a>""",
                "/contact", """
                        <a href="/">Start</a> <a href="/files/report.pdf">Raport</a>
                        <a href="mailto:kontakt@example.com">Napisz</a>""");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        try {
            // Krótkie opóźnienie, żeby wątki faktycznie pracowały równolegle i ścigały się o linki.
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String body = site.get(path);
        int status = body != null ? 200 : 404;
        byte[] bytes = ("<html><body>" + (body != null ? body : "Nie ma takiej strony")
                + "</body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Oczekiwane seen dla każdego adresu = liczba wystąpień linku na wszystkich stronach
     * (warianty z #fragmentem i adresy względne liczą się do tego samego adresu).
     * Adres startowy ma dodatkowo 1 za samo dodanie do bazy.
     */
    private Map<String, Integer> expectedSeen() {
        Map<String, Integer> seen = new LinkedHashMap<>();
        seen.put(base + "/", 1 + 5);             // start + /about, /team, /blog, /blog/post2, /contact
        seen.put(base + "/about", 4);            // / ×2 (w tym #team), /about (#top), /team
        seen.put(base + "/team", 2);             // /about, /blog/post2
        seen.put(base + "/blog", 3);             // / ×2, /blog/post1
        seen.put(base + "/blog/post1", 4);       // /blog ×3, /blog/post2
        seen.put(base + "/blog/post2", 2);       // /blog, /blog/post1 (względny)
        seen.put(base + "/contact", 2);          // /, /blog/post1 (względny ../contact)
        seen.put(base + "/missing", 1);          // /team
        seen.put(base + "/files/report.pdf", 2); // /blog, /contact
        seen.put(externalUrl, 1);                // /about
        return seen;
    }

    @Test
    void crawlsWholeSiteOnceAndRecordsEveryLink() {
        String dbPath = tempDir.resolve("crawl.db").toString();
        CrawlerConfig config = new CrawlerConfig(base + "/", THREADS, 100, 10, true,
                dbPath, 2_000, 0, "test-agent");

        Crawler.Stats stats = new Crawler(config).run();

        // Każda istniejąca strona i strona 404 pobrane dokładnie raz; nic więcej.
        for (String path : site.keySet()) {
            assertEquals(1, requestCount(path), "liczba żądań dla " + path);
        }
        assertEquals(1, requestCount("/missing"), "liczba żądań dla /missing");
        assertEquals(site.size() + 1, requests.size(), "żądane ścieżki: " + requests.keySet());
        // Pominięte linki nie wygenerowały żadnego żądania.
        assertEquals(0, requestCount("/files/report.pdf"));
        assertEquals(0, requestCount("/external"));
        assertEquals(site.size() + 1, stats.pagesFetched());

        // Wszystkie linki są w bazie (żadnych wierszy z #fragmentem ani mailto:),
        // każdy w stanie końcowym - brak IN_PROGRESS i NEW.
        Map<String, Integer> expectedSeen = expectedSeen();
        assertEquals(Map.of("VISITED", site.size(), "ERROR", 1, "SKIPPED", 2), stats.countByStatus());
        assertFalse(stats.countByStatus().containsKey("IN_PROGRESS"));

        try (DBConn db = new DBConn(dbPath)) {
            db.connect();
            expectedSeen.forEach((url, seen) -> {
                DBConn.LinkRow row = db.findByUrl(url)
                        .orElseThrow(() -> new AssertionError("brak w bazie: " + url));
                assertEquals(seen, row.seen(), "seen dla " + url);
            });

            for (String path : site.keySet()) {
                DBConn.LinkRow row = db.findByUrl(base + path).orElseThrow();
                assertEquals("VISITED", row.status(), path);
                assertNull(row.error(), path);
            }
            DBConn.LinkRow missing = db.findByUrl(base + "/missing").orElseThrow();
            assertEquals("ERROR", missing.status());
            assertEquals("HTTP 404", missing.error());
            assertEquals("SKIPPED", db.findByUrl(base + "/files/report.pdf").orElseThrow().status());
            assertEquals("SKIPPED", db.findByUrl(externalUrl).orElseThrow().status());
            assertTrue(db.findByUrl(base + "/about#team").isEmpty(), "fragment musi zostać usunięty");
        }
    }

    private int requestCount(String path) {
        AtomicInteger count = requests.get(path);
        return count == null ? 0 : count.get();
    }
}
