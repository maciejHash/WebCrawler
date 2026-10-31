package pl.webcrawler;

/**
 * Punkt wejścia programu. Na tym etapie tylko wypisuje komunikat startowy;
 * obsługa argumentów wiersza poleceń dojdzie później.
 */
public class Main {

    /** Wydzielone z main, żeby test mógł sprawdzić treść bez przechwytywania System.out. */
    static String startMessage() {
        return "Web Crawler - start (Java " + Runtime.version() + ")";
    }

    public static void main(String[] args) {
        System.out.println(startMessage());
    }
}
