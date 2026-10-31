package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Sprawdza, że JUnit 5 jest poprawnie podpięty do builda. */
class MainTest {

    @Test
    void startMessageNamesTheProgram() {
        assertTrue(Main.startMessage().startsWith("Web Crawler"));
    }
}
