package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsEveryColumnWithRfc4180QuotingAndUtf8() throws Exception {
        Path file = tempDir.resolve("test.db");
        try (DBConn db = new DBConn(file.toString())) {
            db.connect();
            db.createTables();
            String from = "https://example.com/a,b";
            String to = "https://example.com/c\"d";
            db.addSeed(from);
            long id = db.findByUrl(from).orElseThrow().id();
            db.saveVisitedPage(id, List.of(to, to), 1);
            // Opisy błędów mogą zawierać znaki, których nie ma w znormalizowanym URL-u.
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + file);
                 var stmt = conn.prepareStatement("""
                         UPDATE links SET claimed_by = ?, found_at = ?, visited_at = ?, error = ?,
                                          attempts = 2, retry_at = 1234 WHERE id = ?""")) {
                stmt.setString(1, "wątek\"1");
                stmt.setString(2, "czas znalezienia");
                stmt.setString(3, "czas wizyty");
                stmt.setString(4, "Błąd, \"opis\"\r\nciąg dalszy\nkoniec\r");
                stmt.setLong(5, id);
                stmt.executeUpdate();
            }
            Exporter.Result result = new Exporter(db).export(tempDir.resolve("out/nested"));
            assertEquals(2, result.links());
            assertEquals(1, result.edges());
            String links = Files.readString(result.linksFile());
            assertTrue(links.startsWith("id,url,depth,seen,status,claimed_by,found_at,visited_at,error,attempts,retry_at\r\n"));
            assertTrue(links.contains(id + ",\"https://example.com/a,b\",0,1,VISITED,\"wątek\"\"1\","
                    + "czas znalezienia,czas wizyty,\"Błąd, \"\"opis\"\"\r\nciąg dalszy\nkoniec\r\",2,1234\r\n"), links);
            assertTrue(links.contains(",\"https://example.com/c\"\"d\",1,2,NEW,,"), links);
            assertTrue(links.endsWith(",,,0,\r\n"), links);
            assertEquals("from_url,to_url,count\r\n\"https://example.com/a,b\",\"https://example.com/c\"\"d\",2\r\n",
                    Files.readString(result.edgesFile()));
        }
    }

    @Test
    void dotIncludesOnlyVisitedNodesAndEdgesAndEscapesLabels() throws IOException {
        try (DBConn db = new DBConn(tempDir.resolve("dot.db").toString())) {
            db.connect();
            db.createTables();
            // Surowe wartości badają escapowanie także dla danych pochodzących spoza robota.
            String a = "https://example.com/zażółć\"\\\n\r";
            String b = "https://example.com/b";
            String isolated = "https://example.com/isolated";
            db.addSeed(a);
            long aId = db.findByUrl(a).orElseThrow().id();
            db.saveVisitedPage(aId, List.of(a, b, b, "new", "error", "skipped", "in-progress"), 1);
            long bId = db.findByUrl(b).orElseThrow().id();
            db.saveVisitedPage(bId, List.of(a), 2);
            db.insertRow(isolated, 0);
            long isolatedId = db.findByUrl(isolated).orElseThrow().id();
            db.markVisited(isolatedId);
            db.markError(db.findByUrl("error").orElseThrow().id(), "błąd");
            db.markSkipped(db.findByUrl("skipped").orElseThrow().id());
            db.claimNextLink("worker", 1, 0).orElseThrow();
            Exporter.Result result = new Exporter(db).export(tempDir.resolve("out"));
            assertEquals(3, result.nodes());
            assertEquals(3, result.graphEdges());
            String expected = "digraph links {\n"
                    + "  n" + aId + " [label=\"https://example.com/zażółć\\\"\\\\\\n\\r\"];\n"
                    + "  n" + bId + " [label=\"" + b + "\"];\n"
                    + "  n" + isolatedId + " [label=\"" + isolated + "\"];\n"
                    + "  n" + aId + " -> n" + aId + " [label=\"1\"];\n"
                    + "  n" + aId + " -> n" + bId + " [label=\"2\"];\n"
                    + "  n" + bId + " -> n" + aId + " [label=\"1\"];\n}\n";
            assertEquals(expected, Files.readString(result.graphFile()));
            assertEquals(7, result.edges());
            assertFalse(Files.readString(result.graphFile()).contains("skipped"));
        }
    }

    @Test
    void emptyDatabaseExportsHeadersAndEmptyGraphAndOverwritesFiles() throws IOException {
        try (DBConn db = new DBConn(tempDir.resolve("empty.db").toString())) {
            db.connect();
            db.createTables();
            Path directory = tempDir.resolve("out");
            Files.createDirectories(directory);
            for (String name : List.of("links.csv", "edges.csv", "graph.dot")) {
                Files.writeString(directory.resolve(name), "stara zawartość, która ma zniknąć");
            }
            Exporter.Result result = new Exporter(db).export(directory);
            assertEquals(0, result.links());
            assertEquals(0, result.edges());
            assertEquals(0, result.nodes());
            assertEquals(0, result.graphEdges());
            assertEquals("id,url,depth,seen,status,claimed_by,found_at,visited_at,error,attempts,retry_at\r\n",
                    Files.readString(result.linksFile()));
            assertEquals("from_url,to_url,count\r\n", Files.readString(result.edgesFile()));
            assertEquals("digraph links {\n}\n", Files.readString(result.graphFile()));
        }
    }
}
