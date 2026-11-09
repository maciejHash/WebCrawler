package pl.webcrawler;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Cała obsługa bazy SQLite. Jedna instancja jest współdzielona przez wszystkie wątki,
 * dlatego metody publiczne są synchronized - w danej chwili z połączenia korzysta
 * tylko jeden wątek (obiekt Connection z JDBC nie jest bezpieczny wielowątkowo).
 */
public class DBConn implements AutoCloseable {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS links (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                url        TEXT    NOT NULL UNIQUE,
                depth      INTEGER NOT NULL,
                seen       INTEGER NOT NULL DEFAULT 1,
                status     TEXT    NOT NULL DEFAULT 'NEW',
                claimed_by TEXT,
                found_at   TEXT DEFAULT CURRENT_TIMESTAMP,
                visited_at TEXT,
                error      TEXT
            )""";

    // Wątki szukają linków po statusie i głębokości - indeks przyspiesza to wyszukiwanie.
    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_links_status_depth ON links (status, depth)";

    // Ponawianie błędów przejściowych. attempts: ile prób skończyło się błędem przejściowym
    // i ponowieniem. retry_at: od kiedy link wolno przejąć (ms od epoki, czas z Javy, a nie
    // CURRENT_TIMESTAMP - dzięki temu test steruje czasem); NULL = od razu.
    // ADD COLUMN z NOT NULL wymaga wartości domyślnej - istniejące wiersze dostają 0.
    private static final String ADD_ATTEMPTS =
            "ALTER TABLE links ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0";
    private static final String ADD_RETRY_AT =
            "ALTER TABLE links ADD COLUMN retry_at INTEGER";

    private static final String CREATE_EDGES = """
            CREATE TABLE edges (
                from_id INTEGER NOT NULL REFERENCES links(id),
                to_id   INTEGER NOT NULL REFERENCES links(id),
                count   INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (from_id, to_id)
            ) WITHOUT ROWID""";
    private static final String CREATE_EDGES_INDEX =
            "CREATE INDEX idx_edges_to_id ON edges (to_id)";

    /**
     * Migracje schematu: element o indeksie i prowadzi bazę do wersji i + 1. Numer wersji
     * jest zapisany w pliku bazy (PRAGMA user_version, nowa baza ma 0).
     * Zmianę schematu dopisuje się jako nowy element na końcu listy. Istniejących migracji
     * nie wolno zmieniać: bazy, które już je mają, nie wykonają ich drugi raz.
     * Migracja 1 ma IF NOT EXISTS, bo wersje programu sprzed wersjonowania tworzyły tę samą
     * tabelę i indeks, ale zostawiały user_version = 0 - na takiej bazie niczego nie zmienia.
     */
    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(CREATE_TABLE, CREATE_INDEX),    // 1: tabela links i indeks (status, depth)
            List.of(ADD_ATTEMPTS, ADD_RETRY_AT),    // 2: ponawianie - kolumny attempts i retry_at
            List.of(CREATE_EDGES, CREATE_EDGES_INDEX) // 3: graf linków
    );

    /** Wersja schematu, do której createTables() doprowadza bazę: numer ostatniej migracji. */
    public static final int LATEST_SCHEMA_VERSION = MIGRATIONS.size();

    private static final String GET_SCHEMA_VERSION = "PRAGMA user_version";

    // PRAGMA nie przyjmuje parametrów (?), więc numer doklejamy do tekstu. To bezpieczne:
    // zawsze jest to int z naszego kodu, a nie dane z zewnątrz.
    private static final String SET_SCHEMA_VERSION = "PRAGMA user_version = ";

    // Jedno zapytanie zamiast "SELECT, potem INSERT albo UPDATE": dzięki UNIQUE na url
    // baza sama rozstrzyga, czy link jest nowy, więc dwa wątki nie wstawią go podwójnie.
    // MIN(depth, ...) - jeśli link znaleziono krótszą drogą, zapamiętujemy mniejszą głębokość.
    private static final String UPSERT_LINK = """
            INSERT INTO links (url, depth) VALUES (?, ?)
            ON CONFLICT(url) DO UPDATE SET
                seen  = seen + 1,
                depth = MIN(depth, excluded.depth)""";

    // Po INSERT ... SELECT parser SQLite może uznać ON za warunek JOIN zamiast UPSERT-u.
    // Klauzula WHERE kończy tę niejednoznaczność (bez filtra należałoby użyć WHERE true).
    private static final String UPSERT_EDGE = """
            INSERT INTO edges (from_id, to_id) SELECT ?, id FROM links WHERE url = ?
            ON CONFLICT DO UPDATE SET count = count + 1""";

    // Adres startowy: OR IGNORE, a nie UPSERT, bo ponowne uruchomienie na tej samej bazie
    // to wznowienie pracy, a nie kolejne "znalezienie" linku - seen nie może rosnąć.
    private static final String INSERT_SEED =
            "INSERT OR IGNORE INTO links (url, depth) VALUES (?, 0)";

    private static final String SELECT_BY_URL = """
            SELECT id, url, depth, seen, status, claimed_by, found_at, visited_at, error, attempts, retry_at
            FROM links WHERE url = ?""";

    // Przy równym seen decyduje id (kolejność znalezienia) - raport jest powtarzalny.
    private static final String SELECT_MOST_SEEN = """
            SELECT id, url, depth, seen, status, claimed_by, found_at, visited_at, error, attempts, retry_at
            FROM links ORDER BY seen DESC, id
            LIMIT ?""";

    private static final String SELECT_LINKS = """
            SELECT id, url, depth, seen, status, claimed_by, found_at, visited_at, error, attempts, retry_at
            FROM links ORDER BY id""";
    private static final String SELECT_VISITED_LINKS = """
            SELECT id, url, depth, seen, status, claimed_by, found_at, visited_at, error, attempts, retry_at
            FROM links WHERE status = 'VISITED' ORDER BY id""";
    private static final String SELECT_EDGES = """
            SELECT e.from_id, e.to_id, f.url AS from_url, t.url AS to_url, e.count
            FROM edges e JOIN links f ON f.id = e.from_id JOIN links t ON t.id = e.to_id
            ORDER BY e.from_id, e.to_id""";
    private static final String SELECT_VISITED_EDGES = """
            SELECT e.from_id, e.to_id, f.url AS from_url, t.url AS to_url, e.count
            FROM edges e JOIN links f ON f.id = e.from_id JOIN links t ON t.id = e.to_id
            WHERE f.status = 'VISITED' AND t.status = 'VISITED'
            ORDER BY e.from_id, e.to_id""";
    // Para (from_id, to_id) jest unikalna, więc COUNT(*) liczy różne strony źródłowe,
    // niezależnie od liczby wystąpień odnośnika na każdej z nich (edges.count).
    private static final String SELECT_MOST_LINKED = """
            SELECT t.url, COUNT(*) AS sources FROM edges e JOIN links t ON t.id = e.to_id
            GROUP BY e.to_id ORDER BY sources DESC, e.to_id LIMIT ?""";

    // Strony faktycznie pobrane (VISITED i ERROR) - SKIPPED nie wymagał żądania HTTP.
    // claimed_by zostaje w wierszu po zakończeniu, więc wiadomo, który wątek go obsłużył.
    // Sortowanie po długości, żeby crawler-2 było przed crawler-10.
    private static final String COUNT_PAGES_BY_WORKER = """
            SELECT claimed_by, COUNT(*) AS cnt FROM links
            WHERE status IN ('VISITED', 'ERROR') AND claimed_by IS NOT NULL
            GROUP BY claimed_by
            ORDER BY LENGTH(claimed_by), claimed_by""";

    // Kandydaci do przejęcia: linki NEW w dozwolonej głębokości, najpierw najpłytsze, a przy
    // równej głębokości najstarsze (najmniejsze id) - przeszukiwanie wszerz. Depth jest czytane
    // przy każdym SELECT-cie, więc link, któremu UPSERT zmniejszył depth, od razu przesuwa się
    // w kolejce. Indeks (status, depth) przechowuje wpisy w kolejności (depth, rowid), a id
    // to rowid - SQLite czyta je w tej kolejności i nie musi sortować.
    // Pobieramy kilku naraz, żeby po przegranym wyścigu nie robić od razu nowego SELECT-a.
    // Link czekający na ponowienie (retry_at w przyszłości) jest pomijany, ale nie blokuje
    // kolejki - wątek bierze następny. Warunek na retry_at SQLite sprawdza na wierszach
    // znalezionych przez indeks, więc plan zapytania się nie zmienia (dalej bez sortowania).
    private static final int CLAIM_BATCH = 20;
    private static final String SELECT_CANDIDATES = """
            SELECT id, url, depth, attempts FROM links
            WHERE status = 'NEW' AND depth <= ? AND (retry_at IS NULL OR retry_at <= ?)
            ORDER BY depth, id
            LIMIT ?""";

    // Serce ochrony przed podwójnymi odwiedzinami. Warunek status='NEW' sprawdza baza
    // w chwili zapisu (zapisy w SQLite są szeregowane), więc z kilku połączeń, które
    // próbują przejąć ten sam link, tylko pierwsze zmieni wiersz - reszta dostanie 0.
    private static final String CLAIM_LINK = """
            UPDATE links SET status = 'IN_PROGRESS', claimed_by = ?
            WHERE id = ? AND status = 'NEW'""";

    private static final String MARK_VISITED = """
            UPDATE links SET status = 'VISITED', visited_at = CURRENT_TIMESTAMP, error = NULL
            WHERE id = ?""";

    private static final String MARK_ERROR = """
            UPDATE links SET status = 'ERROR', visited_at = CURRENT_TIMESTAMP, error = ?
            WHERE id = ?""";

    private static final String MARK_SKIPPED = """
            UPDATE links SET status = 'SKIPPED', visited_at = CURRENT_TIMESTAMP
            WHERE id = ?""";

    // Powód pominięcia trafia do kolumny error - jak opis błędu przy ERROR.
    private static final String MARK_SKIPPED_WITH_REASON = """
            UPDATE links SET status = 'SKIPPED', visited_at = CURRENT_TIMESTAMP, error = ?
            WHERE id = ?""";

    private static final String RELEASE_LINK = """
            UPDATE links SET status = 'NEW', claimed_by = NULL
            WHERE id = ? AND status = 'IN_PROGRESS'""";

    // Jak RELEASE_LINK, ale link wraca do kolejki dopiero od retry_at i z licznikiem prób.
    // Warunek status = 'IN_PROGRESS': zmieniamy tylko link, który wciąż jest przejęty.
    private static final String SCHEDULE_RETRY = """
            UPDATE links SET status = 'NEW', attempts = attempts + 1, claimed_by = NULL,
                             error = ?, retry_at = ?
            WHERE id = ? AND status = 'IN_PROGRESS'""";

    // Jedno zapytanie, żeby odpowiedź była spójna: dwa osobne zapytania mogłyby zobaczyć
    // bazę w dwóch różnych chwilach (np. link przeszedł z NEW do IN_PROGRESS pomiędzy nimi).
    // Celowo bez warunku na retry_at: link czekający na ponowienie to też praca do zrobienia,
    // więc wątki czekają na niego, zamiast kończyć.
    private static final String HAS_PENDING_WORK = """
            SELECT EXISTS (
                SELECT 1 FROM links
                WHERE status = 'IN_PROGRESS' OR (status = 'NEW' AND depth <= ?)
            )""";

    private static final String RESET_IN_PROGRESS = """
            UPDATE links SET status = 'NEW', claimed_by = NULL
            WHERE status = 'IN_PROGRESS'""";

    private static final String COUNT_BY_STATUS =
            "SELECT status, COUNT(*) AS cnt FROM links GROUP BY status";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS links";
    private static final String DROP_EDGES = "DROP TABLE IF EXISTS edges";

    /**
     * Wiersz tabeli links w postaci potrzebnej do sprawdzania stanu bazy (m.in. w testach).
     * retryAt to Long, a nie long, bo w bazie może być NULL (ponowienia nie zaplanowano).
     */
    public record LinkRow(long id, String url, int depth, int seen, String status,
                          String claimedBy, String foundAt, String visitedAt, String error,
                          int attempts, Long retryAt) {
    }

    public record EdgeRow(long fromId, long toId, String fromUrl, String toUrl, long count) { }

    public record IncomingLinks(String url, long sources) { }

    private final String dbPath;
    private Connection connection;

    /** Samo utworzenie obiektu nie otwiera bazy - do tego służy {@link #connect()}. */
    public DBConn(String dbPath) {
        this.dbPath = dbPath;
    }

    /** Otwiera połączenie. Ponowne wywołanie przy otwartym połączeniu nic nie robi. */
    public synchronized void connect() {
        connect("jdbc:sqlite:" + dbPath);
    }

    /** Tryb rw pozwala eksportować istniejącą bazę, ale nigdy nie tworzy nowego pliku. */
    public synchronized void connectExisting() {
        connect("jdbc:sqlite:" + Path.of(dbPath).toAbsolutePath().toUri() + "?mode=rw");
    }

    private void connect(String jdbcUrl) {
        if (connection != null) {
            return;
        }
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            // WAL: odczyty nie blokują zapisu. busy_timeout: przy chwilowej blokadzie pliku
            // SQLite czeka do 5 s, zamiast od razu zgłaszać błąd SQLITE_BUSY.
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            disconnect();
            throw new DatabaseException("Nie można otworzyć bazy: " + dbPath, e);
        }
    }

    /**
     * Doprowadza schemat do wersji {@link #LATEST_SCHEMA_VERSION}: wykonuje po kolei migracje,
     * których baza jeszcze nie ma. Ponowne wywołanie nic nie robi.
     * Każda migracja razem z nowym user_version to osobna transakcja - przerwanie programu
     * nie zostawi migracji wykonanej w połowie ani numeru wersji niezgodnego ze schematem.
     *
     * @throws DatabaseException także wtedy, gdy baza ma wersję wyższą niż znana programowi
     */
    public synchronized void createTables() {
        int version = schemaVersion();
        if (version > LATEST_SCHEMA_VERSION) {
            // Starszy program nie zna tabel i kolumn dodanych później - nie ruszamy takiej bazy.
            throw new DatabaseException("Baza " + dbPath + " pochodzi z nowszej wersji programu"
                    + " (wersja schematu " + version + ", ten program obsługuje najwyżej "
                    + LATEST_SCHEMA_VERSION + ") - użyj nowszej wersji programu albo innego pliku bazy");
        }
        for (int next = version + 1; next <= LATEST_SCHEMA_VERSION; next++) {
            List<String> statements = new ArrayList<>(MIGRATIONS.get(next - 1));
            statements.add(SET_SCHEMA_VERSION + next);
            try {
                executeInTransaction(statements);
            } catch (SQLException e) {
                throw new DatabaseException("Nie można wykonać migracji schematu do wersji " + next, e);
            }
        }
    }

    /** Wersja schematu zapisana w pliku bazy (PRAGMA user_version); 0 - żadnej migracji. */
    public synchronized int schemaVersion() {
        try (PreparedStatement stmt = openConnection().prepareStatement(GET_SCHEMA_VERSION);
             ResultSet rs = stmt.executeQuery()) {
            // PRAGMA user_version zawsze zwraca dokładnie jeden wiersz z jedną liczbą.
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać wersji schematu bazy: " + dbPath, e);
        }
    }

    /** Zapisuje link: nowy dostaje seen=1 i status NEW, istniejący ma zwiększone seen. */
    public synchronized void insertRow(String url, int depth) {
        try (PreparedStatement stmt = openConnection().prepareStatement(UPSERT_LINK)) {
            stmt.setString(1, url);
            stmt.setInt(2, depth);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Nie można zapisać linku: " + url, e);
        }
    }

    /**
     * Dodaje adres startowy z głębokością 0. Jeśli adres już jest w bazie (wznowienie),
     * wiersz zostaje bez zmian - łącznie z seen, depth i statusem.
     *
     * @return true, gdy wiersz faktycznie dodano
     */
    public synchronized boolean addSeed(String url) {
        try (PreparedStatement stmt = openConnection().prepareStatement(INSERT_SEED)) {
            stmt.setString(1, url);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można dodać adresu startowego: " + url, e);
        }
    }

    /** Zwraca wiersz o podanym adresie albo pusty Optional, gdy takiego linku nie ma. */
    public synchronized Optional<LinkRow> findByUrl(String url) {
        try (PreparedStatement stmt = openConnection().prepareStatement(SELECT_BY_URL)) {
            stmt.setString(1, url);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(toLinkRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać linku: " + url, e);
        }
    }

    /** Najczęściej znajdowane linki (największe seen), najwyżej limit wierszy. */
    public synchronized List<LinkRow> findMostSeen(int limit) {
        List<LinkRow> rows = new ArrayList<>();
        try (PreparedStatement stmt = openConnection().prepareStatement(SELECT_MOST_SEEN)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(toLinkRow(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać najczęściej znajdowanych linków", e);
        }
    }

    /** Najwyżej limit adresów z największą liczbą różnych stron linkujących. */
    public synchronized List<IncomingLinks> findMostLinked(int limit) {
        List<IncomingLinks> rows = new ArrayList<>();
        try (PreparedStatement stmt = openConnection().prepareStatement(SELECT_MOST_LINKED)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new IncomingLinks(rs.getString("url"), rs.getLong("sources")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać stron z największą liczbą źródeł", e);
        }
    }

    /** Przekazuje po jednym wierszu, bez listy całej bazy; zwraca liczbę wierszy. */
    public synchronized long forEachLink(Consumer<LinkRow> consumer) {
        return readLinks(SELECT_LINKS, consumer);
    }

    /** Obejmuje także odwiedzone strony bez krawędzi, potrzebne jako węzły DOT. */
    public synchronized long forEachVisitedLink(Consumer<LinkRow> consumer) {
        return readLinks(SELECT_VISITED_LINKS, consumer);
    }

    private long readLinks(String sql, Consumer<LinkRow> consumer) {
        try (PreparedStatement stmt = openConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            long count = 0;
            while (rs.next()) {
                consumer.accept(toLinkRow(rs));
                count++;
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać linków do eksportu", e);
        }
    }

    /** visitedOnly ogranicza oba końce krawędzi do VISITED; wynik nie jest zbierany w pamięci. */
    public synchronized long forEachEdge(boolean visitedOnly, Consumer<EdgeRow> consumer) {
        String sql = visitedOnly ? SELECT_VISITED_EDGES : SELECT_EDGES;
        try (PreparedStatement stmt = openConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            long count = 0;
            while (rs.next()) {
                consumer.accept(new EdgeRow(rs.getLong("from_id"), rs.getLong("to_id"),
                        rs.getString("from_url"), rs.getString("to_url"), rs.getLong("count")));
                count++;
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać krawędzi do eksportu", e);
        }
    }

    /**
     * Liczba stron pobranych przez każdy wątek (kolumna claimed_by), w kolejności
     * crawler-1, crawler-2, ... Obejmuje całą bazę, także poprzednie uruchomienia.
     */
    public synchronized Map<String, Integer> countPagesByWorker() {
        // LinkedHashMap zachowuje kolejność z ORDER BY.
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement stmt = openConnection().prepareStatement(COUNT_PAGES_BY_WORKER);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("claimed_by"), rs.getInt("cnt"));
            }
            return counts;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można policzyć stron według wątków", e);
        }
    }

    /**
     * Przejmuje link NEW o głębokości do maxDepth i zapisuje, kto go wziął. Kolejność to
     * przeszukiwanie wszerz: najmniejsza aktualna głębokość (także zmniejszona później
     * przez UPSERT), a przy równej głębokości najstarszy link (najmniejsze id).
     * Link należy do wątku tylko wtedy, gdy warunkowy UPDATE zmienił wiersz; przy 0
     * (link właśnie przejęło inne połączenie) próbujemy kolejnego kandydata.
     *
     * @param nowMs bieżący czas w ms od epoki; link z retry_at > nowMs czeka jeszcze na ponowienie.
     *              Czas podaje wywołujący, a nie baza, żeby test mógł nim sterować
     */
    public synchronized Optional<LinkRecord> claimNextLink(String workerName, int maxDepth, long nowMs) {
        try {
            while (true) {
                List<LinkRecord> candidates = selectCandidates(maxDepth, nowMs);
                if (candidates.isEmpty()) {
                    return Optional.empty();
                }
                for (LinkRecord candidate : candidates) {
                    if (tryClaim(candidate.id(), workerName)) {
                        return Optional.of(candidate);
                    }
                }
                // Wszystkich kandydatów przejęły inne połączenia - pobieramy świeżą listę.
                // Pętla się skończy: każda przegrana oznacza, że jeden link NEW zniknął.
            }
        } catch (SQLException e) {
            throw new DatabaseException("Nie można przejąć linku dla " + workerName, e);
        }
    }

    /** Strona pobrana i przetworzona. */
    public synchronized void markVisited(long id) {
        updateById(MARK_VISITED, id, "Nie można oznaczyć linku jako VISITED: ");
    }

    /**
     * Zapisuje wynik odwiedzenia strony w jednej transakcji: wszystkie znalezione linki
     * (ten sam UPSERT co {@link #insertRow}), krawędzie i status VISITED strony o podanym id.
     * Zmiany widać w bazie albo wszystkie, albo żadnej - przerwanie w połowie nie zostawi
     * strony IN_PROGRESS z częścią linków (przy wznowieniu seen rosłoby drugi raz).
     * Przy okazji jeden COMMIT zamiast osobnego zapisu na dysk dla każdego linku.
     *
     * @param links     znalezione adresy (już znormalizowane), duplikaty zwiększają seen
     * @param linkDepth głębokość znalezionych linków, czyli głębokość strony + 1
     */
    public synchronized void saveVisitedPage(long id, List<String> links, int linkDepth) {
        Connection conn = openConnection();
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement upsert = conn.prepareStatement(UPSERT_LINK);
                 PreparedStatement edge = conn.prepareStatement(UPSERT_EDGE);
                 PreparedStatement visited = conn.prepareStatement(MARK_VISITED)) {
                for (String url : links) {
                    upsert.setString(1, url);
                    upsert.setInt(2, linkDepth);
                    upsert.addBatch();
                }
                upsert.executeBatch();
                for (String url : links) {
                    edge.setLong(1, id);
                    edge.setString(2, url);
                    edge.addBatch();
                }
                edge.executeBatch();
                visited.setLong(1, id);
                visited.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollback(conn, e);
            throw new DatabaseException("Nie można zapisać odwiedzonej strony: " + id, e);
        } finally {
            restoreAutoCommit(conn);
        }
    }

    /** Nie udało się pobrać strony; message trafia do kolumny error. */
    public synchronized void markError(long id, String message) {
        try (PreparedStatement stmt = openConnection().prepareStatement(MARK_ERROR)) {
            stmt.setString(1, message);
            stmt.setLong(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Nie można oznaczyć linku jako ERROR: " + id, e);
        }
    }

    /** Link świadomie pominięty bez żądania HTTP: obcy host albo plik binarny. */
    public synchronized void markSkipped(long id) {
        updateById(MARK_SKIPPED, id, "Nie można oznaczyć linku jako SKIPPED: ");
    }

    /** Link pominięty bez żądania HTTP z podanego powodu (np. "robots.txt"), zapisanego w kolumnie error. */
    public synchronized void markSkipped(long id, String reason) {
        try (PreparedStatement stmt = openConnection().prepareStatement(MARK_SKIPPED_WITH_REASON)) {
            stmt.setString(1, reason);
            stmt.setLong(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Nie można oznaczyć linku jako SKIPPED: " + id, e);
        }
    }

    /** Oddaje przejęty link (IN_PROGRESS → NEW), żeby mógł go wziąć inny wątek. */
    public synchronized void releaseLink(long id) {
        updateById(RELEASE_LINK, id, "Nie można zwolnić linku: ");
    }

    /**
     * Błąd przejściowy: link wraca z IN_PROGRESS do NEW, ale przejąć go można dopiero
     * od retryAtMs. attempts rośnie o 1, a w error zostaje ostatni błąd.
     * Link w innym statusie niż IN_PROGRESS zostaje bez zmian.
     */
    public synchronized void scheduleRetry(long id, String error, long retryAtMs) {
        try (PreparedStatement stmt = openConnection().prepareStatement(SCHEDULE_RETRY)) {
            stmt.setString(1, error);
            stmt.setLong(2, retryAtMs);
            stmt.setLong(3, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Nie można zaplanować ponowienia linku: " + id, e);
        }
    }

    /**
     * Czy jest jeszcze praca: link w trakcie odwiedzania (może dodać nowe linki)
     * albo link NEW w dozwolonej głębokości - także czekający na ponowienie. Dlatego
     * metoda nie potrzebuje bieżącego czasu: termin ponowienia nie zmienia odpowiedzi.
     */
    public synchronized boolean hasPendingWork(int maxDepth) {
        try (PreparedStatement stmt = openConnection().prepareStatement(HAS_PENDING_WORK)) {
            stmt.setInt(1, maxDepth);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Nie można sprawdzić stanu pracy", e);
        }
    }

    /**
     * Wznowienie po przerwanym uruchomieniu: linki, które zostały IN_PROGRESS,
     * nie mają już właściciela, więc wracają do NEW. Zwraca liczbę zmienionych wierszy.
     */
    public synchronized int resetInProgress() {
        try (PreparedStatement stmt = openConnection().prepareStatement(RESET_IN_PROGRESS)) {
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Nie można zresetować linków IN_PROGRESS", e);
        }
    }

    /** Liczba linków w każdym statusie; statusy bez linków nie występują w mapie. */
    public synchronized Map<String, Integer> countByStatus() {
        Map<String, Integer> counts = new TreeMap<>();
        try (PreparedStatement stmt = openConnection().prepareStatement(COUNT_BY_STATUS);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("status"), rs.getInt("cnt"));
            }
            return counts;
        } catch (SQLException e) {
            throw new DatabaseException("Nie można policzyć linków", e);
        }
    }

    /**
     * Usuwa tabele (indeksy znikają razem z nimi) i zeruje wersję schematu, żeby createTables()
     * wykonało wszystkie migracje od nowa. Oba kroki w jednej transakcji: gdyby program
     * przerwał się pomiędzy nimi, baza miałaby wersję 1 bez tabeli, a createTables()
     * uznałoby, że nie ma nic do zrobienia.
     */
    public synchronized void dropTables() {
        try {
            executeInTransaction(List.of(DROP_EDGES, DROP_TABLE, SET_SCHEMA_VERSION + 0));
        } catch (SQLException e) {
            throw new DatabaseException("Nie można usunąć tabel", e);
        }
    }

    /** Zamyka połączenie; ponowne wywołanie nic nie robi. */
    public synchronized void disconnect() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException("Błąd przy zamykaniu bazy: " + dbPath, e);
        } finally {
            // Nawet gdy close() się nie uda, nie próbujemy dalej używać tego połączenia.
            connection = null;
        }
    }

    /** Pozwala używać DBConn w try-with-resources. */
    @Override
    public synchronized void close() {
        disconnect();
    }

    /** Wykonuje polecenie bez parametrów (DDL, PRAGMA). execute(), bo PRAGMA zwraca wynik. */
    private void execute(String sql) throws SQLException {
        try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
            stmt.execute();
        }
    }

    /**
     * Wykonuje polecenia bez parametrów w jednej transakcji: w bazie zostają wszystkie zmiany
     * albo żadna. W SQLite transakcja obejmuje także DDL (CREATE, DROP) i PRAGMA user_version.
     */
    private void executeInTransaction(List<String> statements) throws SQLException {
        Connection conn = openConnection();
        conn.setAutoCommit(false);
        try {
            for (String sql : statements) {
                execute(sql);
            }
            conn.commit();
        } catch (SQLException e) {
            rollback(conn, e);
            throw e;
        } finally {
            restoreAutoCommit(conn);
        }
    }

    /**
     * Czyta kandydatów do listy i zamyka ResultSet PRZED jakimkolwiek UPDATE-em.
     * Otwarty SELECT trzyma transakcję odczytu na starym obrazie bazy; gdyby w tym czasie
     * inne połączenie coś zapisało, nasz UPDATE dostałby od razu SQLITE_BUSY
     * (busy_timeout tu nie pomaga, bo czekanie nie odświeży starego obrazu).
     */
    private List<LinkRecord> selectCandidates(int maxDepth, long nowMs) throws SQLException {
        List<LinkRecord> candidates = new ArrayList<>();
        try (PreparedStatement stmt = openConnection().prepareStatement(SELECT_CANDIDATES)) {
            stmt.setInt(1, maxDepth);
            stmt.setLong(2, nowMs);
            stmt.setInt(3, CLAIM_BATCH);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new LinkRecord(rs.getLong("id"), rs.getString("url"),
                            rs.getInt("depth"), rs.getInt("attempts")));
                }
            }
        }
        return candidates;
    }

    /** true tylko wtedy, gdy to nasze połączenie zmieniło status z NEW na IN_PROGRESS. */
    private boolean tryClaim(long id, String workerName) throws SQLException {
        try (PreparedStatement stmt = openConnection().prepareStatement(CLAIM_LINK)) {
            stmt.setString(1, workerName);
            stmt.setLong(2, id);
            return stmt.executeUpdate() == 1;
        }
    }

    private static LinkRow toLinkRow(ResultSet rs) throws SQLException {
        // getLong zamienia NULL na 0 - wasNull() mówi, czy w kolumnie naprawdę było NULL.
        // (getObject(..., Long.class) byłby krótszy, ale sqlite-jdbc rzuca przy nim wyjątek dla NULL.)
        long retryAtValue = rs.getLong("retry_at");
        Long retryAt = rs.wasNull() ? null : retryAtValue;
        return new LinkRow(
                rs.getLong("id"),
                rs.getString("url"),
                rs.getInt("depth"),
                rs.getInt("seen"),
                rs.getString("status"),
                rs.getString("claimed_by"),
                rs.getString("found_at"),
                rs.getString("visited_at"),
                rs.getString("error"),
                rs.getInt("attempts"),
                retryAt);
    }

    /** Wspólny kod dla UPDATE-ów, których jedynym parametrem jest id. */
    private void updateById(String sql, long id, String errorMessage) {
        try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException(errorMessage + id, e);
        }
    }

    /**
     * Cofa transakcję po błędzie. Jeśli i to się nie uda, ważniejszy jest pierwotny błąd -
     * błąd rollbacku dołączamy do niego jako "suppressed", żeby nie zginął z logów.
     */
    private static void rollback(Connection conn, SQLException cause) {
        try {
            conn.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }

    /**
     * Przywraca tryb autoCommit, w którym działają wszystkie pozostałe metody - bez tego
     * każdy kolejny insertRow czy claimNextLink czekałby w niezatwierdzonej transakcji.
     */
    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DatabaseException("Nie można przywrócić trybu autoCommit", e);
        }
    }

    private Connection openConnection() {
        if (connection == null) {
            throw new IllegalStateException("Brak połączenia z bazą - najpierw wywołaj connect()");
        }
        return connection;
    }
}
