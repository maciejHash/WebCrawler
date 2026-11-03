package pl.webcrawler;

/**
 * Błąd bazy danych. Niekontrolowany, żeby kod robota nie musiał deklarować
 * SQLException w każdej metodzie - i tak nie da się go sensownie obsłużyć lokalnie.
 */
public class DatabaseException extends RuntimeException {

    /** Błąd wykryty przez nasz kod (np. zła wersja schematu), a nie zgłoszony przez SQLite. */
    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
