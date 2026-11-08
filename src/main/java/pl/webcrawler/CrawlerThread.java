package pl.webcrawler;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Jeden pracownik robota. Sam pobiera z bazy nieodwiedzony link, ściąga stronę,
 * zapisuje znalezione na niej linki i oznacza stronę jako odwiedzoną.
 * O tym, który wątek dostaje który link, decyduje wyłącznie baza (warunkowy UPDATE
 * w {@link DBConn#claimNextLink}), więc wątki nie muszą się ze sobą komunikować.
 */
public class CrawlerThread implements Runnable {

    /** Ile czekać, gdy nie ma linku NEW, ale inny wątek może jeszcze jakieś dodać. */
    static final long WAIT_FOR_WORK_MS = 200;

    /** Wartość kolumny error dla linków pominiętych, bo zabrania ich robots.txt. */
    static final String ROBOTS_SKIP_REASON = "robots.txt";

    /** Przerwa przed pierwszym ponowieniem; każda kolejna jest dwa razy dłuższa. */
    static final long RETRY_BASE_DELAY_MS = 1_000;
    /** Najdłuższa przerwa przed ponowieniem. */
    static final long MAX_RETRY_DELAY_MS = 30_000;

    private final DBConn db;
    private final CrawlerConfig config;
    private final RobotsCache robots;
    private final AtomicInteger pagesFetched;
    private final AtomicBoolean stopRequested;
    private final AtomicReference<DatabaseException> dbFailure;
    /** Bieżący czas w ms od epoki: od niego liczymy retry_at i porównujemy go z retry_at w bazie. */
    private final LongSupplier clock;

    /**
     * Wszystkie wątki dostają te same obiekty db, config, robots, pagesFetched, stopRequested i dbFailure.
     *
     * @param robots        wspólne dla wątków, żeby robots.txt każdego hosta był pobierany raz
     * @param pagesFetched  licznik wspólny dla wszystkich wątków; dzięki niemu limit
     *                      maxPages dotyczy całego robota, a nie pojedynczego wątku
     * @param stopRequested flaga wspólna dla wszystkich wątków; ustawiona (np. po Ctrl+C)
     *                      oznacza: dokończ bieżącą stronę i nie bierz kolejnej
     * @param dbFailure     tu wątek zapisuje błąd bazy; run() z Runnable nie może rzucić
     *                      wyjątku do Crawlera, więc przekazujemy go przez wspólny obiekt
     */
    public CrawlerThread(DBConn db, CrawlerConfig config, RobotsCache robots, AtomicInteger pagesFetched,
                         AtomicBoolean stopRequested, AtomicReference<DatabaseException> dbFailure) {
        this(db, config, robots, pagesFetched, stopRequested, dbFailure, System::currentTimeMillis);
    }

    /**
     * Tylko dla testów (pakietowo-prywatny): clock zastępuje zegar systemowy. Test przesuwa
     * czas, zamiast czekać sekundami, aż minie przerwa przed ponowieniem.
     */
    CrawlerThread(DBConn db, CrawlerConfig config, RobotsCache robots, AtomicInteger pagesFetched,
                  AtomicBoolean stopRequested, AtomicReference<DatabaseException> dbFailure, LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.robots = robots;
        this.pagesFetched = pagesFetched;
        this.stopRequested = stopRequested;
        this.dbFailure = dbFailure;
        this.clock = clock;
    }

    @Override
    public void run() {
        String workerName = Thread.currentThread().getName();
        log("start");
        // Link przejęty i jeszcze nieobsłużony - przy błędzie bazy trzeba go oddać,
        // inaczej zostałby IN_PROGRESS, a pozostałe wątki czekałyby na niego bez końca.
        LinkRecord current = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Łagodne zatrzymanie: flagę sprawdzamy tylko tutaj, między stronami, więc
                // żadna strona nie zostaje przerwana w połowie. Linki NEW czekają w bazie
                // na następne uruchomienie.
                if (stopRequested.get()) {
                    log("zatrzymano na żądanie");
                    break;
                }
                // Limit już wyczerpany - nie ma sensu przejmować linku tylko po to, by go oddać.
                if (pagesFetched.get() >= config.maxPages()) {
                    log("osiągnięto limit " + config.maxPages() + " stron");
                    break;
                }
                Optional<LinkRecord> claimed = db.claimNextLink(workerName, config.maxDepth(), clock.getAsLong());
                if (claimed.isEmpty()) {
                    // Brak linku do przejęcia nie oznacza końca: strona IN_PROGRESS w innym wątku
                    // może za chwilę dodać nowe linki, a link NEW może czekać na termin ponowienia.
                    // Kończymy dopiero, gdy nie ma ani jednych, ani drugich.
                    if (!db.hasPendingWork(config.maxDepth())) {
                        log("brak linków do odwiedzenia");
                        break;
                    }
                    Thread.sleep(WAIT_FOR_WORK_MS);
                    continue;
                }
                current = claimed.get();
                boolean keepWorking = process(current);
                // Po process() link ma status końcowy albo został oddany - nie jest już nasz.
                current = null;
                if (!keepWorking) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            // sleep() czyści flagę przerwania - przywracamy ją, żeby wywołujący
            // (np. ExecutorService przy shutdownNow) wiedział, że wątek przerwano.
            Thread.currentThread().interrupt();
            log("przerwano");
        } catch (DatabaseException e) {
            stopAfterDatabaseError(e, current);
        }
        log("koniec");
    }

    /**
     * Błąd bazy to nie błąd jednej strony: każdy kolejny zapis też może się nie udać,
     * więc nie ma sensu pracować dalej. Oddajemy link, zapisujemy błąd dla Crawlera
     * i prosimy pozostałe wątki o łagodne zatrzymanie.
     */
    private void stopAfterDatabaseError(DatabaseException e, LinkRecord current) {
        // Zapisujemy tylko pierwszy błąd: inne wątki mogą zaraz trafić na ten sam problem.
        dbFailure.compareAndSet(null, e);
        // Flaga przed oddaniem linku: inaczej inny wątek mógłby go od razu przejąć
        // i pobrać stronę jeszcze raz tylko po to, żeby trafić na ten sam błąd.
        stopRequested.set(true);
        if (current != null) {
            try {
                db.releaseLink(current.id());
            } catch (RuntimeException releaseError) {
                // Baza nadal nie działa - ważniejszy jest pierwotny błąd. Link zostaje
                // IN_PROGRESS, a przy następnym starcie resetInProgress przywróci go do NEW.
                logError("nie udało się oddać " + current.url() + ": " + releaseError.getMessage());
            }
        }
        logError("błąd bazy danych, zatrzymywanie robota: " + e.getMessage());
    }

    /**
     * Obsługuje jeden przejęty link.
     *
     * @return false, gdy wątek ma zakończyć pracę (wyczerpany limit stron)
     * @throws DatabaseException gdy nie działa baza - to obsługuje {@link #run()}, nie ta metoda
     */
    private boolean process(LinkRecord link) throws InterruptedException {
        if (shouldSkip(link.url())) {
            db.markSkipped(link.id());
            log("pominięto " + link.url());
            return true;
        }

        // robots.txt przed rezerwacją miejsca w limicie: zabroniona strona nie wymaga żądania HTTP,
        // więc nie może zabrać miejsca stronie dozwolonej.
        long crawlDelayMs = 0;
        if (config.respectRobots()) {
            RobotsTxt rules = robotsFor(link);
            if (!rules.isAllowed(UrlUtils.pathWithQuery(link.url()))) {
                db.markSkipped(link.id(), ROBOTS_SKIP_REASON);
                log("pominięto (robots.txt) " + link.url());
                return true;
            }
            crawlDelayMs = rules.crawlDelayMs().orElse(0);
        }

        // Rezerwacja miejsca tuż przed żądaniem: incrementAndGet jest atomowe, więc
        // nawet przy wielu wątkach naraz dokładnie maxPages z nich dostanie wartość <= limit.
        // Ponowienie to też żądanie HTTP, więc rezerwuje miejsce jak pierwsza próba.
        if (pagesFetched.incrementAndGet() > config.maxPages()) {
            // Cofamy nieudaną rezerwację, żeby licznik pokazywał faktyczną liczbę żądań.
            pagesFetched.decrementAndGet();
            // Link wraca do NEW - przy wznowieniu z większym limitem zostanie odwiedzony.
            db.releaseLink(link.id());
            log("osiągnięto limit " + config.maxPages() + " stron, oddano " + link.url());
            return false;
        }

        try {
            Document doc = fetch(link.url());
            List<String> links = extractLinks(doc);
            // Linki i status VISITED zapisuje jedna transakcja, więc żaden wątek nie zobaczy
            // strony VISITED bez jej linków. To ważne: dopóki strona jest IN_PROGRESS, inne
            // wątki czekają zamiast kończyć pracę - gdyby VISITED pojawiło się przed linkami,
            // mogłyby uznać, że pracy już nie ma, i zakończyć się przedwcześnie.
            db.saveVisitedPage(link.id(), links, link.depth() + 1);
            log("odwiedzono " + link.url() + " (głębokość " + link.depth()
                    + ", linków: " + links.size() + ")");
        } catch (HttpStatusException e) {
            handleFailure(link, e, "HTTP " + e.getStatusCode());
        } catch (UnsupportedMimeTypeException e) {
            markError(link, "Nieobsługiwany typ treści: " + e.getMimeType());
        } catch (SocketTimeoutException e) {
            handleFailure(link, e, "Przekroczono czas oczekiwania (" + config.timeoutMs() + " ms)");
        } catch (DatabaseException e) {
            // Błąd bazy nie jest błędem tej strony - przekazujemy go dalej do run().
            throw e;
        } catch (IOException | RuntimeException e) {
            // RuntimeException to też błąd tej jednej strony, nie całego wątku: jsoup rzuca
            // np. IllegalArgumentException (przekierowanie na nieobsługiwany schemat) albo
            // UncheckedIOException (błąd przy czytaniu treści). Bez tego wyjątek zabiłby wątek,
            // a strona zostałaby IN_PROGRESS na zawsze.
            if (Thread.currentThread().isInterrupted()) {
                // Pobieranie przerwane z zewnątrz - strona nie jest wadliwa, oddajemy ją.
                db.releaseLink(link.id());
                throw new InterruptedException("Przerwano pobieranie " + link.url());
            }
            // Tu trafia m.in. ConnectException (serwer odrzucił połączenie) - błąd przejściowy.
            handleFailure(link, e, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Grzeczność wobec serwera: przerwa po każdym żądaniu, także nieudanym.
        Thread.sleep(pauseAfterRequestMs(config.delayMs(), crawlDelayMs));
        return true;
    }

    /**
     * Nieudane pobranie strony. Błąd przejściowy ponawiamy, dopóki nie wyczerpiemy --retries:
     * link wraca do NEW, ale inny (albo ten sam) wątek przejmie go dopiero po przerwie.
     * Pozostałe błędy i nieudana ostatnia próba kończą się statusem ERROR.
     */
    private void handleFailure(LinkRecord link, Exception cause, String message) {
        if (isTransient(cause) && link.attempts() < config.maxRetries()) {
            long delayMs = retryDelayMs(link.attempts());
            db.scheduleRetry(link.id(), message, clock.getAsLong() + delayMs);
            logError("błąd przejściowy " + link.url() + ": " + message + " - ponowienie "
                    + (link.attempts() + 1) + "/" + config.maxRetries() + " za " + delayMs + " ms");
            return;
        }
        int tries = link.attempts() + 1;
        // Dopisek tylko po kilku próbach - po jednej opis wygląda jak dotąd, np. "HTTP 404".
        markError(link, tries > 1 ? message + " (po " + tries + " próbach)" : message);
    }

    /**
     * Błąd przejściowy - ten sam adres za chwilę może zadziałać: przekroczony czas, odrzucone
     * połączenie (serwer chwilowo nie przyjmuje połączeń), HTTP 429 (za dużo żądań) i 5xx
     * (błąd serwera). Pozostałe błędy (np. 404, zły typ treści) powtórzyłyby się przy każdej próbie.
     */
    static boolean isTransient(Exception e) {
        if (e instanceof HttpStatusException http) {
            int status = http.getStatusCode();
            return status == 429 || (status >= 500 && status <= 599);
        }
        return e instanceof SocketTimeoutException || e instanceof ConnectException;
    }

    /**
     * Przerwa przed ponowieniem po attempts nieudanych próbach: 1 s, 2 s, 4 s..., najwyżej 30 s.
     * Wykładnik ograniczamy, bo w Javie przesunięcie o 64 bity i więcej "zawija się"
     * (1L << 64 == 1) - przy dużym --retries przerwy znów byłyby krótkie.
     */
    static long retryDelayMs(int attempts) {
        int exponent = Math.min(attempts, 30);
        return Math.min(RETRY_BASE_DELAY_MS << exponent, MAX_RETRY_DELAY_MS);
    }

    /**
     * Przerwa po żądaniu: dłuższa z --delay i Crawl-delay hosta. Crawl-delay ograniczamy do
     * {@link CrawlerConfig#MAX_CRAWL_DELAY_MS}, bo wpisuje go właściciel serwera; --delay
     * użytkownik wybrał sam, więc go nie skracamy.
     */
    static long pauseAfterRequestMs(int delayMs, long crawlDelayMs) {
        return Math.max(delayMs, Math.min(crawlDelayMs, CrawlerConfig.MAX_CRAWL_DELAY_MS));
    }

    /**
     * Reguły robots.txt hosta strony. Przerwanie w trakcie pobierania robots.txt traktujemy
     * jak przerwanie pobierania strony: link wraca do NEW, a nie dostaje SKIPPED.
     */
    private RobotsTxt robotsFor(LinkRecord link) throws InterruptedException {
        try {
            return robots.rulesFor(link.url());
        } catch (InterruptedException e) {
            db.releaseLink(link.id());
            throw e;
        }
    }

    /** Pomijamy bez żądania HTTP: obce hosty (jeśli tak ustawiono) i pliki binarne. */
    private boolean shouldSkip(String url) {
        boolean otherHost = config.sameHostOnly() && !UrlUtils.isSameHost(url, config.startUrl());
        return otherHost || UrlUtils.looksLikeBinaryFile(url);
    }

    private Document fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(config.userAgent())
                .timeout(config.timeoutMs())
                .get();
    }

    /**
     * Wszystkie linki ze strony w postaci znormalizowanej, w kolejności występowania.
     * Duplikaty zostają celowo: każde wystąpienie zwiększa {@code seen} w bazie.
     * Pomijane są puste {@code href} oraz adresy, których {@link UrlUtils#normalize}
     * nie przyjmuje (mailto:, javascript:, tel: itp.).
     * Strona z meta tagiem robots {@code nofollow} albo {@code none} nie daje żadnych linków.
     */
    static List<String> extractLinks(Document doc) {
        if (forbidsFollowingLinks(doc)) {
            // Strona i tak dostaje VISITED - została pobrana, tylko nie idziemy za jej linkami.
            return List.of();
        }
        List<String> links = new ArrayList<>();
        for (Element anchor : doc.select("a[href]")) {
            // Pusty href jsoup rozwiązałby do adresu bieżącej strony - to nie jest prawdziwy link.
            if (anchor.attr("href").isBlank()) {
                continue;
            }
            // absUrl zamienia adres względny na bezwzględny względem adresu strony;
            // dla adresu, którego nie da się rozwiązać, zwraca pusty tekst.
            UrlUtils.normalize(anchor.absUrl("href")).ifPresent(links::add);
        }
        return links;
    }

    /**
     * {@code <meta name="robots" content="noindex, nofollow">} - właściciel prosi, żeby roboty
     * nie szły za linkami strony; {@code none} znaczy to samo co {@code noindex, nofollow}.
     * Nazwa i wartości bez względu na wielkość liter. Wartości rozdziela przecinek, ale
     * przyjmujemy też same spacje ({@code "noindex nofollow"}) - tak też zdarza się pisać.
     */
    static boolean forbidsFollowingLinks(Document doc) {
        for (Element meta : doc.select("meta[name]")) {
            if (!meta.attr("name").strip().equalsIgnoreCase("robots")) {
                continue;
            }
            for (String value : meta.attr("content").split("[,\\s]+")) {
                if (value.equalsIgnoreCase("nofollow") || value.equalsIgnoreCase("none")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void markError(LinkRecord link, String message) {
        db.markError(link.id(), message);
        logError("błąd " + link.url() + ": " + message);
    }

    private static void log(String message) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + message);
    }

    private static void logError(String message) {
        System.err.println("[" + Thread.currentThread().getName() + "] " + message);
    }
}
