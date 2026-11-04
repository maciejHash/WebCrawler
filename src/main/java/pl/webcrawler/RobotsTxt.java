package pl.webcrawler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Reguły z pliku robots.txt dotyczące naszego robota - uproszczona wersja RFC 9309.
 * Sama logika, bez sieci: plik pobiera {@link RobotsCache}. Obiekt jest niezmienny,
 * więc wątki mogą go bezpiecznie współdzielić.
 * <p>
 * Plik składa się z grup: jedna lub kilka linii {@code User-agent}, a pod nimi reguły
 * {@code Allow}, {@code Disallow} i {@code Crawl-delay}. Liczy się tylko jedna grupa -
 * ta dla naszego robota albo, gdy jej nie ma, grupa {@code *}.
 */
public final class RobotsTxt {

    /**
     * Jedna reguła Allow albo Disallow.
     *
     * @param pattern wzorzec ścieżki; {@code *} to dowolny ciąg znaków, {@code $} na końcu - koniec adresu
     */
    private record Rule(String pattern, boolean allow) {
    }

    private static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of(), OptionalLong.empty());
    // Każda ścieżka po normalizacji zaczyna się od "/", więc ta reguła pasuje do wszystkiego.
    private static final RobotsTxt DISALLOW_ALL =
            new RobotsTxt(List.of(new Rule("/", false)), OptionalLong.empty());

    private final List<Rule> rules;
    private final OptionalLong crawlDelayMs;

    private RobotsTxt(List<Rule> rules, OptionalLong crawlDelayMs) {
        this.rules = List.copyOf(rules);
        this.crawlDelayMs = crawlDelayMs;
    }

    /** Brak ograniczeń - np. gdy serwer odpowiedział 404 (pliku nie ma). */
    public static RobotsTxt allowAll() {
        return ALLOW_ALL;
    }

    /** Wszystko zabronione - gdy nie wiadomo, na co host pozwala (HTTP 5xx, timeout, błąd sieci). */
    public static RobotsTxt disallowAll() {
        return DISALLOW_ALL;
    }

    /**
     * Czyta treść robots.txt i wybiera reguły dla naszego robota: grupę, której nazwa
     * (bez względu na wielkość liter) to token produktu z naszego User-Agent, a gdy jej nie ma -
     * grupę {@code *}. Bez żadnej z nich wszystko jest dozwolone. Kilka grup o tej samej
     * nazwie traktujemy jak jedną (tak każe RFC 9309).
     * <p>
     * Puste linie, komentarze {@code #}, nieznane dyrektywy (np. {@code Sitemap}) i linie bez
     * dwukropka są pomijane - plik pisze człowiek, więc błąd w jednej linii nie może
     * unieważnić całości. Reguły przed pierwszą linią {@code User-agent} nie należą do żadnej grupy.
     *
     * @param text      treść pliku (może zawierać BOM i końce linii CRLF)
     * @param userAgent pełny nagłówek User-Agent robota, np. {@code pl.webcrawler/1.2.0 (student project)}
     */
    public static RobotsTxt parse(String text, String userAgent) {
        String token = productToken(userAgent);
        // Reguły zbieramy osobno dla naszej grupy i dla "*" - o wyborze decydujemy dopiero na końcu,
        // bo grupa dla naszego robota może stać w pliku za grupą "*".
        List<Rule> ownRules = new ArrayList<>();
        List<Rule> anyRules = new ArrayList<>();
        OptionalLong ownDelay = OptionalLong.empty();
        OptionalLong anyDelay = OptionalLong.empty();
        boolean ownGroupFound = false;
        boolean anyGroupFound = false;

        // Stan bieżącej grupy: do kogo się odnosi i czy były już w niej reguły.
        boolean groupIsOwn = false;
        boolean groupIsAny = false;
        boolean groupHasRules = false;

        // BOM to niewidoczny znak na początku pliku, który zapisują niektóre edytory.
        String content = text.startsWith("﻿") ? text.substring(1) : text;
        // lines() rozpoznaje końce linii \n, \r\n i \r.
        for (String rawLine : content.lines().toList()) {
            int hash = rawLine.indexOf('#');
            String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue; // pusta linia, sam komentarz albo śmieci
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();

            switch (key) {
                case "user-agent" -> {
                    // User-agent po regułach zaczyna nową grupę; kilka linii pod rząd to jedna grupa.
                    if (groupHasRules) {
                        groupIsOwn = false;
                        groupIsAny = false;
                        groupHasRules = false;
                    }
                    if (!token.isEmpty() && value.equalsIgnoreCase(token)) {
                        groupIsOwn = true;
                        ownGroupFound = true;
                    } else if (value.equals("*")) {
                        groupIsAny = true;
                        anyGroupFound = true;
                    }
                }
                case "allow", "disallow" -> {
                    groupHasRules = true;
                    // Pusty wzorzec niczego nie blokuje ("Disallow:" = wszystko wolno), więc go nie
                    // zapisujemy - pasowałby do każdej ścieżki i zablokował wszystko.
                    if (!value.isEmpty()) {
                        Rule rule = new Rule(value, key.equals("allow"));
                        if (groupIsOwn) {
                            ownRules.add(rule);
                        }
                        if (groupIsAny) {
                            anyRules.add(rule);
                        }
                    }
                }
                case "crawl-delay" -> {
                    groupHasRules = true;
                    OptionalLong delay = parseSeconds(value);
                    // Liczy się pierwsza poprawna wartość w grupie.
                    if (groupIsOwn && ownDelay.isEmpty()) {
                        ownDelay = delay;
                    }
                    if (groupIsAny && anyDelay.isEmpty()) {
                        anyDelay = delay;
                    }
                }
                default -> {
                    // Nieznana dyrektywa (Sitemap, Host, literówka) - pomijamy.
                }
            }
        }

        if (ownGroupFound) {
            return new RobotsTxt(ownRules, ownDelay);
        }
        if (anyGroupFound) {
            return new RobotsTxt(anyRules, anyDelay);
        }
        return ALLOW_ALL;
    }

    /**
     * Token produktu z User-Agent - to nim robot przedstawia się w robots.txt: część przed
     * pierwszym {@code /} (albo spacją, gdy wersji nie ma), np. {@code pl.webcrawler}
     * z {@code pl.webcrawler/1.2.0 (student project)}.
     */
    static String productToken(String userAgent) {
        int end = 0;
        while (end < userAgent.length() && userAgent.charAt(end) != '/' && userAgent.charAt(end) != ' ') {
            end++;
        }
        return userAgent.substring(0, end);
    }

    /**
     * Czy wolno pobrać stronę. Wygrywa reguła o najdłuższym pasującym wzorcu (najbardziej
     * szczegółowa), a przy remisie Allow. Gdy nie pasuje żadna reguła - wolno.
     *
     * @param pathWithQuery ścieżka razem z query stringiem adresu po normalizacji, np. {@code /szukaj?q=a}
     */
    public boolean isAllowed(String pathWithQuery) {
        Rule best = null;
        for (Rule rule : rules) {
            if (!matches(rule.pattern(), pathWithQuery)) {
                continue;
            }
            int length = rule.pattern().length();
            if (best == null || length > best.pattern().length()
                    || (length == best.pattern().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** Wymagana przez host przerwa między żądaniami w milisekundach; pusta, gdy plik jej nie podaje. */
    public OptionalLong crawlDelayMs() {
        return crawlDelayMs;
    }

    /**
     * Dopasowanie wzorca od początku ścieżki. Bez {@code $} na końcu wystarczy, że wzorzec pasuje
     * do początku (to tak zwane dopasowanie prefiksu: {@code /a} pasuje do {@code /abc}).
     */
    static boolean matches(String pattern, String path) {
        boolean toEnd = pattern.endsWith("$");
        String withoutEnd = toEnd ? pattern.substring(0, pattern.length() - 1) : pattern;
        // Części między gwiazdkami muszą wystąpić w ścieżce po kolei; -1 zachowuje puste części.
        String[] parts = withoutEnd.split("\\*", -1);

        if (!path.startsWith(parts[0])) {
            return false;
        }
        if (parts.length == 1) {
            // Bez gwiazdek: zwykły prefiks albo, z $, cała ścieżka.
            return !toEnd || path.length() == parts[0].length();
        }
        int position = parts[0].length();
        for (int i = 1; i < parts.length - 1; i++) {
            // Bierzemy najwcześniejsze wystąpienie - zostawia najwięcej miejsca na kolejne części.
            int found = path.indexOf(parts[i], position);
            if (found < 0) {
                return false;
            }
            position = found + parts[i].length();
        }
        String last = parts[parts.length - 1];
        if (toEnd) {
            // Ostatnia część musi stać na samym końcu, za wszystkimi poprzednimi.
            return path.endsWith(last) && path.length() - last.length() >= position;
        }
        return path.indexOf(last, position) >= 0;
    }

    /**
     * Crawl-delay w sekundach, także ułamkowych ({@code 0.5}). Inny zapis (ujemny, z przecinkiem,
     * z jednostką) jest pomijany, jak każda niezrozumiała linia.
     */
    private static OptionalLong parseSeconds(String value) {
        if (!value.matches("\\d+(\\.\\d+)?")) {
            return OptionalLong.empty();
        }
        // Math.round dla ogromnych wartości zwraca Long.MAX_VALUE - górny limit nakłada CrawlerThread.
        return OptionalLong.of(Math.round(Double.parseDouble(value) * 1000));
    }
}
