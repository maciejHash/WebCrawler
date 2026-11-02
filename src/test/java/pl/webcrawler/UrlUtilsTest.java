package pl.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlUtilsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # fragment
            https://example.com/page#section     | https://example.com/page
            https://example.com/#                | https://example.com/
            # wielkie litery w schemacie i hoście; ścieżka bez zmian
            HTTPS://Example.COM/Page             | https://example.com/Page
            # porty domyślne znikają, inne zostają
            http://example.com:80/a              | http://example.com/a
            https://example.com:443/a            | https://example.com/a
            http://example.com:443/a             | http://example.com:443/a
            https://example.com:8080/a           | https://example.com:8080/a
            # pusta ścieżka
            https://example.com                  | https://example.com/
            https://example.com?q=1              | https://example.com/?q=1
            # query string bez zmian, fragment po nim usunięty
            https://example.com/s?q=Java&b=2     | https://example.com/s?q=Java&b=2
            https://example.com/s?q=a%20b#top    | https://example.com/s?q=a%20b
            # spacje i znaki spoza ASCII kodowane w UTF-8, istniejące %XX nietknięte
            https://example.com/my page          | https://example.com/my%20page
            https://example.com/a%20b            | https://example.com/a%20b
            https://example.com/zażółć?q=łódź    | https://example.com/za%C5%BC%C3%B3%C5%82%C4%87?q=%C5%82%C3%B3d%C5%BA
            '  https://example.com/trim  '       | https://example.com/trim
            """)
    void normalizesValidUrls(String input, String expected) {
        assertEquals(Optional.of(expected), UrlUtils.normalize(input));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "mailto:jan@example.com",
            "javascript:void(0)",
            "tel:+48123456789",
            "ftp://example.com/file.txt",
            "/relative/path",
            "example.com/page",
            "http://",
            "http:example.com",
            "https://exa mple.com/",
            "https://zażółć.pl/",
            "https://example.com/%zz",
            "not a url at all"
    })
    void rejectsInvalidOrUnsupportedUrls(String input) {
        assertEquals(Optional.empty(), UrlUtils.normalize(input));
    }

    @ParameterizedTest(name = "{0} ~ {1}")
    @CsvSource({
            "https://example.com/a,        https://example.com/b",
            "https://www.example.com/,     https://example.com/",
            "https://WWW.Example.COM/,     http://example.com:8080/x",
            "https://example.com,          https://www.EXAMPLE.com/a?q=1"
    })
    void sameHost(String url, String otherUrl) {
        assertTrue(UrlUtils.isSameHost(url, otherUrl));
    }

    @ParameterizedTest(name = "{0} !~ {1}")
    @CsvSource({
            "https://example.com/,         https://example.org/",
            "https://blog.example.com/,    https://example.com/",
            "https://www2.example.com/,    https://example.com/",
            "https://example.com/,         mailto:jan@example.com",
            "https://example.com/,         ''"
    })
    void differentHost(String url, String otherUrl) {
        assertFalse(UrlUtils.isSameHost(url, otherUrl));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/doc.pdf",
            "https://example.com/IMG/Photo.JPG",
            "https://example.com/a.png",
            "https://example.com/a.gif",
            "https://example.com/logo.svg",
            "https://example.com/archive.zip",
            "https://example.com/song.mp3",
            "https://example.com/movie.mp4",
            "https://example.com/setup.exe",
            "https://example.com/doc.pdf?version=2",
            "https://example.com/doc.pdf#page=3"
    })
    void recognizesBinaryFiles(String url) {
        assertTrue(UrlUtils.looksLikeBinaryFile(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/",
            "https://example.com/index.html",
            "https://example.com/page.php",
            "https://example.com/articles/2024",
            "https://example.com/v1.2/docs",
            "https://example.com/download.php?file=a.pdf",
            "https://example.com/pdf",
            "not a url"
    })
    void doesNotFlagPagesAsBinary(String url) {
        assertFalse(UrlUtils.looksLikeBinaryFile(url));
    }
}
