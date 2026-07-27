# Turf

Dzieli repozytorium na dwa terytoria: Twoje i AI. Wolno wam się nawzajem **wywoływać**,
nie wolno wam się nawzajem **edytować**.

Dwa kawałki, jeden wspólny plik prawdy:

| | co robi |
|---|---|
| `mcp/` | serwer MCP — **orzeka**, czy model ma prawo tknąć daną ścieżkę |
| `plugin/` | wtyczka IntelliJ — strona ludzka: nadawanie własności, wnioski, wykrywanie naruszeń |
| `.turf/ownership.json` | manifest własności, w repo, które chronisz (nie tutaj) |

## Jak to działa

MCP **nie jest kanałem zapisu**. Model dalej pisze normalnym Edit/Write — ale przed każdą
modyfikacją musi wywołać `turf_check`, który zwraca właściciela i twarde zasady.

```
turf_check("src/Main.java")   ->  WŁAŚCICIEL: human      MOŻESZ EDYTOWAĆ: NIE
turf_check("src/NoweAi.java") ->  nie istnieje  -> rezerwacja jako plik AI, wolno tworzyć
turf_check("src/Stare.java")  ->  WŁAŚCICIEL: none       MOŻESZ EDYTOWAĆ: NIE
```

Cztery narzędzia:

- **`turf_rules`** — pełny kontrakt. Do wywołania raz na starcie sesji.
- **`turf_check`** — obowiązkowe przed każdą modyfikacją. Nic nie zapisuje w pliku.
- **`turf_request`** — wniosek o zmianę w Twoim pliku, **maksymalnie 3 linijki łącznie**.
  Walidacja jest twarda: 4 linijki lecą z błędem.
- **`turf_status`** — podsumowanie własności i decyzje w sprawie złożonych wniosków.

### Kto jest właścicielem

| stan | AI może edytować | AI może wnioskować |
|---|---|---|
| `ai` | tak | — |
| `human` | **nie** | tak, ≤3 linijki |
| `none` (brak wpisu) | **nie** | **nie** |

Brak wpisu to odmowa, nie zgoda. Stary kod jest niczyj do momentu, aż go oznaczysz —
AI nie może go tknąć ani nawet o niego wnioskować.

Jedyne automatyczne nadanie własności: `turf_check` na **nieistniejącą** ścieżkę rezerwuje
ją dla AI. Rezerwacja bez utworzonego pliku wygasa po 24 h.

### Gdzie jest realne egzekwowanie

Skoro MCP nie pisze, to sam z siebie niczego nie zablokuje — orzeka, a nie broni.
Prawdziwym strażnikiem jest wtyczka: `ViolationWatcher` łapie zapisy do plików, które nie
należą do AI, i pokazuje je w oknie **Turf → Naruszenia** plus balonik.

Rozróżnienie idzie po tym, kto zgłosił zdarzenie VFS: zapis z edytora IDE ma niepustego
requestora, zapis narzędziem z zewnątrz (Claude Code) — pustego. To **heurystyka**:
edycja tym samym plikiem w innym zewnętrznym edytorze też się tu pokaże. Masowe zmiany
(>20 plików naraz, czyli operacje gita) są pomijane.

## Instalacja

### 1. Serwer MCP

```bash
cd C:/Users/danie/turf/mcp && npm install && npm run build
```

Test (buduje tymczasowe repo i przechodzi każdą ścieżkę werdyktu):

```bash
node C:/Users/danie/turf/mcp/test/smoke.mjs
```

Podepnij w repozytorium, które chcesz chronić — `.mcp.json` w jego korzeniu:

```json
{
  "mcpServers": {
    "turf": {
      "command": "node",
      "args": ["C:/Users/danie/turf/mcp/dist/index.js"]
    }
  }
}
```

Korzeń repo serwer wykrywa sam, idąc w górę do `.git`. Jak chcesz go wskazać na sztywno,
ustaw zmienną `TURF_ROOT`.

### 2. Wtyczka IntelliJ

```bash
cd C:/Users/danie/turf/plugin && ./gradlew buildPlugin
```

Zip ląduje w `plugin/build/distributions/`. W IDE: **Settings → Plugins → ⚙ → Install
Plugin from Disk**. Zbudowana pod IntelliJ 2025.1+ (`sinceBuild=251`, `untilBuild=261.*`),
tylko API platformy — działa też w PyCharmie, Riderze i reszcie.

Do rozwoju: `./gradlew runIde` odpala osobną instancję IDE z wtyczką.

### 3. Kontrakt w CLAUDE.md

Opisy narzędzi to za mało, żeby model konsekwentnie pytał. Wklej to do `CLAUDE.md`
chronionego repozytorium:

```markdown
## Turf — podział terytorium

To repozytorium jest podzielone na kod człowieka i kod AI. Obowiązuje Cię kontrakt Turf.

- Na starcie sesji wywołaj `turf_rules`.
- Przed KAŻDĄ modyfikacją pliku wywołaj `turf_check` na tę ścieżkę. Bez wyjątków, także
  dla plików już edytowanych w tej sesji.
- Werdykt "MOŻESZ EDYTOWAĆ: NIE" jest wiążący. Nie omijaj go powłoką ani żadną inną drogą.
- Plik użytkownika: możesz złożyć `turf_request` na maksymalnie 3 linijki i idziesz dalej,
  nie czekasz na decyzję.
- Plik bez właściciela: nie edytujesz i nie wnioskujesz. Powiedz o tym użytkownikowi.
```

## Używanie

**Nadawanie własności** — prawy przycisk w drzewie projektu → **Turf**: Oznacz jako moje /
Oznacz jako AI / Wyczyść. Działa na zaznaczeniu wielu plików i rekurencyjnie na katalogach.

**Drzewo projektu** — pliki AI dostają czerwoną ikonę `AI` w miejsce ikony typu (przy
klasie Javy zamiast niebieskiego ⓒ). Twoje pliki nie dostają nic: chodzi o to, żeby cudze
rzucało się w oczy, a nie żeby przy każdej pozycji w drzewie wisiał dopisek. Właściciela
otwartego pliku — łącznie ze stanem „niczyj" — pokazuje pasek stanu.

**Okno Turf** (dół): zakładka **Wnioski** — lista z powodem i diffem `było` / `ma być`,
przyciski Przyjmij i Odrzuć (z komentarzem dla AI). Przyjęcie stosuje zmianę jako zwykłą
edycję dokumentu, więc działa Ctrl+Z. Zakładka **Naruszenia** — zapisy z zewnątrz do
nieswoich plików, z możliwością oddania pliku AI na miejscu.

Wniosek zapisuje treść linii z chwili złożenia. Jeśli plik zmienił się w międzyczasie,
przyjęcie jest odrzucane z komunikatem zamiast nadpisać coś na ślepo.

## Format manifestu

`.turf/ownership.json` w chronionym repozytorium:

```json
{
  "version": 1,
  "files": {
    "src/main/java/com/example/Main.java": {
      "owner": "human",
      "since": "2026-07-28T00:00:00Z",
      "by": "IDE"
    }
  },
  "patterns": [
    { "glob": "src/generated/**", "owner": "ai" }
  ]
}
```

Wpis pliku bije wzorzec. Przy wzorcach wygrywa **ostatni** pasujący, więc te bardziej
szczegółowe dopisuje się na koniec listy. Implementacja globa jest ta sama po obu stronach
(`**`, `*`, `?`).

## Znane ograniczenia

- Wykrywanie naruszeń jest heurystyką po requestorze zdarzenia VFS, nie dowodem.
- Manifest nie wie o `git mv` — plik przeniesiony poza IDE traci wpis i staje się niczyj
  (czyli domyślnie zablokowany, więc błąd jest w bezpieczną stronę).
- `plugin/build.gradle.kts` używa IntelliJ Platform Gradle Plugin 2.6.0; jest już 2.18.1,
  ale 2.6.0 jest sprawdzone na tej konfiguracji.
