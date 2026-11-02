package pl.webcrawler;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Normalizacja i filtrowanie adresów. Każdy adres przechodzi tędy przed zapisem do bazy,
 * bo kolumna {@code url} jest UNIQUE: dwa zapisy tej samej strony (np. z fragmentem i bez)
 * dałyby dwa wiersze i stronę odwiedzoną dwa razy.
 */
public final class UrlUtils {

    /** Rozszerzenia plików, których nie warto pobierać - robot parsuje tylko HTML. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt",
            "jpg", "jpeg", "png", "gif", "svg", "webp", "bmp", "ico", "tif", "tiff",
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "mp3", "wav", "ogg", "flac", "m4a",
            "mp4", "avi", "mov", "mkv", "webm", "wmv",
            "exe", "msi", "dmg", "iso", "apk", "bin", "jar");

    private UrlUtils() {
    }

    /**
     * Sprowadza bezwzględny adres do jednej, porównywalnej postaci.
     * <ul>
     *   <li>przyjmuje tylko {@code http} i {@code https} (mailto:, javascript:, tel:, ftp: odrzuca),</li>
     *   <li>usuwa fragment ({@code #...}) - wskazuje miejsce na tej samej stronie,</li>
     *   <li>schemat i host zamienia na małe litery (wielkość liter w nich nie ma znaczenia),</li>
     *   <li>usuwa port domyślny (80 dla http, 443 dla https),</li>
     *   <li>pustą ścieżkę zamienia na {@code "/"},</li>
     *   <li>ścieżkę i query string zostawia bez zmian - tam wielkość liter ma znaczenie.</li>
     * </ul>
     * <p>
     * Spacje i znaki spoza ASCII: {@link URI} ich nie akceptuje, a na stronach się zdarzają
     * (przeglądarki je tolerują). Dlatego przed parsowaniem kodujemy je w UTF-8 jako
     * {@code %XX}, tak jak robi to przeglądarka: {@code /moja strona} → {@code /moja%20strona}.
     * Istniejące sekwencje {@code %XX} zostają nietknięte, więc nic nie jest kodowane podwójnie.
     * Wyjątek to host: po zakodowaniu nie jest poprawną nazwą domeny, więc adresy ze spacją
     * albo polskimi znakami w hoście są odrzucane (obsługa domen IDN to za dużo jak na ten projekt).
     *
     * @param absoluteUrl adres bezwzględny, np. wynik {@code element.absUrl("href")}
     * @return znormalizowany adres albo {@code Optional.empty()}, gdy adres jest niepoprawny
     *         lub ma nieobsługiwany schemat; nigdy nie rzuca wyjątku
     */
    public static Optional<String> normalize(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(encodeUnsafeChars(absoluteUrl.strip()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        // getHost() zwraca null, gdy części "//host" nie ma albo host jest niepoprawny
        String host = uri.getHost();
        if (host == null) {
            return Optional.empty();
        }

        int port = uri.getPort();
        boolean defaultPort = (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);

        // Składamy adres ręcznie z "surowych" części, żeby nie zdekodować ani nie zakodować
        // niczego ponownie; fragment pomijamy.
        StringBuilder result = new StringBuilder();
        result.append(scheme).append("://");
        if (uri.getRawUserInfo() != null) {
            result.append(uri.getRawUserInfo()).append('@');
        }
        result.append(host.toLowerCase(Locale.ROOT));
        if (port != -1 && !defaultPort) {
            result.append(':').append(port);
        }
        String path = uri.getRawPath();
        result.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) {
            result.append('?').append(uri.getRawQuery());
        }
        return Optional.of(result.toString());
    }

    /**
     * Sprawdza, czy oba adresy wskazują ten sam serwis. Wielkość liter i przedrostek
     * {@code www.} są pomijane, bo {@code WWW.Example.com} i {@code example.com} to
     * w praktyce ta sama strona. Port i schemat nie mają znaczenia.
     *
     * @return {@code false}, gdy którykolwiek adres jest niepoprawny
     */
    public static boolean isSameHost(String url, String otherUrl) {
        Optional<String> host = hostWithoutWww(url);
        return host.isPresent() && host.equals(hostWithoutWww(otherUrl));
    }

    /**
     * Rozpoznaje po rozszerzeniu w ścieżce pliki, których nie warto pobierać
     * (dokumenty, obrazy, archiwa, multimedia, programy). Query string jest pomijany,
     * więc {@code /plik.pdf?v=2} to plik binarny, a {@code /pobierz.php?f=a.pdf} nie -
     * tego nie da się rozstrzygnąć bez pobrania.
     *
     * @return {@code false} także dla niepoprawnego adresu (i tak nie przejdzie przez {@link #normalize})
     */
    public static boolean looksLikeBinaryFile(String url) {
        Optional<String> normalized = normalize(url);
        if (normalized.isEmpty()) {
            return false;
        }
        String path = URI.create(normalized.get()).getPath().toLowerCase(Locale.ROOT);
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        int dot = lastSegment.lastIndexOf('.');
        if (dot == -1) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(lastSegment.substring(dot + 1));
    }

    /** Host małymi literami, bez "www."; pusty dla niepoprawnego adresu. */
    private static Optional<String> hostWithoutWww(String url) {
        // normalize() gwarantuje poprawny adres z hostem małymi literami
        return normalize(url)
                .map(u -> URI.create(u).getHost())
                .map(host -> host.startsWith("www.") ? host.substring(4) : host);
    }

    /**
     * Koduje spacje i znaki spoza ASCII jako UTF-8 {@code %XX}. Pozostałe znaki,
     * także {@code %}, zostają bez zmian - za ich poprawność odpowiada {@link URI}.
     */
    private static String encodeUnsafeChars(String url) {
        StringBuilder result = new StringBuilder(url.length());
        url.codePoints().forEach(codePoint -> {
            if (codePoint > ' ' && codePoint < 0x7F) {
                result.appendCodePoint(codePoint);
            } else {
                // spacja, znaki sterujące i wszystko spoza ASCII
                byte[] bytes = Character.toString(codePoint).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    result.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        });
        return result.toString();
    }
}
