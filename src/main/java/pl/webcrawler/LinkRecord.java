package pl.webcrawler;

/** Link przejęty przez wątek do odwiedzenia: tylko to, czego wątek potrzebuje do pracy. */
public record LinkRecord(long id, String url, int depth) {
}
