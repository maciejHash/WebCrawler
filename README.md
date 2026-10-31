# Web Crawler

Wielowątkowy robot internetowy w Javie, który przegląda strony WWW i zapisuje znalezione linki w bazie SQLite.

## Wymagania

- JDK 27
- Maven 3.9+

## Budowanie

```bash
mvn verify
```

Kompiluje kod i uruchamia testy.

## Uruchamianie

```bash
mvn -q compile exec:java
```

`exec:java` nie kompiluje projektu, dlatego przed nim jest `compile` (można je pominąć, jeśli wcześniej wykonano `mvn verify`).
