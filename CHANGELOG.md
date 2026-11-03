# Changelog

Wszystkie istotne zmiany w projekcie. Format według [Keep a Changelog](https://keepachangelog.com/pl/1.1.0/),
numeracja według [Semantic Versioning](https://semver.org/lang/pl/). Najnowsze wydanie jest na górze;
zmiany jeszcze niewydane są w sekcji „Niewydane”.

## [Niewydane]

### Dodane

- Opcja `--version` - wypisuje `Web Crawler <wersja>` i kończy program kodem 0, także przy błędnych
  pozostałych argumentach.
- Jedno źródło numeru wersji: `<version>` w `pom.xml`. Maven wstawia go do `webcrawler-version.properties`,
  skąd czyta go klasa `AppVersion`. Wersja programu i Javy w komunikacie startowym,
  `Implementation-Title` i `Implementation-Version` w manifeście JAR-a.
- Opcja `--timeout MS` - limit czasu jednego żądania HTTP (wcześniej stałe 10 s).
- Opcja `--user-agent TEKST` - własny nagłówek `User-Agent`; dozwolone tylko drukowalne znaki ASCII.
- Wersjonowanie schematu bazy (`PRAGMA user_version`) z listą migracji. Bazy `crawler.db` z wersji 1.0.0
  są rozpoznawane i przejmowane bez utraty danych. Baza z nowszej wersji programu kończy się błędem.

### Zmienione

- Domyślny `User-Agent` zawiera wersję programu: `pl.webcrawler/<wersja> (student project)`
  zamiast stałego `pl.webcrawler/1.0 (student project)`.
- Linki znalezione na stronie i status `VISITED` tej strony są zapisywane w jednej transakcji
  (`saveVisitedPage`, linki wysyłane paczką) - mniej zapisów na dysk.

### Poprawione

- Robot nie zawiesza się, gdy w wątku poleci nieoczekiwany wyjątek. Strona dostaje status `ERROR`
  z nazwą klasy wyjątku, a wątek pracuje dalej (wcześniej link zostawał `IN_PROGRESS`, a pozostałe
  wątki czekały bez końca).
- Błąd bazy w wątku roboczym (`DatabaseException`) zatrzymuje wszystkie wątki łagodnie, oddaje link
  do `NEW` i kończy program kodem 1.
- Strona przerwana w trakcie zapisu linków nie zwiększa `seen` dwukrotnie po wznowieniu
  (zasługa transakcji powyżej).

## [1.0.0] - 2026-11-02

Pierwsze wydanie.

### Dodane

- Wielowątkowy robot: pula wątków `Executors.newFixedThreadPool`, każdy `CrawlerThread` sam przejmuje
  z bazy nieodwiedzone linki.
- Baza SQLite (`DBConn`) z tabelą `links`: `url UNIQUE`, zapis UPSERT-em (`seen = seen + 1` dla linku
  już znanego), przejęcie linku warunkowym `UPDATE` - żadna strona nie jest pobierana dwa razy.
  Tryb WAL i `busy_timeout`.
- Normalizacja adresów (`UrlUtils`): fragment, wielkość liter schematu i hosta, port domyślny, kodowanie
  znaków. Pomijanie innych hostów i plików binarnych (status `SKIPPED`).
- Opcje `--threads`, `--max-pages` (wspólny limit dla wszystkich wątków), `--max-depth`, `--delay`,
  `--all-hosts`, `--db`, `--fresh`, `-h`/`--help`. Kody wyjścia: 2 przy błędnych argumentach, 1 przy błędzie bazy.
- Wznawianie przerwanej pracy z pliku bazy; Ctrl+C zatrzymuje robota łagodnie.
- Raport na koniec: linki według statusu, 10 najczęściej znajdowanych linków, strony na wątek, czas trwania.
- Wykonywalny `target/web-crawler.jar` (maven-shade-plugin), CI w GitHub Actions, testy bez dostępu
  do internetu.

[Niewydane]: https://github.com/maciejHash/WebCrawler/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/maciejHash/WebCrawler/releases/tag/v1.0.0
