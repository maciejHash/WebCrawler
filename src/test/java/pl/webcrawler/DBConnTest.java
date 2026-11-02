package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Testy DBConn na prawdziwym pliku SQLite w katalogu tymczasowym. */
class DBConnTest {

    private static final String URL = "https://example.com/";

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
    void createTablesTwiceDoesNotThrowAndKeepsData() {
        db.insertRow(URL, 0);

        assertDoesNotThrow(db::createTables);
        assertTrue(db.findByUrl(URL).isPresent());
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
    void dropTablesRemovesTable() {
        db.insertRow(URL, 0);

        db.dropTables();

        // Brak tabeli → SQLite zgłasza błąd, który DBConn opakowuje w DatabaseException.
        assertThrows(DatabaseException.class, () -> db.findByUrl(URL));
        // Po ponownym utworzeniu tabela jest pusta.
        db.createTables();
        assertTrue(db.findByUrl(URL).isEmpty());
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
