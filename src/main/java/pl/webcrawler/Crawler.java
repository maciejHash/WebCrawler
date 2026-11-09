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
import java.util.concurrent.atomic.AtomicReference;

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
    static final int MOST_LINKED_LIMIT = 10;

    /**
     * Wynik jednego uruchomienia - dane do raportu.
     *
     * @param countByStatus liczba linków w bazie w każdym statusie (cała baza, także z poprzednich uruchomień)
     * @param mostSeen      najczęściej znajdowane linki, od największego seen
     * @param mostLinked    adresy z największą liczbą różnych stron linkujących
     * @param pagesByWorker liczba stron pobranych przez każdy wątek (cała baza)
     * @param pagesFetched  liczba żądań HTTP wykonanych w tym uruchomieniu
     * @param duration      czas od przygotowania bazy do zakończenia wszystkich wątków
     * @param stopped       czy pracę zatrzymano przed końcem (np. Ctrl+C)
     */
    public record Stats(Map<String, Integer> countByStatus, List<DBConn.LinkRow> mostSeen,
                        List<DBConn.IncomingLinks> mostLinked, Map<String, Integer> pagesByWorker,
                        int pagesFetched, Duration duration,
                        boolean stopped) {
    }

    private final CrawlerConfig config;
    private final DBConn db;
    /**
     * Wspólna dla wszystkich wątków; AtomicBoolean, bo ustawiają ją inne wątki
     * (shutdown hook albo CrawlerThread po błędzie bazy).
     */
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    /** @param config ustawienia uruchomienia; ten sam obiekt dostają wszystkie wątki */
    public Crawler(CrawlerConfig config) {
        this(config, new DBConn(config.dbPath()));
    }

    /**
     * Tylko dla testów (pakietowo-prywatny): pozwala podstawić podklasę DBConn,
     * która symuluje błędy. Sam obiekt DBConn nie otwiera jeszcze bazy.
     */
    Crawler(CrawlerConfig config, DBConn db) {
        this.config = config;
        this.db = db;
    }

    /**
     * Uruchamia robota i blokuje do zakończenia pracy wszystkich wątków.
     * Połączenie z bazą jest zamykane także wtedy, gdy wystąpi błąd.
     *
     * @throws DatabaseException gdy nie da się otworzyć lub przygotować bazy albo gdy
     *                           baza przestała działać w trakcie pracy wątków
     */
    public Stats run() {
        long start = System.nanoTime();
        // Jeden licznik dla wszystkich wątków - limit maxPages dotyczy całego robota.
        AtomicInteger pagesFetched = new AtomicInteger();
        // Pierwszy błąd bazy z któregokolwiek wątku. Wątek zapisuje go tutaj i kończy pracę,
        // a my rzucamy go dalej dopiero, gdy wszystkie wątki się zatrzymają.
        AtomicReference<DatabaseException> dbFailure = new AtomicReference<>();
        // Jedna dla wszystkich wątków, żeby robots.txt każdego hosta był pobrany raz. Nowa przy
        // każdym uruchomieniu: wznowienie pracy pobiera aktualną wersję pliku.
        RobotsCache robots = new RobotsCache(config.userAgent(), config.timeoutMs());
        try {
            prepareDatabase(db);

            ExecutorService pool = Executors.newFixedThreadPool(config.threads(), namedThreads());
            for (int i = 0; i < config.threads(); i++) {
                // execute, a nie submit: przy submit wyjątek z wątku zostałby po cichu
                // schowany w Future; tu JVM wypisze go razem z nazwą wątku.
                pool.execute(new CrawlerThread(db, config, robots, pagesFetched, stopRequested, dbFailure));
            }
            // shutdown nie przerywa wątków - tylko zamyka pulę na nowe zadania,
            // dzięki czemu awaitTermination zwróci true, gdy wszystkie skończą pracę.
            pool.shutdown();
            waitForThreads(pool, pagesFetched);

            if (dbFailure.get() != null) {
                // Ten sam wyjątek, który dostał wątek - Main zamienia go na kod wyjścia 1.
                throw dbFailure.get();
            }
            if (stopRequested.get()) {
                log("zatrzymano - niedokończone linki zostają w bazie do wznowienia");
            }
            return new Stats(db.countByStatus(), db.findMostSeen(MOST_SEEN_LIMIT),
                    db.findMostLinked(MOST_LINKED_LIMIT), db.countPagesByWorker(), pagesFetched.get(),
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

    /**
     * Czeka na koniec pracy wątków, co kilka sekund wypisując postęp. Wraca dopiero,
     * gdy wątki skończyły (albo po przerwaniu minął limit czekania), bo zaraz potem
     * run() zamyka bazę.
     */
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
            // Przerwany wątek musi jeszcze oddać link (releaseLink), a finally w run() zaraz
            // zamknie bazę. Czekamy najwyżej timeoutMs - dłużej wątek nie utknie w jednym żądaniu.
            try {
                if (!pool.awaitTermination(config.timeoutMs(), TimeUnit.MILLISECONDS)) {
                    log("wątki nie zakończyły się w " + config.timeoutMs() + " ms - zamykanie bazy mimo to");
                }
            } catch (InterruptedException again) {
                // Drugie przerwanie - nie czekamy dłużej; flagę i tak przywracamy niżej.
            }
            // Flagę przywracamy dopiero teraz: ustawiona wcześniej przerwałaby od razu awaitTermination.
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
