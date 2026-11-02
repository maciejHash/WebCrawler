package pl.webcrawler;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final DBConn db;
    private final CrawlerConfig config;
    private final AtomicInteger pagesFetched;
    private final AtomicBoolean stopRequested;

    /**
     * @param pagesFetched  licznik wspólny dla wszystkich wątków; dzięki niemu limit
     *                      maxPages dotyczy całego robota, a nie pojedynczego wątku
     * @param stopRequested flaga wspólna dla wszystkich wątków; ustawiona (np. po Ctrl+C)
     *                      oznacza: dokończ bieżącą stronę i nie bierz kolejnej
     */
    public CrawlerThread(DBConn db, CrawlerConfig config, AtomicInteger pagesFetched,
                         AtomicBoolean stopRequested) {
        this.db = db;
        this.config = config;
        this.pagesFetched = pagesFetched;
        this.stopRequested = stopRequested;
    }

    @Override
    public void run() {
        String workerName = Thread.currentThread().getName();
        log("start");
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
                Optional<LinkRecord> claimed = db.claimNextLink(workerName, config.maxDepth());
                if (claimed.isEmpty()) {
                    // Brak NEW nie oznacza końca: strona IN_PROGRESS w innym wątku może
                    // za chwilę dodać nowe linki. Kończymy dopiero, gdy nie ma ani jednych, ani drugich.
                    if (!db.hasPendingWork(config.maxDepth())) {
                        log("brak linków do odwiedzenia");
                        break;
                    }
                    Thread.sleep(WAIT_FOR_WORK_MS);
                    continue;
                }
                if (!process(claimed.get())) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            // sleep() czyści flagę przerwania - przywracamy ją, żeby wywołujący
            // (np. ExecutorService przy shutdownNow) wiedział, że wątek przerwano.
            Thread.currentThread().interrupt();
            log("przerwano");
        }
        log("koniec");
    }

    /**
     * Obsługuje jeden przejęty link.
     *
     * @return false, gdy wątek ma zakończyć pracę (wyczerpany limit stron)
     */
    private boolean process(LinkRecord link) throws InterruptedException {
        if (shouldSkip(link.url())) {
            db.markSkipped(link.id());
            log("pominięto " + link.url());
            return true;
        }

        // Rezerwacja miejsca tuż przed żądaniem: incrementAndGet jest atomowe, więc
        // nawet przy wielu wątkach naraz dokładnie maxPages z nich dostanie wartość <= limit.
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
            // Najpierw linki, potem VISITED: dopóki strona jest IN_PROGRESS, inne wątki
            // czekają zamiast kończyć pracę, więc nie zakończą się przed zapisaniem tych linków.
            for (String found : links) {
                db.insertRow(found, link.depth() + 1);
            }
            db.markVisited(link.id());
            log("odwiedzono " + link.url() + " (głębokość " + link.depth()
                    + ", linków: " + links.size() + ")");
        } catch (HttpStatusException e) {
            markError(link, "HTTP " + e.getStatusCode());
        } catch (UnsupportedMimeTypeException e) {
            markError(link, "Nieobsługiwany typ treści: " + e.getMimeType());
        } catch (SocketTimeoutException e) {
            markError(link, "Przekroczono czas oczekiwania (" + config.timeoutMs() + " ms)");
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: jsoup odrzuca niektóre adresy (np. przekierowanie
            // na nieobsługiwany schemat) - to też błąd tej jednej strony, nie całego wątku.
            if (Thread.currentThread().isInterrupted()) {
                // Pobieranie przerwane z zewnątrz - strona nie jest wadliwa, oddajemy ją.
                db.releaseLink(link.id());
                throw new InterruptedException("Przerwano pobieranie " + link.url());
            }
            markError(link, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Grzeczność wobec serwera: przerwa po każdym żądaniu, także nieudanym.
        Thread.sleep(config.delayMs());
        return true;
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
     */
    static List<String> extractLinks(Document doc) {
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
