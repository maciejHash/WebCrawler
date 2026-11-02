package pl.webcrawler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uruchamia całego robota: przygotowuje bazę, startuje {@code threads} obiektów
 * {@link CrawlerThread} w puli o stałym rozmiarze i czeka, aż same zakończą pracę.
 * Wątki nie dostają linków od Crawlera - każdy pobiera je z bazy samodzielnie.
 */
public class Crawler {

    /** Co ile sekund wypisywać postęp podczas czekania na wątki. */
    static final long PROGRESS_INTERVAL_SECONDS = 5;
    /** Ile najczęściej znajdowanych linków pokazać w raporcie. */
    static final int MOST_SEEN_LIMIT = 10;

    /**
     * Wynik jednego uruchomienia - dane do raportu.
     *
     * @param countByStatus liczba linków w bazie w każdym statusie (cała baza, także z poprzednich uruchomień)
     * @param mostSeen      najczęściej znajdowane linki, od największego seen
     * @param pagesByWorker liczba stron pobranych przez każdy wątek (cała baza)
     * @param pagesFetched  liczba żądań HTTP wykonanych w tym uruchomieniu
     * @param duration      czas od przygotowania bazy do zakończenia wszystkich wątków
     * @param stopped       czy pracę zatrzymano przed końcem (np. Ctrl+C)
     */
    public record Stats(Map<String, Integer> countByStatus, List<DBConn.LinkRow> mostSeen,
                        Map<String, Integer> pagesByWorker, int pagesFetched, Duration duration,
                        boolean stopped) {
    }

    private final CrawlerConfig config;
    /** Wspólna dla wszystkich wątków; AtomicBoolean, bo ustawia ją inny wątek (shutdown hook). */
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    public Crawler(CrawlerConfig config) {
        this.config = config;
    }

    public Stats run() {
        long start = System.nanoTime();
        DBConn db = new DBConn(config.dbPath());
        // Jeden licznik dla wszystkich wątków - limit maxPages dotyczy całego robota.
        AtomicInteger pagesFetched = new AtomicInteger();
        try {
            prepareDatabase(db);

            ExecutorService pool = Executors.newFixedThreadPool(config.threads(), namedThreads());
            for (int i = 0; i < config.threads(); i++) {
                // execute, a nie submit: przy submit wyjątek z wątku zostałby po cichu
                // schowany w Future; tu JVM wypisze go razem z nazwą wątku.
                pool.execute(new CrawlerThread(db, config, pagesFetched, stopRequested));
            }
            // shutdown nie przerywa wątków - tylko zamyka pulę na nowe zadania,
            // dzięki czemu awaitTermination zwróci true, gdy wszystkie skończą pracę.
            pool.shutdown();
            waitForThreads(pool, pagesFetched);

            if (stopRequested.get()) {
                log("zatrzymano - niedokończone linki zostają w bazie do wznowienia");
            }
            return new Stats(db.countByStatus(), db.findMostSeen(MOST_SEEN_LIMIT),
                    db.countPagesByWorker(), pagesFetched.get(),
                    Duration.ofNanos(System.nanoTime() - start), stopRequested.get());
        } finally {
            db.disconnect();
        }
    }

    /**
     * Prosi wątki o zakończenie pracy po bieżącej stronie. Nie czeka - {@link #run()}
     * zwróci wynik, gdy wszystkie wątki skończą. Można wywołać z dowolnego wątku.
     */
    public void requestStop() {
        stopRequested.set(true);
    }

    private void prepareDatabase(DBConn db) {
        db.connect();
        if (config.fresh()) {
            db.dropTables();
            log("--fresh: usunięto dane poprzednich uruchomień");
        }
        db.createTables();
        int reset = db.resetInProgress();
        if (reset > 0) {
            log("wznowienie: " + reset + " linków IN_PROGRESS wróciło do NEW");
        }
        if (db.addSeed(config.startUrl())) {
            log("dodano adres startowy " + config.startUrl());
        } else {
            log("adres startowy już jest w bazie - kontynuacja poprzedniego uruchomienia");
        }
    }

    /** Czeka na koniec pracy wątków, co kilka sekund wypisując postęp. */
    private void waitForThreads(ExecutorService pool, AtomicInteger pagesFetched) {
        try {
            while (!pool.awaitTermination(PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
                log("postęp: pobrano " + pagesFetched.get() + "/" + config.maxPages() + " stron");
            }
        } catch (InterruptedException e) {
            // Ktoś przerwał wątek główny - przerywamy też robotników (CrawlerThread reaguje
            // na flagę przerwania) i przywracamy flagę, bo awaitTermination ją wyczyściło.
            log("przerwano - zatrzymywanie wątków");
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Nazwy crawler-1, crawler-2, ... - trafiają do logów i do kolumny claimed_by. */
    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return task -> new Thread(task, "crawler-" + counter.incrementAndGet());
    }

    private static void log(String message) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + message);
    }
}
