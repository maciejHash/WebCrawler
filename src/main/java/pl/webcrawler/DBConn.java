package pl.webcrawler;

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

    // Jedno zapytanie zamiast "SELECT, potem INSERT albo UPDATE": dzięki UNIQUE na url
    // baza sama rozstrzyga, czy link jest nowy, więc dwa wątki nie wstawią go podwójnie.
    // MIN(depth, ...) - jeśli link znaleziono krótszą drogą, zapamiętujemy mniejszą głębokość.
    private static final String UPSERT_LINK = """
            INSERT INTO links (url, depth) VALUES (?, ?)
            ON CONFLICT(url) DO UPDATE SET
                seen  = seen + 1,
                depth = MIN(depth, excluded.depth)""";

    // Adres startowy: OR IGNORE, a nie UPSERT, bo ponowne uruchomienie na tej samej bazie
    // to wznowienie pracy, a nie kolejne "znalezienie" linku - seen nie może rosnąć.
    private static final String INSERT_SEED =
            "INSERT OR IGNORE INTO links (url, depth) VALUES (?, 0)";

    private static final String SELECT_BY_URL = """
            SELECT id, url, depth, seen, status, claimed_by, visited_at, error
            FROM links WHERE url = ?""";

    // Przy równym seen decyduje id (kolejność znalezienia) - raport jest powtarzalny.
    private static final String SELECT_MOST_SEEN = """
            SELECT id, url, depth, seen, status, claimed_by, visited_at, error
            FROM links ORDER BY seen DESC, id
            LIMIT ?""";

    // Strony faktycznie pobrane (VISITED i ERROR) - SKIPPED nie wymagał żądania HTTP.
    // claimed_by zostaje w wierszu po zakończeniu, więc wiadomo, który wątek go obsłużył.
    // Sortowanie po długości, żeby crawler-2 było przed crawler-10.
    private static final String COUNT_PAGES_BY_WORKER = """
            SELECT claimed_by, COUNT(*) AS cnt FROM links
            WHERE status IN ('VISITED', 'ERROR') AND claimed_by IS NOT NULL
            GROUP BY claimed_by
            ORDER BY LENGTH(claimed_by), claimed_by""";

    // Kandydaci do przejęcia: najstarsze (najmniejsze id) linki NEW w dozwolonej głębokości.
    // Pobieramy kilku naraz, żeby po przegranym wyścigu nie robić od razu nowego SELECT-a.
    private static final int CLAIM_BATCH = 20;
    private static final String SELECT_CANDIDATES = """
            SELECT id, url, depth FROM links
            WHERE status = 'NEW' AND depth <= ?
            ORDER BY id
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

    private static final String RELEASE_LINK = """
            UPDATE links SET status = 'NEW', claimed_by = NULL
            WHERE id = ? AND status = 'IN_PROGRESS'""";

    // Jedno zapytanie, żeby odpowiedź była spójna: dwa osobne zapytania mogłyby zobaczyć
    // bazę w dwóch różnych chwilach (np. link przeszedł z NEW do IN_PROGRESS pomiędzy nimi).
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

    /** Wiersz tabeli links w postaci potrzebnej do sprawdzania stanu bazy (m.in. w testach). */
    public record LinkRow(long id, String url, int depth, int seen, String status,
                          String claimedBy, String visitedAt, String error) {
    }

    private final String dbPath;
    private Connection connection;

    /** Samo utworzenie obiektu nie otwiera bazy - do tego służy {@link #connect()}. */
    public DBConn(String dbPath) {
        this.dbPath = dbPath;
    }

    /** Otwiera połączenie. Ponowne wywołanie przy otwartym połączeniu nic nie robi. */
    public synchronized void connect() {
        if (connection != null) {
            return;
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            // WAL: odczyty nie blokują zapisu. busy_timeout: przy chwilowej blokadzie pliku
            // SQLite czeka do 5 s, zamiast od razu zgłaszać błąd SQLITE_BUSY.
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            disconnect();
            throw new DatabaseException("Nie można otworzyć bazy: " + dbPath, e);
        }
    }

    /** Tworzy tabelę i indeks; bezpieczne przy ponownym wywołaniu dzięki IF NOT EXISTS. */
    public synchronized void createTables() {
        try {
            execute(CREATE_TABLE);
            execute(CREATE_INDEX);
        } catch (SQLException e) {
            throw new DatabaseException("Nie można utworzyć tabel", e);
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
     * Przejmuje najstarszy link NEW o głębokości do maxDepth i zapisuje, kto go wziął.
     * Link należy do wątku tylko wtedy, gdy warunkowy UPDATE zmienił wiersz; przy 0
     * (link właśnie przejęło inne połączenie) próbujemy kolejnego kandydata.
     */
    public synchronized Optional<LinkRecord> claimNextLink(String workerName, int maxDepth) {
        try {
            while (true) {
                List<LinkRecord> candidates = selectCandidates(maxDepth);
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

    /** Oddaje przejęty link (IN_PROGRESS → NEW), żeby mógł go wziąć inny wątek. */
    public synchronized void releaseLink(long id) {
        updateById(RELEASE_LINK, id, "Nie można zwolnić linku: ");
    }

    /**
     * Czy jest jeszcze praca: link w trakcie odwiedzania (może dodać nowe linki)
     * albo link NEW w dozwolonej głębokości.
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

    /** Usuwa tabelę (indeks znika razem z nią). */
    public synchronized void dropTables() {
        try {
            execute(DROP_TABLE);
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
     * Czyta kandydatów do listy i zamyka ResultSet PRZED jakimkolwiek UPDATE-em.
     * Otwarty SELECT trzyma transakcję odczytu na starym obrazie bazy; gdyby w tym czasie
     * inne połączenie coś zapisało, nasz UPDATE dostałby od razu SQLITE_BUSY
     * (busy_timeout tu nie pomaga, bo czekanie nie odświeży starego obrazu).
     */
    private List<LinkRecord> selectCandidates(int maxDepth) throws SQLException {
        List<LinkRecord> candidates = new ArrayList<>();
        try (PreparedStatement stmt = openConnection().prepareStatement(SELECT_CANDIDATES)) {
            stmt.setInt(1, maxDepth);
            stmt.setInt(2, CLAIM_BATCH);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new LinkRecord(
                            rs.getLong("id"), rs.getString("url"), rs.getInt("depth")));
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
        return new LinkRow(
                rs.getLong("id"),
                rs.getString("url"),
                rs.getInt("depth"),
                rs.getInt("seen"),
                rs.getString("status"),
                rs.getString("claimed_by"),
                rs.getString("visited_at"),
                rs.getString("error"));
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

    private Connection openConnection() {
        if (connection == null) {
            throw new IllegalStateException("Brak połączenia z bazą - najpierw wywołaj connect()");
        }
        return connection;
    }
}
