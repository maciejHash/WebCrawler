package pl.webcrawler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Cała obsługa bazy SQLite. Jedna instancja jest współdzielona przez wszystkie wątki,
 * dlatego metody publiczne są synchronized - w danej chwili z połączenia korzysta
 * tylko jeden wątek (obiekt Connection z JDBC nie jest bezpieczny wielowątkowo)
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

    // Wątki szukają linków po statusie i głębokości – indeks przyspiesza to wyszukiwanie.
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

    private static final String SELECT_BY_URL =
            "SELECT id, url, depth, seen, status FROM links WHERE url = ?";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS links";

    /** Wiersz tabeli links w postaci potrzebnej do sprawdzania stanu bazy (m.in. w testach). */
    public record LinkRow(long id, String url, int depth, int seen, String status) {
    }

    private final String dbPath;
    private Connection connection;

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

    /** Zwraca wiersz o podanym adresie albo pusty Optional, gdy takiego linku nie ma. */
    public synchronized Optional<LinkRow> findByUrl(String url) {
        try (PreparedStatement stmt = openConnection().prepareStatement(SELECT_BY_URL)) {
            stmt.setString(1, url);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkRow(
                        rs.getLong("id"),
                        rs.getString("url"),
                        rs.getInt("depth"),
                        rs.getInt("seen"),
                        rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Nie można odczytać linku: " + url, e);
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

    private Connection openConnection() {
        if (connection == null) {
            throw new IllegalStateException("Brak połączenia z bazą - najpierw wywołaj connect()");
        }
        return connection;
    }
}
