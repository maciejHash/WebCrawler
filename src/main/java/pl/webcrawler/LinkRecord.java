package pl.webcrawler;

/**
 * Link przejęty przez wątek do odwiedzenia: tylko to, czego wątek potrzebuje do pracy.
 *
 * @param id    klucz wiersza w tabeli links - nim wątek oznacza potem status strony
 * @param url   znormalizowany adres strony
 * @param depth głębokość strony; znalezione na niej linki dostaną depth + 1
 */
public record LinkRecord(long id, String url, int depth) {
}
