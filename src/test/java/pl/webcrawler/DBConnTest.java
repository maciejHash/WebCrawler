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
import java.util.Map;

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
        long id = db.claimNextLink("crawler-1", 5).orElseThrow().id();
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

    // ---- przejmowanie linków ----

    @Test
    void claimTakesOldestNewLinkAndStoresWorker() {
        db.insertRow(URL + "a", 0);
        db.insertRow(URL + "b", 0);

        LinkRecord claimed = db.claimNextLink("worker-1", 5).orElseThrow();

        assertEquals(URL + "a", claimed.url());
        assertEquals(0, claimed.depth());
        DBConn.LinkRow row = db.findByUrl(URL + "a").orElseThrow();
        assertEquals(claimed.id(), row.id());
        assertEquals("IN_PROGRESS", row.status());
        assertEquals("worker-1", row.claimedBy());
    }

    @Test
    void claimedLinkIsNotGivenAgain() {
        db.insertRow(URL + "a", 0);
        db.insertRow(URL + "b", 0);

        LinkRecord first = db.claimNextLink("worker-1", 5).orElseThrow();
        LinkRecord second = db.claimNextLink("worker-2", 5).orElseThrow();

        assertNotEquals(first.id(), second.id());
        assertTrue(db.claimNextLink("worker-3", 5).isEmpty());
    }

    @Test
    void claimSkipsLinksDeeperThanMaxDepth() {
        db.insertRow(URL + "deep", 3);
        db.insertRow(URL + "shallow", 2);

        assertEquals(URL + "shallow", db.claimNextLink("w", 2).orElseThrow().url());
        assertTrue(db.claimNextLink("w", 2).isEmpty());
        assertEquals("NEW", db.findByUrl(URL + "deep").orElseThrow().status());
    }

    @Test
    void claimOnEmptyTableGivesEmptyResult() {
        assertTrue(db.claimNextLink("w", 10).isEmpty());
    }

    @Test
    void claimLooksPastFirstBatchOfCandidates() {
        // Więcej linków niż jedna paczka kandydatów (20) - wszystkie muszą dać się przejąć.
        for (int i = 0; i < 45; i++) {
            db.insertRow(URL + i, 0);
        }
        int claimed = 0;
        while (db.claimNextLink("w", 0).isPresent()) {
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
    }

    @Test
    void releasedLinkCanBeClaimedAgain() {
        LinkRecord link = insertAndClaim(URL);

        db.releaseLink(link.id());

        DBConn.LinkRow row = db.findByUrl(URL).orElseThrow();
        assertEquals("NEW", row.status());
        assertNull(row.claimedBy());
        assertEquals(link.id(), db.claimNextLink("other", 0).orElseThrow().id());
    }

    @Test
    void releaseDoesNotTouchFinishedLink() {
        LinkRecord link = insertAndClaim(URL);
        db.markVisited(link.id());

        db.releaseLink(link.id());

        assertEquals("VISITED", db.findByUrl(URL).orElseThrow().status());
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
        db.claimNextLink("w", 3).orElseThrow();

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

    /** Wstawia link i od razu go przejmuje (w tabeli nie może być innych linków NEW). */
    private LinkRecord insertAndClaim(String url) {
        db.insertRow(url, 0);
        LinkRecord link = db.claimNextLink("w", 0).orElseThrow();
        assertEquals(url, link.url());
        return link;
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
