package pl.webcrawler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Eksport strumieniowy: w pamięci znajduje się tylko aktualnie zapisywany wiersz. */
public class Exporter {

    private final DBConn db;

    public Exporter(DBConn db) {
        this.db = db;
    }

    public record Result(Path linksFile, long links, Path edgesFile, long edges,
                         Path graphFile, long nodes, long graphEdges) { }

    /** Tworzy katalog i nadpisuje trzy pliki w UTF-8. NULL w CSV oznacza puste pole. */
    public Result export(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path linksFile = directory.resolve("links.csv").toAbsolutePath();
        Path edgesFile = directory.resolve("edges.csv").toAbsolutePath();
        Path graphFile = directory.resolve("graph.dot").toAbsolutePath();
        long links;
        long edges;
        long nodes;
        long graphEdges;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(linksFile, StandardCharsets.UTF_8)) {
                csvRow(writer, "id", "url", "depth", "seen", "status", "claimed_by", "found_at",
                        "visited_at", "error", "attempts", "retry_at");
                links = db.forEachLink(row -> csvRow(writer, row.id(), row.url(), row.depth(), row.seen(),
                        row.status(), row.claimedBy(), row.foundAt(), row.visitedAt(), row.error(),
                        row.attempts(), row.retryAt()));
            }
            try (BufferedWriter writer = Files.newBufferedWriter(edgesFile, StandardCharsets.UTF_8)) {
                csvRow(writer, "from_url", "to_url", "count");
                edges = db.forEachEdge(false, row -> csvRow(writer, row.fromUrl(), row.toUrl(), row.count()));
            }
            try (BufferedWriter writer = Files.newBufferedWriter(graphFile, StandardCharsets.UTF_8)) {
                write(writer, "digraph links {\n");
                nodes = db.forEachVisitedLink(row -> write(writer,
                        "  n" + row.id() + " [label=\"" + dotLabel(row.url()) + "\"];\n"));
                graphEdges = db.forEachEdge(true, row -> write(writer,
                        "  n" + row.fromId() + " -> n" + row.toId() + " [label=\"" + row.count() + "\"];\n"));
                write(writer, "}\n");
            }
        } catch (UncheckedIOException e) {
            // Consumer nie deklaruje IOException; na granicy eksportera przywracamy zwykły wyjątek I/O.
            throw e.getCause();
        }
        return new Result(linksFile, links, edgesFile, edges, graphFile, nodes, graphEdges);
    }

    private static void csvRow(BufferedWriter writer, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                write(writer, ",");
            }
            String value = values[i] == null ? "" : values[i].toString();
            if (value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")) {
                value = "\"" + value.replace("\"", "\"\"") + "\"";
            }
            write(writer, value);
        }
        // RFC 4180 wymaga CRLF niezależnie od systemu, na którym działa program.
        write(writer, "\r\n");
    }

    private static String dotLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void write(BufferedWriter writer, String value) {
        try {
            writer.write(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
