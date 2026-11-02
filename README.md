# Web Crawler

Wielowątkowy robot internetowy w Javie, który przegląda strony WWW i zapisuje znalezione linki w bazie SQLite.

## Wymagania

- JDK 27
- Maven 3.9+

## Budowanie

```bash
mvn verify
```

Kompiluje kod, uruchamia testy i buduje wykonywalny plik `target/web-crawler.jar`
(zawiera wszystkie zależności).

## Uruchamianie

```bash
java -jar target/web-crawler.jar https://books.toscrape.com --threads 4 --max-pages 50
java -jar target/web-crawler.jar --help
```

Najważniejsze opcje:

- `--fresh` – usuwa dane z bazy i zaczyna od nowa. Bez tej flagi robot wznawia
  poprzednią pracę zapisaną w pliku bazy (`--db`, domyślnie `crawler.db`).
- `--delay MS` – przerwa po każdym żądaniu, żeby nie przeciążać serwera (domyślnie 500 ms).
- Ctrl+C zatrzymuje robota łagodnie: wątki kończą bieżące strony, wypisywany jest raport,
  a nieodwiedzone linki czekają na następne uruchomienie.

Na końcu program wypisuje raport: liczbę linków w każdym statusie, 10 najczęściej
znajdowanych linków, liczbę stron pobranych przez każdy wątek i czas trwania.

Alternatywnie, bez budowania JAR-a:

```bash
mvn -q compile exec:java -Dexec.args="https://books.toscrape.com --max-pages 20"
```
