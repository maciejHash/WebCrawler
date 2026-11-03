package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Odczyt wersji z zasobu wypełnianego przez Mavena. */
class AppVersionTest {

    @Test
    void versionComesFromFilteredResource() {
        // Testy uruchamia Maven, więc plik musi być już przefiltrowany.
        String version = AppVersion.get();

        assertNotEquals(AppVersion.FALLBACK, version);
        assertFalse(version.contains("${"), version);
        assertFalse(version.isBlank());
    }

    @Test
    void missingResourceGivesFallback() {
        assertEquals(AppVersion.FALLBACK, AppVersion.load("/nie-ma-takiego-pliku.properties"));
    }

    @Test
    void unfilteredPlaceholderGivesFallback() {
        assertEquals(AppVersion.FALLBACK, AppVersion.load("/unfiltered-version.properties"));
    }
}
