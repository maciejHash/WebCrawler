package pl.webcrawler;

/**
 * Błąd bazy danych. Niekontrolowany, żeby kod robota nie musiał deklarować
 * SQLException w każdej metodzie - i tak nie da się go sensownie obsłużyć lokalnie.
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
