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
turf_check("src/Stare.java")  ->  WŁAŚCICIEL: none       MOŻESZ EDYTOWAĆ: NIE (wniosek: tak)
```

Cztery narzędzia:

- **`turf_rules`** — pełny kontrakt. Do wywołania raz na starcie sesji.
- **`turf_check`** — obowiązkowe przed każdą modyfikacją. Nic nie zapisuje w pliku.
- **`turf_request`** — wniosek o zmianę w Twoim pliku, **maksymalnie 3 linijki łącznie**.
  Walidacja jest twarda: 4 linijki lecą z błędem.
- **`turf_status`** — podsumowanie własności i decyzje w sprawie złożonych wniosków.

### Tryb: pliki albo pakiety

Granulacja własności jest do wyboru **per projekt** i siedzi w manifeście, żeby MCP orzekał
dokładnie tak samo, jak wygląda drzewo w IDE.

| tryb | jednostka własności | plik dostaje właściciela |
|---|---|---|
| `file` (domyślny) | pojedynczy plik | z własnego wpisu |
| `package` | katalog | z **najbliższego** katalogu w górę |

W trybie pakietowym oznaczasz cały `com/example` jednym kliknięciem i każdy plik w środku
— też ten, którego jeszcze nie ma — jest już rozstrzygnięty. Zagnieżdżony pakiet przykrywa
nadrzędny, więc `com/example` może być Twój, a `com/example/generated` już AI.

Tryby są **rozłączne**: aktywny decyduje, którą warstwę manifestu się czyta, druga leży
nietknięta. Przełączenie tam i z powrotem odtwarza to, co było, zamiast mieszać dwie
granulacje i produkować pliki z dwoma właścicielami naraz. Wzorce (`patterns`) działają
w obu trybach jako podkładka pod spodem.

Jedna rzecz różni się poza samym rozstrzyganiem: **w trybie pakietowym nie ma rezerwacji
nowych ścieżek**. AI tworzy pliki wyłącznie wewnątrz pakietów, które już do niej należą —
inaczej „nowy plik" byłby furtką do zakładania sobie terytorium w cudzym pakiecie. Jak AI
potrzebuje miejsca, prosi Cię o oznaczenie pakietu.

Trybu nie ma w narzędziach MCP. Wybierasz go tylko Ty, w IDE.

### Kto jest właścicielem

| stan | AI może edytować | AI może wnioskować |
|---|---|---|
| `ai` | tak | — |
| `human` | **nie** | tak, ≤3 linijki |
| `none` (brak wpisu) | **nie** | tak, ≤3 linijki |

**Brak wpisu traktowany jest jak własność człowieka** — domyślną odpowiedzią jest odmowa
edycji, ale wniosek przechodzi na tych samych zasadach. Osobna kategoria „niczyj" z
zakazem wnioskowania tworzyła martwy stan: plik, którego nie da się ani tknąć, ani o
niego poprosić. Jedna reguła zamiast dwóch.

Automatyczne nadania własności są dwa, oba w trybie plikowym:

- `turf_check` na **nieistniejącą** ścieżkę rezerwuje ją dla AI. Rezerwacja bez
  utworzonego pliku wygasa po 24 h.
- **Plik utworzony w IntelliJ jest od razu Twój.** Nowa klasa lądowała wcześniej jako
  „niczyj", czyli wyglądała jak plik czekający na decyzję — mimo że decyzja właśnie
  zapadła przez samo jej utworzenie. Rezerwacji AI to nie odbiera: jeśli wpis na tę
  ścieżkę już istnieje, zostaje bez zmian.

W trybie pakietowym żadne z tych dwóch nie działa i nie musi — nowy plik dziedziczy
właściciela pakietu, w którym powstał.

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

- Na starcie sesji wywołaj `turf_rules`. Projekt ma tryb własności — plikowy albo
  pakietowy — i `turf_rules` podaje zasady tego, który jest ustawiony. Nie zakładaj trybu
  z góry i nie proś o jego zmianę.
- Przed KAŻDĄ modyfikacją pliku wywołaj `turf_check` na tę ścieżkę. Bez wyjątków, także
  dla plików już edytowanych w tej sesji.
- Utworzenie nowego pliku też wymaga `turf_check`. W trybie pakietowym wolno Ci tworzyć
  pliki wyłącznie wewnątrz pakietów, które już należą do Ciebie.
- Werdykt "MOŻESZ EDYTOWAĆ: NIE" jest wiążący. Nie omijaj go powłoką ani żadną inną drogą.
- Plik użytkownika: możesz złożyć `turf_request` na maksymalnie 3 linijki i idziesz dalej,
  nie czekasz na decyzję.
- Plik bez wpisu traktuj jak plik użytkownika: nie edytujesz, ale możesz wnioskować
  na tych samych zasadach.
```

## Używanie

**Wybór trybu** — prawy przycisk w drzewie → **Turf → Tryb własności**: Pliki albo Pakiety.
Ustawienie jest per projekt i zapisuje się w manifeście, więc MCP widzi je natychmiast.

**Nadawanie własności** — prawy przycisk w drzewie projektu → **Turf**: Oznacz jako moje /
Oznacz jako AI / Wyczyść. Działa na zaznaczeniu wielu plików.

Zasięg zależy od trybu, a nazwa pozycji w menu mówi to wprost, żeby nie trzeba było zgadywać:

- **pliki** — „Oznacz jako moje", katalogi schodzą rekurencyjnie do pojedynczych plików;
- **pakiety** — „Oznacz pakiet jako mój", jeden wpis na katalog, bez schodzenia w głąb;
  kliknięcie w plik oznacza pakiet, w którym on leży. Opis pod pozycją menu podaje, o który
  pakiet dokładnie chodzi.

**Drzewo projektu** — pliki AI dostają czerwoną ikonę w miejsce ikony typu (przy klasie
Javy zamiast niebieskiego ⓒ). Ikona jest zbudowana dokładnie tak jak stockowe
`expui/nodes/*.svg`: koło `r=6.5` z bladą poświatą i obwódką w kolorze akcentu, litery
tej samej wysokości i grubości co w `class`/`enum`/`interface` — różni się wyłącznie
paletą, więc nie odstaje od reszty drzewa. Jest osobny wariant na ciemny motyw.

Pliki **nieprzypisane są wyszarzone**. Twoje nie dostają nic — brak dekoracji znaczy, że
wszystko jest jak być powinno.

W trybie pakietowym te same reguły obejmują **katalogi**, bo to one są jednostką własności:
pakiet AI dostaje czerwoną ikonę, nieprzypisany jest wyszarzony. Pliki w środku i tak noszą
oznaczenie odziedziczone, więc granicę pakietu widać bez rozwijania drzewa.

**`Ctrl+Shift+G`** ukrywa i przywraca czerwone ikony AI — jednym klawiszem patrzysz na
drzewo bez tego oznaczenia, drugim wracasz. Wyszarzenie nieprzypisanych zostaje niezależnie
od przełącznika: to nie jest oznaczenie AI, tylko sygnał, że plik czeka na Twoją decyzję.
Skrót zmienisz w Settings → Keymap, szukając „Turf". Stan przełącznika, aktywny tryb i
właściciela otwartego pliku — łącznie ze stanem „niczyj" — widać na pasku stanu.

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
  "mode": "package",
  "files": {
    "src/main/java/com/example/Main.java": {
      "owner": "human",
      "since": "2026-07-28T00:00:00Z",
      "by": "IDE"
    }
  },
  "dirs": {
    "src/main/java/com/example": { "owner": "human", "since": "...", "by": "IDE" },
    "src/main/java/com/example/generated": { "owner": "ai", "since": "...", "by": "IDE" }
  },
  "patterns": [
    { "glob": "src/generated/**", "owner": "ai" }
  ]
}
```

`mode` to `"file"` albo `"package"`; brak pola znaczy `"file"`, więc stare manifesty
działają bez konwersji. Czytana jest tylko warstwa odpowiadająca trybowi — w powyższym
przykładzie `Main.java` należy do Ciebie z `dirs`, a wpis w `files` jest ignorowany do
czasu przełączenia na tryb plikowy.

W `dirs` klucz `""` oznacza korzeń repozytorium. Wygrywa **najbliższy** pasujący katalog,
więc reguły zagnieżdżone nie wymagają żadnej dodatkowej kolejności.

Wpis (pliku albo katalogu, zależnie od trybu) bije wzorzec. Przy wzorcach wygrywa
**ostatni** pasujący, więc te bardziej szczegółowe dopisuje się na koniec listy.
Implementacja globa jest ta sama po obu stronach (`**`, `*`, `?`).

## Znane ograniczenia

- Wykrywanie naruszeń jest heurystyką po requestorze zdarzenia VFS, nie dowodem.
- Automatyczne przejęcie nowego pliku stoi na tej samej heurystyce, tylko odwróconej.
  Plik utworzony innym zewnętrznym edytorem nie zostanie przejęty (pusty requestor), a
  wtyczka, która tworzy pliki przez API IDE, zrobi to za Ciebie. Pomijane są ścieżki,
  których i tak się nie oznacza: `.turf`, `.git`, `.idea`, `.gradle`, `build`, `out`,
  `target`, `node_modules`.
- Zmiana trybu nie konwertuje oznaczeń. Świeże przełączenie na pakiety zastaje pusty
  `dirs`, czyli całe repo jako „niczyj", dopóki nie oznaczysz pakietów.
- Manifest nie wie o `git mv` — plik przeniesiony poza IDE traci wpis i staje się niczyj
  (czyli domyślnie zablokowany, więc błąd jest w bezpieczną stronę).
- `plugin/build.gradle.kts` używa IntelliJ Platform Gradle Plugin 2.6.0; jest już 2.18.1,
  ale 2.6.0 jest sprawdzone na tej konfiguracji.
