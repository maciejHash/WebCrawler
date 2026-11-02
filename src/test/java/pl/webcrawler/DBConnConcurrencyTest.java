package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wiele wątków jednocześnie przejmuje linki. Sprawdzamy, że każdy link trafił
 * do dokładnie jednego wątku - zarówno przy wspólnym DBConn, jak i przy osobnych połączeniach.
 */
class DBConnConcurrencyTest {

    private static final int LINKS = 200;
    private static final int THREADS = 8;
    private static final int MAX_DEPTH = 0;

    /** Przejęcie widziane z perspektywy wątku: kto uważa, że link jest jego. */
    private record Claim(String worker, LinkRecord link) {
    }

    @TempDir
    Path tempDir;

    private String dbPath;
    private DBConn db;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("concurrency.db").toString();
        db = new DBConn(dbPath);
        db.connect();
        db.createTables();
        for (int i = 0; i < LINKS; i++) {
            db.insertRow("https://example.com/page/" + i, 0);
        }
    }

    @AfterEach
    void tearDown() {
        db.disconnect();
    }

    @RepeatedTest(10)
    @Timeout(30)
    void sharedConnectionNeverGivesLinkTwice() throws Exception {
        // Wariant (a): wszystkie wątki korzystają z tego samego obiektu (jak w crawlerze).
        Queue<Claim> claims = claimConcurrently(() -> db, false);
        assertExactlyOnce(claims);
    }

    @RepeatedTest(10)
    @Timeout(30)
    void separateConnectionsNeverGiveLinkTwice() throws Exception {
        // Wariant (b): każdy wątek ma własne połączenie, więc synchronized na DBConn
        // nikogo nie blokuje - chroni wyłącznie warunkowy UPDATE w bazie.
        Queue<Claim> claims = claimConcurrently(() -> {
            DBConn own = new DBConn(dbPath);
            own.connect();
            return own;
        }, true);
        assertExactlyOnce(claims);
    }

    /**
     * Uruchamia THREADS wątków, które startują jednocześnie (CountDownLatch)
     * i przejmują linki, dopóki baza ich nie wyczerpie.
     */
    private Queue<Claim> claimConcurrently(Supplier<DBConn> dbForWorker, boolean closeAfter)
            throws Exception {
        Queue<Claim> claims = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String worker = "worker-" + i;
                futures.add(pool.submit(() -> {
                    // Połączenie otwieramy przed startem, żeby wszyscy ruszyli naprawdę razem.
                    DBConn conn = dbForWorker.get();
                    try {
                        ready.countDown();
                        start.await();
                        Optional<LinkRecord> link;
                        while ((link = conn.claimNextLink(worker, MAX_DEPTH)).isPresent()) {
                            claims.add(new Claim(worker, link.get()));
                        }
                    } finally {
                        if (closeAfter) {
                            conn.disconnect();
                        }
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            // get() przenosi do testu wyjątek rzucony w wątku (np. SQLITE_BUSY).
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        return claims;
    }

    private void assertExactlyOnce(Queue<Claim> claims) {
        assertEquals(LINKS, claims.size(), "każdy link powinien zostać przejęty dokładnie raz");

        Set<Long> distinctIds = claims.stream()
                .map(claim -> claim.link().id())
                .collect(Collectors.toSet());
        assertEquals(LINKS, distinctIds.size(), "ten sam link przejęły dwa wątki");

        assertEquals(Map.of("IN_PROGRESS", LINKS), db.countByStatus());

        // Baza i wątki zgadzają się co do właściciela każdego linku.
        for (Claim claim : claims) {
            DBConn.LinkRow row = db.findByUrl(claim.link().url()).orElseThrow();
            assertEquals(claim.worker(), row.claimedBy(), "zły właściciel: " + row.url());
        }
    }
}
