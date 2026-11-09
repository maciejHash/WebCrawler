package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Testy DBConn na prawdziwym pliku SQLite w katalogu tymczasowym. */
class DBConnTest {

    private static final String URL = "https://example.com/";
    /** Dowolny "bieżący czas" dla claimNextLink - test sam decyduje, która jest godzina. */
    private static final long NOW = 1_700_000_000_000L;

    /** Tabela links w wersji schematu 1 (i sprzed wersjonowania) - bez attempts i retry_at. */
    private static final String SCHEMA_V1_TABLE = """
            CREATE TABLE links (
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
    private static final String SCHEMA_V1_INDEX =
            "CREATE INDEX idx_links_status_depth ON links (status, depth)";

    @TempDir
    Path tempDir;

    private DBConn db;

    @BeforeEach
    void setUp() {
        db = new DBConn(tempDir.resolve("test.db").toString());
        db.connect();
        db.createTables();
    }

    @AfterEach
    void tearDown() {
        db.disconnect();
    }

    @Test
    void addSeedTwiceKeepsSeenAtOne() {
        assertTrue(db.addSeed(URL), "pierwsze wywołanie dodaje wiersz");
        assertFalse(db.addSeed(URL), "drugie wywołanie niczego nie zmienia");

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals(0, row.depth());
        assertEquals(1, row.seen());
        assertEquals("NEW", row.status());
    }

    @Test
    void addSeedLeavesExistingLinkUntouched() {
        db.insertRow(URL, 0);
        db.insertRow(URL, 0);
        long id = db.claimNextLink("crawler-1", 5, NOW).orElseThrow().id();
        db.markVisited(id);

        assertFalse(db.addSeed(URL));

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals(2, row.seen());
        assertEquals("VISITED", row.status());
        assertEquals("crawler-1", row.claimedBy());
    }

    @Test
    void newLinkHasSeenOneAndStatusNew() {
        db.insertRow(URL, 2);

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals(URL, row.url());
        assertEquals(2, row.depth());
        assertEquals(1, row.seen());
        assertEquals("NEW", row.status());
    }

    @Test
    void sameLinkInsertedThreeTimesGivesOneRowWithSeenThree() {
        db.insertRow(URL, 1);
        long firstId = db.findByUrl(URL).orElseThrow().id();

        db.insertRow(URL, 1);
        db.insertRow(URL, 1);

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals(firstId, row.id(), "UPSERT nie może tworzyć nowego wiersza");
        assertEquals(3, row.seen());
    }

    @Test
    void smallerDepthOverridesLargerButNotTheOtherWay() {
        db.insertRow(URL, 3);
        db.insertRow(URL, 1);
        assertEquals(1, db.findByUrl(URL).orElseThrow().depth());

        db.insertRow(URL, 5);
        assertEquals(1, db.findByUrl(URL).orElseThrow().depth());
    }

    @Test
    void unknownUrlGivesEmptyResult() {
        assertTrue(db.findByUrl("https://example.com/missing").isEmpty());
    }

    @Test
    void disconnectTwiceIsSafe() {
        db.disconnect();
        assertDoesNotThrow(db::disconnect);
    }

    @Test
    void usingDatabaseWithoutConnectionFails() {
        db.disconnect();
        assertThrows(IllegalStateException.class, () -> db.insertRow(URL, 0));
    }

    // ---- wersje schematu ----

    @Test
    void newDatabaseGetsLatestSchemaVersion() {
        try (DBConn fresh = new DBConn(tempDir.resolve("new.db").toString())) {
            fresh.connect();
            assertEquals(0, fresh.schemaVersion(), "pusty plik nie ma jeszcze żadnej migracji");

            fresh.createTables();

            assertEquals(DBConn.LATEST_SCHEMA_VERSION, fresh.schemaVersion());
        }
    }

    @Test
    void databaseFromProgramWithoutVersioningIsUpgradedAndKeepsData() throws SQLException {
        // Tak bazę zostawiały wersje programu sprzed wersjonowania: tabela i indeks są,
        // ale user_version nikt nie ustawił, więc wynosi 0.
        Path file = tempDir.resolve("old.db");
        executeRaw(file,
                SCHEMA_V1_TABLE,
                SCHEMA_V1_INDEX,
                """
                INSERT INTO links (url, depth, seen, status, claimed_by)
                VALUES ('https://example.com/', 0, 3, 'VISITED', 'crawler-1')""",
                "INSERT INTO links (url, depth) VALUES ('https://example.com/new', 1)");

        try (DBConn old = new DBConn(file.toString())) {
            old.connect();
            assertEquals(0, old.schemaVersion());

            old.createTables();

            assertEquals(DBConn.LATEST_SCHEMA_VERSION, old.schemaVersion());
            assertEquals(Map.of("VISITED", 1, "NEW", 1), old.countByStatus());
            DBConn.LinkRow row = old.findByUrl("https://example.com/").orElseThrow();
            assertEquals(3, row.seen());
            assertEquals("crawler-1", row.claimedBy());
            // Po migracji robot może dalej pracować na tych danych.
            assertEquals("https://example.com/new", old.claimNextLink("w", 1, NOW).orElseThrow().url());
        }
    }

    @Test
    void databaseInVersionOneGetsRetryColumnsAndKeepsData() throws SQLException {
        // Baza z wersji programu, w której ostatnią migracją była 1 - bez kolumn do ponawiania.
        Path file = tempDir.resolve("v1.db");
        executeRaw(file,
                SCHEMA_V1_TABLE,
                SCHEMA_V1_INDEX,
                """
                INSERT INTO links (url, depth, seen, status, claimed_by, visited_at, error)
                VALUES ('https://example.com/', 0, 3, 'ERROR', 'crawler-1', '2026-11-01 10:00:00', 'HTTP 503')""",
                "INSERT INTO links (url, depth) VALUES ('https://example.com/new', 1)",
                "PRAGMA user_version = 1");

        try (DBConn old = new DBConn(file.toString())) {
            old.connect();
            assertEquals(1, old.schemaVersion());

            old.createTables();

            assertEquals(DBConn.LATEST_SCHEMA_VERSION, old.schemaVersion());
            assertEquals(Map.of("ERROR", 1, "NEW", 1), old.countByStatus());
            DBConn.LinkRow row = old.findByUrl("https://example.com/").orElseThrow();
            assertEquals(3, row.seen());
            assertEquals("crawler-1", row.claimedBy());
            assertEquals("2026-11-01 10:00:00", row.visitedAt());
            assertEquals("HTTP 503", row.error());
            // Istniejące wiersze: zero prób i brak terminu ponowienia - jak nowy link.
            assertEquals(0, row.attempts());
            assertNull(row.retryAt());
            // Link NEW sprzed migracji da się od razu przejąć (retry_at = NULL).
            LinkRecord claimed = old.claimNextLink("w", 1, NOW).orElseThrow();
            assertEquals("https://example.com/new", claimed.url());
            assertEquals(0, claimed.attempts());
        }
    }

    @Test
    void databaseInVersionTwoGetsEdgesWithoutInventingHistoricalEdges() throws SQLException {
        Path file = tempDir.resolve("v2.db");
        executeRaw(file, SCHEMA_V1_TABLE, SCHEMA_V1_INDEX,
                "ALTER TABLE links ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE links ADD COLUMN retry_at INTEGER",
                """
                INSERT INTO links (id, url, depth, seen, status, found_at, visited_at, attempts, retry_at, error)
                VALUES (7, 'https://example.com/', 0, 5, 'VISITED', '2026-11-01 09:00:00',
                        '2026-11-01 10:00:00', 2, 1234, 'stary opis')""",
                "INSERT INTO links (url, depth) VALUES ('https://example.com/new', 1)",
                "PRAGMA user_version = 2");
        try (DBConn old = new DBConn(file.toString())) {
            old.connect();
            old.createTables();
            old.createTables();
            assertEquals(3, old.schemaVersion());
            DBConn.LinkRow row = old.findByUrl(URL).orElseThrow();
            assertEquals(7, row.id());
            assertEquals(5, row.seen());
            assertEquals("VISITED", row.status());
            assertEquals("2026-11-01 09:00:00", row.foundAt());
            assertEquals("2026-11-01 10:00:00", row.visitedAt());
            assertEquals(2, row.attempts());
            assertEquals(1234L, row.retryAt());
            assertEquals("stary opis", row.error());
            assertEquals(0, old.forEachEdge(false, edge -> {}));
            LinkRecord page = old.claimNextLink("w", 1, NOW).orElseThrow();
            old.saveVisitedPage(page.id(), List.of(URL), 2);
            List<DBConn.EdgeRow> edges = new ArrayList<>();
            old.forEachEdge(false, edges::add);
            assertEquals(List.of(new DBConn.EdgeRow(page.id(), 7, URL + "new", URL, 1)), edges);
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement stmt = conn.createStatement()) {
            try (var rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'edges'")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("WITHOUT ROWID"));
            }
            try (var rs = stmt.executeQuery("PRAGMA index_info(idx_edges_to_id)")) {
                assertTrue(rs.next());
                assertEquals("to_id", rs.getString("name"));
            }
            stmt.execute("PRAGMA foreign_keys = ON");
            assertThrows(SQLException.class,
                    () -> stmt.execute("INSERT INTO edges (from_id, to_id) VALUES (999, 7)"));
        }
    }

    @Test
    void connectingToMissingExistingDatabaseNeverCreatesFile() {
        Path missing = tempDir.resolve("missing.db");
        try (DBConn other = new DBConn(missing.toString())) {
            assertThrows(DatabaseException.class, other::connectExisting);
        }
        assertFalse(Files.exists(missing));
    }

    @Test
    void createTablesTwiceKeepsDataAndVersion() {
        db.insertRow(URL, 0);

        assertDoesNotThrow(db::createTables);

        assertEquals(DBConn.LATEST_SCHEMA_VERSION, db.schemaVersion());
        assertEquals(1, db.findByUrl(URL).orElseThrow().seen());
    }

    @Test
    void databaseFromNewerProgramIsRejected() throws SQLException {
        Path file = tempDir.resolve("newer.db");
        int newer = DBConn.LATEST_SCHEMA_VERSION + 1;
        executeRaw(file, "PRAGMA user_version = " + newer);

        try (DBConn other = new DBConn(file.toString())) {
            other.connect();

            DatabaseException e = assertThrows(DatabaseException.class, other::createTables);

            assertTrue(e.getMessage().contains("nowszej wersji programu"), e.getMessage());
            assertEquals(newer, other.schemaVersion(), "wersja bez zmian");
            assertThrows(DatabaseException.class, other::countByStatus, "tabela nie powstała");
        }
    }

    @Test
    void dropTablesRemovesTableAndResetsVersion() {
        db.insertRow(URL, 0);
        db.saveVisitedPage(db.findByUrl(URL).orElseThrow().id(), List.of(URL), 1);

        db.dropTables();

        assertEquals(0, db.schemaVersion());
        // Brak tabeli → SQLite zgłasza błąd, który DBConn opakowuje w DatabaseException.
        assertThrows(DatabaseException.class, () -> db.findByUrl(URL));
        assertThrows(DatabaseException.class, () -> db.forEachEdge(false, edge -> {}));
        // Wersja 0, więc createTables() wykonuje migracje od nowa - tabela jest pusta.
        db.createTables();
        assertEquals(DBConn.LATEST_SCHEMA_VERSION, db.schemaVersion());
        assertTrue(db.findByUrl(URL).isEmpty());
        assertEquals(0, db.forEachEdge(false, edge -> {}));
    }

    // ---- przejmowanie linków ----

    @Test
    void claimPrefersOlderLinkAtEqualDepthAndStoresWorker() {
        db.insertRow(URL + "a", 0);
        db.insertRow(URL + "b", 0);

        LinkRecord claimed = db.claimNextLink("worker-1", 5, NOW).orElseThrow();

        assertEquals(URL + "a", claimed.url());
        assertEquals(0, claimed.depth());
        DBConn.LinkRow row = db.findByUrl(URL + "a").orElseThrow();
        assertEquals(claimed.id(), row.id());
        assertEquals("IN_PROGRESS", row.status());
        assertEquals("worker-1", row.claimedBy());
    }

    @Test
    void shallowerLinkAddedLaterIsClaimedBeforeOlderDeeperOne() {
        db.insertRow(URL + "deep", 2);
        db.insertRow(URL + "shallow", 1);
        // Przy ORDER BY id pierwszy byłby "deep" - ma mniejsze id.
        assertTrue(db.findByUrl(URL + "deep").orElseThrow().id()
                < db.findByUrl(URL + "shallow").orElseThrow().id());

        assertEquals(URL + "shallow", db.claimNextLink("w", 5, NOW).orElseThrow().url());
        assertEquals(URL + "deep", db.claimNextLink("w", 5, NOW).orElseThrow().url());
    }

    @Test
    void linkWhoseDepthDecreasedMovesUpTheQueue() {
        db.insertRow(URL + "older", 2);
        db.insertRow(URL + "later", 3);
        // "later" znaleziony krótszą drogą: UPSERT ustawia depth = MIN(3, 1) = 1,
        // więc wyprzedza "older", choć wcześniej był za nim (większe depth i id).
        db.insertRow(URL + "later", 1);

        LinkRecord first = db.claimNextLink("w", 5, NOW).orElseThrow();

        assertEquals(URL + "later", first.url());
        assertEquals(1, first.depth(), "wątek dostaje aktualną, zmniejszoną głębokość");
        assertEquals(URL + "older", db.claimNextLink("w", 5, NOW).orElseThrow().url());
    }

    @Test
    void claimedLinkIsNotGivenAgain() {
        db.insertRow(URL + "a", 0);
        db.insertRow(URL + "b", 0);

        LinkRecord first = db.claimNextLink("worker-1", 5, NOW).orElseThrow();
        LinkRecord second = db.claimNextLink("worker-2", 5, NOW).orElseThrow();

        assertNotEquals(first.id(), second.id());
        assertTrue(db.claimNextLink("worker-3", 5, NOW).isEmpty());
    }

    @Test
    void claimSkipsLinksDeeperThanMaxDepth() {
        db.insertRow(URL + "deep", 3);
        db.insertRow(URL + "shallow", 2);

        assertEquals(URL + "shallow", db.claimNextLink("w", 2, NOW).orElseThrow().url());
        assertTrue(db.claimNextLink("w", 2, NOW).isEmpty());
        assertEquals("NEW", db.findByUrl(URL + "deep").orElseThrow().status());
    }

    @Test
    void claimOnEmptyTableGivesEmptyResult() {
        assertTrue(db.claimNextLink("w", 10, NOW).isEmpty());
    }

    @Test
    void claimLooksPastFirstBatchOfCandidates() {
        // Więcej linków niż jedna paczka kandydatów (20) - wszystkie muszą dać się przejąć.
        for (int i = 0; i < 45; i++) {
            db.insertRow(URL + i, 0);
        }
        int claimed = 0;
        while (db.claimNextLink("w", 0, NOW).isPresent()) {
            claimed++;
        }
        assertEquals(45, claimed);
    }

    // ---- zmiany statusu ----

    @Test
    void markVisitedSetsStatusAndTime() {
        LinkRecord link = insertAndClaim(URL);

        db.markVisited(link.id());

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("VISITED", row.status());
        assertNotNull(row.visitedAt());
        assertNull(row.error());
        assertEquals("w", row.claimedBy(), "po odwiedzeniu zostaje informacja, kto to zrobił");
    }

    // ---- zapis odwiedzonej strony w jednej transakcji ----

    @Test
    void saveVisitedPageStoresLinksAndMarksPageVisited() {
        LinkRecord page = insertAndClaim(URL);

        db.saveVisitedPage(page.id(), List.of(URL + "a", URL + "b", URL + "c"), 1);

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("VISITED", row.status());
        assertNotNull(row.visitedAt());
        assertEquals("w", row.claimedBy());
        for (String url : List.of(URL + "a", URL + "b", URL + "c")) {
            DBConn.LinkRow link = db.findByUrl(url).orElseThrow();
            assertEquals(1, link.depth(), url);
            assertEquals(1, link.seen(), url);
            assertEquals("NEW", link.status(), url);
        }
        assertEquals(Map.of("VISITED", 1, "NEW", 3), db.countByStatus());
    }

    @Test
    void saveVisitedPageCountsDuplicatesAndKeepsSmallerDepth() {
        db.insertRow(URL + "known", 3);
        LinkRecord page = insertAndClaim(URL);

        // Ten sam adres dwa razy na stronie + jeden znany już z bazy z większą głębokością.
        db.saveVisitedPage(page.id(), List.of(URL + "twice", URL + "known", URL + "twice"), 1);

        DBConn.LinkRow twice = db.findByUrl(URL + "twice").orElseThrow();
        assertEquals(2, twice.seen());
        assertEquals(1, twice.depth());
        DBConn.LinkRow known = db.findByUrl(URL + "known").orElseThrow();
        assertEquals(2, known.seen());
        assertEquals(1, known.depth(), "depth = MIN(3, 1)");
    }

    @Test
    void saveVisitedPageStoresDirectedEdgesCountsAndSelfLinks() {
        LinkRecord page = insertAndClaim(URL);
        db.saveVisitedPage(page.id(), List.of(URL, URL + "a", URL + "a"), 1);
        long other = db.findByUrl(URL + "a").orElseThrow().id();
        db.saveVisitedPage(other, List.of(URL), 2);
        List<DBConn.EdgeRow> edges = new ArrayList<>();
        assertEquals(3, db.forEachEdge(false, edges::add));
        assertEquals(List.of(
                new DBConn.EdgeRow(page.id(), page.id(), URL, URL, 1),
                new DBConn.EdgeRow(page.id(), other, URL, URL + "a", 2),
                new DBConn.EdgeRow(other, page.id(), URL + "a", URL, 1)), edges);
    }

    @Test
    void edgeFailureRollsBackLinksCountsEdgesAndPageStatus() throws SQLException {
        LinkRecord page = insertAndClaim(URL);
        db.saveVisitedPage(page.id(), List.of(URL + "known"), 1);
        db.releaseLink(db.claimNextLink("w", 1, NOW).orElseThrow().id());
        long source = db.findByUrl(URL + "known").orElseThrow().id();
        // Pierwsza krawędź przejdzie, druga wywoła błąd już po UPSERT-ach wszystkich linków.
        executeRaw(tempDir.resolve("test.db"), """
                CREATE TRIGGER fail_edge BEFORE INSERT ON edges
                WHEN NEW.to_id = (SELECT id FROM links WHERE url = 'https://example.com/fail')
                BEGIN SELECT RAISE(ABORT, 'test krawędzi'); END""");
        db.claimNextLink("w", 1, NOW).orElseThrow();
        assertThrows(DatabaseException.class,
                () -> db.saveVisitedPage(source, List.of(URL, URL + "fail"), 2));
        assertTrue(db.findByUrl(URL + "fail").isEmpty());
        assertEquals(1, db.findByUrl(URL).orElseThrow().seen());
        assertEquals("IN_PROGRESS", db.findByUrl(URL + "known").orElseThrow().status());
        List<DBConn.EdgeRow> edges = new ArrayList<>();
        db.forEachEdge(false, edges::add);
        assertEquals(List.of(new DBConn.EdgeRow(page.id(), source, URL, URL + "known", 1)), edges);
    }

    @Test
    void mostLinkedCountsSourcesRatherThanOccurrencesAndRespectsLimitAndTies() {
        db.addSeed(URL);
        long a = db.findByUrl(URL).orElseThrow().id();
        db.saveVisitedPage(a, List.of(URL + "b", URL + "b", URL + "b", URL + "c", URL + "d"), 1);
        long b = db.findByUrl(URL + "b").orElseThrow().id();
        db.saveVisitedPage(b, List.of(URL + "c"), 2);
        assertEquals(List.of(new DBConn.IncomingLinks(URL + "c", 2),
                new DBConn.IncomingLinks(URL + "b", 1)), db.findMostLinked(2));
        assertTrue(db.findMostLinked(0).isEmpty());
    }

    @Test
    void saveVisitedPageWithoutLinksOnlyMarksPageVisited() {
        LinkRecord page = insertAndClaim(URL);

        db.saveVisitedPage(page.id(), List.of(), 1);

        assertEquals(Map.of("VISITED", 1), db.countByStatus());
    }

    @Test
    void failedSaveVisitedPageRollsBackLinksAndStatus() {
        db.insertRow(URL + "known", 1);
        LinkRecord page = insertAndClaim(URL);
        // null łamie NOT NULL na kolumnie url - błąd pojawia się po dwóch poprawnych linkach.
        List<String> links = Arrays.asList(URL + "new", URL + "known", null);

        assertThrows(DatabaseException.class, () -> db.saveVisitedPage(page.id(), links, 1));

        assertTrue(db.findByUrl(URL + "new").isEmpty(), "nowy link nie może zostać w bazie");
        assertEquals(1, db.findByUrl(URL + "known").orElseThrow().seen(), "seen bez zmian");
        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("IN_PROGRESS", row.status());
        assertNull(row.visitedAt());
    }

    @Test
    void connectionWorksInAutoCommitModeAfterFailedSave() {
        LinkRecord page = insertAndClaim(URL);
        List<String> links = Arrays.asList(URL + "a", null);
        assertThrows(DatabaseException.class, () -> db.saveVisitedPage(page.id(), links, 1));

        db.insertRow(URL + "after", 0);
        db.markVisited(page.id());

        // Inne połączenie widzi tylko to, co zatwierdzono. Gdyby autoCommit został wyłączony,
        // te zapisy wisiałyby w otwartej transakcji i nie byłoby ich tutaj widać.
        try (DBConn other = new DBConn(tempDir.resolve("test.db").toString())) {
            other.connect();
            assertTrue(other.findByUrl(URL + "after").isPresent());
            assertEquals("VISITED", other.findByUrl(URL).orElseThrow().status());
        }
    }

    @Test
    void markErrorStoresMessage() {
        LinkRecord link = insertAndClaim(URL);

        db.markError(link.id(), "HTTP 404");

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("ERROR", row.status());
        assertEquals("HTTP 404", row.error());
        assertNotNull(row.visitedAt());
    }

    @Test
    void markSkippedSetsStatus() {
        LinkRecord link = insertAndClaim(URL);

        db.markSkipped(link.id());

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("SKIPPED", row.status());
        assertNotNull(row.visitedAt());
        assertNull(row.error());
    }

    @Test
    void markSkippedWithReasonStoresReasonInErrorColumn() {
        LinkRecord link = insertAndClaim(URL);

        db.markSkipped(link.id(), "robots.txt");

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("SKIPPED", row.status());
        assertEquals("robots.txt", row.error());
        assertNotNull(row.visitedAt());
        // Pominięty link nie jest pracą do wykonania ani nie był pobrany przez wątek.
        assertFalse(db.hasPendingWork(0));
        assertTrue(db.countPagesByWorker().isEmpty());
    }

    @Test
    void releasedLinkCanBeClaimedAgain() {
        LinkRecord link = insertAndClaim(URL);

        db.releaseLink(link.id());

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("NEW", row.status());
        assertNull(row.claimedBy());
        assertEquals(link.id(), db.claimNextLink("other", 0, NOW).orElseThrow().id());
    }

    @Test
    void releaseDoesNotTouchFinishedLink() {
        LinkRecord link = insertAndClaim(URL);
        db.markVisited(link.id());

        db.releaseLink(link.id());

        assertEquals("VISITED", db.findByUrl(URL).orElseThrow().status());
    }

    // ---- ponawianie ----

    @Test
    void newLinkHasNoAttemptsAndNoRetryTime() {
        db.insertRow(URL, 0);

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals(0, row.attempts());
        assertNull(row.retryAt());
    }

    @Test
    void scheduleRetryReturnsLinkToNewWithAttemptErrorAndTime() {
        LinkRecord link = insertAndClaim(URL);

        db.scheduleRetry(link.id(), "HTTP 503", NOW + 1_000);

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("NEW", row.status());
        assertNull(row.claimedBy(), "link nie należy już do żadnego wątku");
        assertEquals(1, row.attempts());
        assertEquals("HTTP 503", row.error());
        assertEquals(NOW + 1_000, row.retryAt());
        assertNull(row.visitedAt(), "to nie jest status końcowy");

        // Kolejna nieudana próba: licznik rośnie, błąd i termin są nadpisywane.
        LinkRecord retried = db.claimNextLink("w", 0, NOW + 1_000).orElseThrow();
        db.scheduleRetry(retried.id(), "Przekroczono czas oczekiwania (2000 ms)", NOW + 3_000);

        row = db.findByUrl(URL).orElseThrow();
        assertEquals(2, row.attempts());
        assertEquals("Przekroczono czas oczekiwania (2000 ms)", row.error());
        assertEquals(NOW + 3_000, row.retryAt());
    }

    @Test
    void scheduleRetryChangesOnlyLinkInProgress() {
        LinkRecord visited = insertAndClaim(URL + "visited");
        db.markVisited(visited.id());
        db.insertRow(URL + "new", 0);
        long newId = db.findByUrl(URL + "new").orElseThrow().id();

        db.scheduleRetry(visited.id(), "HTTP 503", NOW + 1_000);
        db.scheduleRetry(newId, "HTTP 503", NOW + 1_000);

        DBConn.LinkRow visitedRow = db.findByUrl(URL + "visited").orElseThrow();
        assertEquals("VISITED", visitedRow.status());
        assertEquals("w", visitedRow.claimedBy());
        assertEquals(0, visitedRow.attempts());
        assertNull(visitedRow.retryAt());
        assertNull(visitedRow.error());
        DBConn.LinkRow newRow = db.findByUrl(URL + "new").orElseThrow();
        assertEquals(0, newRow.attempts());
        assertNull(newRow.retryAt());
    }

    @Test
    void linkWaitingForRetryIsClaimedOnlyFromRetryTime() {
        LinkRecord link = insertAndClaim(URL);
        db.scheduleRetry(link.id(), "HTTP 503", NOW + 1_000);

        assertTrue(db.claimNextLink("w", 0, NOW).isEmpty());
        assertTrue(db.claimNextLink("w", 0, NOW + 999).isEmpty(), "milisekundę przed terminem");
        assertEquals("NEW", db.findByUrl(URL).orElseThrow().status());

        // retry_at <= nowMs: dokładnie w terminie link już wolno przejąć.
        LinkRecord retried = db.claimNextLink("worker-2", 0, NOW + 1_000).orElseThrow();

        assertEquals(link.id(), retried.id());
        assertEquals(1, retried.attempts(), "wątek dostaje liczbę wcześniejszych prób");
        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("IN_PROGRESS", row.status());
        assertEquals("worker-2", row.claimedBy());
    }

    @Test
    void linksWaitingForRetryDoNotBlockReadyLinks() {
        // Więcej czekających niż jedna paczka kandydatów (20), wszystkie płytsze niż gotowy link.
        // Warunek retry_at jest w SQL, więc LIMIT liczy tylko linki, które wolno przejąć.
        for (int i = 0; i < 25; i++) {
            LinkRecord waiting = insertAndClaim(URL + "waiting" + i);
            db.scheduleRetry(waiting.id(), "HTTP 503", NOW + 1_000);
        }
        db.insertRow(URL + "ready", 1);

        assertEquals(URL + "ready", db.claimNextLink("w", 1, NOW).orElseThrow().url());
        assertTrue(db.claimNextLink("w", 1, NOW).isEmpty());
    }

    @Test
    void linkWaitingForRetryIsPendingWork() {
        LinkRecord link = insertAndClaim(URL);
        db.scheduleRetry(link.id(), "HTTP 503", NOW + 60_000);

        assertTrue(db.claimNextLink("w", 0, NOW).isEmpty(), "termin jeszcze nie minął");
        assertTrue(db.hasPendingWork(0), "wątki mają czekać na ponowienie, a nie kończyć pracę");
    }

    @Test
    void linkWaitingForRetrySurvivesRestart() {
        LinkRecord waiting = insertAndClaim(URL + "waiting");
        db.scheduleRetry(waiting.id(), "HTTP 503", NOW + 1_000);
        // Drugi link przerwany w trakcie pobierania - resetInProgress ma zmienić tylko jego.
        insertAndClaim(URL + "interrupted");
        db.disconnect();

        // Następne uruchomienie przygotowuje bazę tak jak Crawler.prepareDatabase.
        try (DBConn restarted = new DBConn(tempDir.resolve("test.db").toString())) {
            restarted.connect();
            restarted.createTables();
            assertEquals(1, restarted.resetInProgress());

            DBConn.LinkRow row = restarted.findByUrl(URL + "waiting").orElseThrow();
            assertEquals("NEW", row.status());
            assertEquals(1, row.attempts());
            assertEquals(NOW + 1_000, row.retryAt());
            assertEquals("HTTP 503", row.error());

            // Termin ponowienia nadal obowiązuje: przed nim dostępny jest tylko przerwany link.
            assertEquals(URL + "interrupted", restarted.claimNextLink("w", 0, NOW).orElseThrow().url());
            assertTrue(restarted.claimNextLink("w", 0, NOW).isEmpty());
            LinkRecord retried = restarted.claimNextLink("w", 0, NOW + 1_000).orElseThrow();
            assertEquals(URL + "waiting", retried.url());
            assertEquals(1, retried.attempts());
        }
    }

    // ---- stan pracy ----

    @Test
    void noPendingWorkInEmptyTable() {
        assertFalse(db.hasPendingWork(5));
    }

    @Test
    void newLinkWithinDepthIsPendingWork() {
        db.insertRow(URL, 2);

        assertTrue(db.hasPendingWork(2));
    }

    @Test
    void newLinkTooDeepIsNotPendingWork() {
        db.insertRow(URL, 3);

        assertFalse(db.hasPendingWork(2));
    }

    @Test
    void linkInProgressIsPendingWorkRegardlessOfDepth() {
        // Wątek, który go przetwarza, może jeszcze dopisać nowe linki do bazy.
        db.insertRow(URL, 3);
        db.claimNextLink("w", 3, NOW).orElseThrow();

        assertTrue(db.hasPendingWork(0));
    }

    @Test
    void finishedLinksAreNotPendingWork() {
        db.markVisited(insertAndClaim(URL + "a").id());
        db.markError(insertAndClaim(URL + "b").id(), "x");
        db.markSkipped(insertAndClaim(URL + "c").id());

        assertFalse(db.hasPendingWork(5));
    }

    @Test
    void resetInProgressReturnsLinksToNew() {
        insertAndClaim(URL + "a");
        insertAndClaim(URL + "b");
        db.markVisited(insertAndClaim(URL + "c").id());

        assertEquals(2, db.resetInProgress());

        assertEquals("NEW", db.findByUrl(URL + "a").orElseThrow().status());
        assertNull(db.findByUrl(URL + "a").orElseThrow().claimedBy());
        assertEquals("VISITED", db.findByUrl(URL + "c").orElseThrow().status());
        assertEquals(0, db.resetInProgress());
    }

    @Test
    void countByStatusCountsEachStatus() {
        assertEquals(Map.of(), db.countByStatus());

        db.markVisited(insertAndClaim(URL + "a").id());
        db.markVisited(insertAndClaim(URL + "b").id());
        db.markError(insertAndClaim(URL + "c").id(), "x");
        insertAndClaim(URL + "d");
        db.insertRow(URL + "e", 0);

        assertEquals(Map.of("VISITED", 2, "ERROR", 1, "IN_PROGRESS", 1, "NEW", 1),
                db.countByStatus());
    }

    @Test
    void mostSeenIsOrderedBySeenThenByIdAndLimited() {
        db.insertRow(URL + "once", 0);
        db.insertRow(URL + "tie-first", 0);
        db.insertRow(URL + "tie-second", 0);
        db.insertRow(URL + "top", 0);
        for (int i = 0; i < 4; i++) {
            db.insertRow(URL + "top", 1);
        }
        db.insertRow(URL + "tie-first", 1);
        db.insertRow(URL + "tie-second", 1);

        List<DBConn.LinkRow> rows = db.findMostSeen(3);

        assertEquals(List.of(URL + "top", URL + "tie-first", URL + "tie-second"),
                rows.stream().map(DBConn.LinkRow::url).toList());
        assertEquals(5, rows.get(0).seen());
        assertEquals(2, rows.get(1).seen());
    }

    @Test
    void mostSeenOnEmptyTableIsEmpty() {
        assertTrue(db.findMostSeen(10).isEmpty());
    }

    @Test
    void pagesByWorkerCountsOnlyFetchedPagesInNaturalOrder() {
        db.markVisited(claim(URL + "a", "crawler-10"));
        db.markVisited(claim(URL + "b", "crawler-2"));
        db.markError(claim(URL + "c", "crawler-2"), "HTTP 404");
        // Nie liczą się: SKIPPED (bez żądania HTTP), IN_PROGRESS (niedokończony), NEW.
        db.markSkipped(claim(URL + "d", "crawler-2"));
        claim(URL + "e", "crawler-3");
        db.insertRow(URL + "f", 0);

        Map<String, Integer> counts = db.countPagesByWorker();

        assertEquals(Map.of("crawler-2", 2, "crawler-10", 1), counts);
        // crawler-2 przed crawler-10, mimo że alfabetycznie byłoby odwrotnie.
        assertEquals(List.of("crawler-2", "crawler-10"), List.copyOf(counts.keySet()));
    }

    /** Wstawia link i przejmuje go jako podany wątek; zwraca id. */
    private long claim(String url, String worker) {
        db.insertRow(url, 0);
        LinkRecord link = db.claimNextLink(worker, 0, NOW).orElseThrow();
        assertEquals(url, link.url());
        return link.id();
    }

    /** Wstawia link i od razu go przejmuje (w tabeli nie może być innych linków NEW). */
    private LinkRecord insertAndClaim(String url) {
        db.insertRow(url, 0);
        LinkRecord link = db.claimNextLink("w", 0, NOW).orElseThrow();
        assertEquals(url, link.url());
        return link;
    }

    /** Wykonuje SQL przez zwykłe połączenie JDBC z pominięciem DBConn - jak inna wersja programu. */
    private static void executeRaw(Path file, String... statements) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    @Test
    void worksWithTryWithResources() {
        String path = tempDir.resolve("other.db").toString();
        try (DBConn other = new DBConn(path)) {
            other.connect();
            other.createTables();
            other.insertRow(URL, 0);
        }
        // Dane zostały zapisane w pliku - nowe połączenie je widzi
        try (DBConn reopened = new DBConn(path)) {
            reopened.connect();
            assertEquals(1, reopened.findByUrl(URL).orElseThrow().seen());
        }
    }
}
