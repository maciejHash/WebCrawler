package pl.webcrawler;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Wersja programu. Jedynym źródłem numeru jest {@code <version>} w pom.xml - Maven przy
 * budowaniu wstawia ją do zasobu {@value #RESOURCE} (filtrowanie zasobów). Zasób zamiast
 * manifestu JAR-a, bo jest dostępny także w testach i w {@code mvn exec:java}, gdzie JAR-a nie ma.
 */
public final class AppVersion {

    /** Wersja, gdy pliku nie ma albo nie przeszedł przez Mavena (np. kompilacja samym IDE). */
    static final String FALLBACK = "dev";

    static final String RESOURCE = "/webcrawler-version.properties";

    /** Wczytana raz, przy pierwszym użyciu klasy - wersja w trakcie działania się nie zmienia. */
    private static final String VERSION = load(RESOURCE);

    private AppVersion() {
    }

    /** Wersja programu, np. {@code 1.1.0-SNAPSHOT}, albo {@value #FALLBACK}. */
    public static String get() {
        return VERSION;
    }

    /** Parametr resource tylko po to, żeby test mógł sprawdzić wartość zapasową dla brakującego pliku. */
    static String load(String resource) {
        try (InputStream in = AppVersion.class.getResourceAsStream(resource)) {
            if (in == null) {
                return FALLBACK;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            // Niefiltrowany plik zawiera dosłownie ${project.version} - to nie jest wersja.
            if (version == null || version.isBlank() || version.contains("${")) {
                return FALLBACK;
            }
            return version.trim();
        } catch (IOException e) {
            // Brak wersji nie może zatrzymać robota - to tylko informacja.
            return FALLBACK;
        }
    }
}
