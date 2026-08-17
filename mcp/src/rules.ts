import type { Mode, Owner } from "./ownership.js";

export const MAX_REQUEST_LINES = 3;

/** Adnotacja, ktora AI zostawia nad kodem dopisanym na terenie wspolnym. */
export const MARKER = "@Claude";

export interface Verdict {
  rel: string;
  exists: boolean;
  mode: Mode;
  owner: Owner;
  source: string;
  canEdit: boolean;
  canCreate: boolean;
  reservedForAi: boolean;
}

const WSPOLNE = `KONTRAKT TURF - PODZIAL TERYTORIUM

Repozytorium jest podzielone na dwa terytoria. Kod nalezy albo do czlowieka, albo do
Ciebie. Wolno wam sie nawzajem WYWOLYWAC - publiczne metody obu stron sa dostepne bez
zadnych ograniczen. Nie wolno wam sie nawzajem EDYTOWAC.

Zasady bezwzgledne:

1. Zanim uzyjesz Edit, Write, NotebookEdit albo jakiegokolwiek innego narzedzia, ktore
   modyfikuje plik na dysku - wywolaj turf_check na ten plik. Bez wyjatkow, takze
   dla plikow, ktore juz wczesniej edytowales w tej sesji.

2. turf_check niczego nie zapisuje. To orzeczenie, nie kanal zapisu. Zapisujesz
   normalnymi narzedziami, ale tylko wtedy, gdy orzeczenie na to pozwala.

3. Plik nalezacy do czlowieka: NIE EDYTUJESZ. Mozesz zlozyc wniosek przez
   turf_request na maksymalnie ${MAX_REQUEST_LINES} linijki i czekasz na decyzje.
   Wiekszej zmiany nie da sie wnioskowac - jesli potrzeba wiecej, napisz to u siebie
   i podepnij sie metoda.

4. Plik niczyj (bez wpisu w manifescie) traktujesz jak plik uzytkownika: nie edytujesz,
   ale mozesz zlozyc na niego wniosek na tych samych zasadach. Brak wpisu to domyslna
   odmowa edycji, a nie osobna kategoria z wlasnymi regulami.

5. Teren wspolny (owner: shared) to trzecia kategoria i jedyna, w ktorej piszesz w
   cudzym kodzie bez pytania. Obowiazuja tam trzy rzeczy naraz:
   - piszesz MALO: przestawiasz kolejnosc, dokladasz krotka metode, poprawiasz
     drobiazg. Nie przepisujesz cudzej metody i nie przebudowujesz pliku;
   - piszesz DLA CZLOWIEKA: to jego kod i jego konwencje, wiec dopasowujesz sie do
     tego, co zastales, zamiast wprowadzac swoj styl;
   - ZAWSZE zostawiasz adnotacje ${MARKER} w linii bezposrednio nad tym, co dopisales.
     Bez adnotacji zmiana na terenie wspolnym jest naruszeniem kontraktu, nawet jesli
     sama zmiana byla drobna i sluszna. IDE po tej adnotacji poznaje i zwija Twoj kod,
     wiec bez niej Twoja praca udaje prace czlowieka.
   Jesli zmiana nie miesci sie w "malo", nie robisz jej po cichu - skladasz wniosek
   albo mowisz uzytkownikowi, ze potrzeba czegos wiekszego.`;

const KONIEC = `Obejscie granicy jest naruszeniem kontraktu, nawet jesli technicznie sie uda.
   Nikt nie stoi nad Toba z alarmem - wlasnie dlatego to Ty odpowiadasz za trzymanie
   sie orzeczenia. Uzytkownik widzi w IDE, ktory plik jest czyj, i czyta diff.

O trybie decyduje wylacznie uzytkownik w IDE. Nie masz narzedzia, ktore go zmienia,
i nie prosisz o zmiane trybu, zeby ominac odmowe.`;

const TRYB_PLIK = `6. TRYB: PLIKI. Wlasnosc jest nadawana pojedynczym plikom. Kazdy plik ma swojego
   wlasciciela niezaleznie od tego, gdzie lezy.

7. Nowy plik: turf_check rezerwuje go dla Ciebie w momencie sprawdzenia. Po
   utworzeniu jest Twoj i mozesz w nim pracowac bez pytania. Rezerwacja dotyczy
   DOKLADNIE tej sciezki - inny plik wymaga osobnego turf_check.
   Uwaga: pliki tworzone przez uzytkownika w IDE staja sie od razu jego, wiec
   sciezka, ktora przed chwila byla wolna, moze juz nie byc.

8. `;

const TRYB_PAKIET = `6. TRYB: PAKIETY. Wlasnosc jest nadawana calym katalogom, a plik dziedziczy ja z
   najblizszego katalogu w gore. Nie ma wlasnosci pojedynczego pliku - jesli pakiet
   nalezy do uzytkownika, to nalezy do niego kazdy plik w srodku, takze taki, ktory
   dopiero powstanie.

7. Nowy plik: NIE MA rezerwacji. Wolno Ci utworzyc plik wylacznie wewnatrz pakietu,
   ktory juz nalezy do Ciebie. W cudzym albo nieprzypisanym pakiecie nie tworzysz
   niczego - takze zeby "obejsc" brak dostepu do pliku obok.

8. Nie zakladasz nowych pakietow poza swoim terytorium. Katalog powstaly przy okazji
   tworzenia pliku dziedziczy tak samo jak plik, wiec katalog w cudzym pakiecie
   dalej jest cudzy. Jesli potrzebujesz wlasnego miejsca, popros uzytkownika o
   oznaczenie pakietu.

9. `;

export function contract(mode: Mode): string {
  const tryb = mode === "package" ? TRYB_PAKIET : TRYB_PLIK;
  return `${WSPOLNE}\n\n${tryb}${KONIEC}`;
}

export function modeLabel(mode: Mode): string {
  return mode === "package" ? "pakiety (wlasnosc katalogu)" : "pliki (wlasnosc pliku)";
}

export function verdictText(v: Verdict, absPath: string): string {
  const head = [
    `PLIK: ${v.rel}`,
    `SCIEZKA: ${absPath}`,
    `ISTNIEJE: ${v.exists ? "tak" : "nie"}`,
    `TRYB: ${modeLabel(v.mode)}`,
    `WLASCICIEL: ${ownerLabel(v.owner)}  (${v.source})`,
    `MOZESZ EDYTOWAC: ${editLabel(v)}`,
  ].join("\n");

  return `${head}\n\n${body(v)}`;
}

function editLabel(v: Verdict): string {
  if (!v.canEdit) return "NIE";
  if (v.owner === "shared") return `TAK, ale drobiazgowo i zawsze z adnotacja ${MARKER}`;
  return "TAK";
}

function ownerLabel(o: Owner): string {
  if (o === "ai") return "ai (Ty)";
  if (o === "human") return "human (uzytkownik)";
  if (o === "shared") return "shared (teren wspolny)";
  return "none (niczyj)";
}

function body(v: Verdict): string {
  if (!v.exists) return bodyNewFile(v);

  if (v.owner === "ai") {
    return `ZASADY

Plik jest Twoj. Edytuj normalnie - Edit, Write, co potrzebujesz.

  - Nie przenos tu kodu, ktory nalezy do uzytkownika, zeby go "odblokowac".
  - Mozesz wywolywac dowolne publiczne metody z jego plikow.
  - Jesli refactor wymaga zmiany po jego stronie, zloz turf_request na jego plik.`;
  }

  if (v.owner === "shared") {
    return `ZASADY - TEREN WSPOLNY

Mozesz pisac w tym pliku bez wniosku, ale na warunkach, ktore obowiazuja bezwzglednie.

  1. KAZDY fragment, ktory dopiszesz albo przerobisz, poprzedzasz linia z adnotacja
     ${MARKER}. Linia stoi bezposrednio nad tym fragmentem i sklada sie wylacznie z
     adnotacji (w jezykach bez adnotacji - w komentarzu, np. "# ${MARKER}" albo
     "// ${MARKER}"). IDE po tym poznaje i zwija Twoj kod.
  2. Zmiana ma byc mala: przestawienie kolejnosci, krotka nowa metoda, drobna poprawka.
     Nie przepisujesz cudzych metod i nie przebudowujesz pliku. Jesli potrzeba czegos
     wiecej - turf_request albo rozmowa z uzytkownikiem, nie ciche wieksze ciecie.
  3. Piszesz w konwencji, ktora tu zastales. Ten kod docelowo nalezy do uzytkownika,
     wiec to jego styl obowiazuje, nie Twoj.
  4. Nie kasujesz i nie przenosisz cudzego kodu, nie zmieniasz nazwy pliku.
  5. Cudzych adnotacji ${MARKER} z poprzednich sesji nie zdejmujesz - to nie jest
     smiec do posprzatania, tylko granica.`;
  }

  if (v.owner === "human") {
    return `ZASADY - OBOWIAZUJA BEZWZGLEDNIE

Plik nalezy do uzytkownika. NIE WOLNO Ci go zmodyfikowac zadnym narzedziem.

  - Nie uzywaj Edit ani Write na tej sciezce.
  - Nie omijaj tego przez powloke (sed, Set-Content, przekierowanie, patch, git apply).
  - Nie kasuj, nie zmieniaj nazwy, nie przenos tego pliku.

Co MOZESZ zrobic:

  1. Zlozyc wniosek: turf_request na maksymalnie ${MAX_REQUEST_LINES} linijki.
     Podajesz zakres linii, nowa tresc i powod. Uzytkownik zatwierdza albo odrzuca
     w IDE. Po zlozeniu wniosku PRZESTAJESZ czekac na ten plik i robisz cos innego -
     decyzje sprawdzisz przez turf_status.
  2. Napisac rozwiazanie u siebie i podpiac sie do jego kodu wywolaniem metody.
  3. Powiedziec uzytkownikowi, ze zmiana po jego stronie jest konieczna, i dlaczego.`;
  }

  const skad =
    v.mode === "package"
      ? `Zaden pakiet nad tym plikiem nie ma wlasciciela.`
      : `Plik nie ma wpisu w manifescie.`;

  return `ZASADY - OBOWIAZUJA BEZWZGLEDNIE

${skad} Brak wpisu traktujesz jak wlasnosc uzytkownika:
domyslna odpowiedz to odmowa edycji.

  - NIE WOLNO Ci go zmodyfikowac zadnym narzedziem.
  - Nie omijaj tego przez powloke (sed, Set-Content, przekierowanie, patch, git apply).
  - Nie "przejmujesz" go sam. Wlasnosc nadaje wylacznie uzytkownik w IDE.

Co MOZESZ zrobic - dokladnie to samo, co przy pliku uzytkownika:

  1. Zlozyc wniosek: turf_request na maksymalnie ${MAX_REQUEST_LINES} linijki.
     Po zlozeniu PRZESTAJESZ czekac i robisz cos innego; decyzje sprawdzisz przez
     turf_status.
  2. Napisac rozwiazanie u siebie i podpiac sie do jego kodu wywolaniem metody.
  3. Powiedziec uzytkownikowi, ze plik jest nieoznaczony - moze chciec nadac mu
     wlasnosc, zeby stan byl jawny.`;
}

function bodyNewFile(v: Verdict): string {
  if (v.reservedForAi) {
    return `ZASADY

Ten plik nie istnieje, wiec jest to utworzenie nowego pliku. Zostal wlasnie
zarezerwowany dla Ciebie w manifescie - po utworzeniu bedzie Twoja wlasnoscia.

  - Mozesz go utworzyc i pracowac w nim bez dalszego pytania.
  - Jesli ostatecznie go nie utworzysz, rezerwacja wygasa sama.
  - Nie uzywaj tego jako furtki: rezerwacja dotyczy DOKLADNIE tej sciezki. Utworzenie
    innego pliku wymaga osobnego turf_check.`;
  }

  if (v.owner === "ai") {
    return `ZASADY

Ten plik nie istnieje. Lezy jednak na Twoim terytorium (${v.source}), wiec
mozesz go utworzyc i od razu w nim pracowac.

  - Wlasnosc dziedziczy sie z pakietu, wiec nie ma tu zadnej rezerwacji do wygasniecia.
  - To pozwolenie dotyczy tego pakietu, nie calego repozytorium. Plik obok, w innym
    pakiecie, wymaga osobnego turf_check.`;
  }

  if (v.owner === "shared") {
    return `ZASADY - TEREN WSPOLNY

Ten plik nie istnieje, a miejsce, w ktorym mialby powstac, jest wspolne (${v.source}).
Mozesz go utworzyc.

  - Kod, ktory w nim napiszesz, oznaczasz adnotacja ${MARKER} tak samo jak wszedzie
    indziej na terenie wspolnym.
  - Nie przenos tu cudzego kodu, zeby go "odblokowac" - przeniesiony kod dalej nalezy
    do uzytkownika, a Ty dostales prawo dopisywania, nie przejmowania.`;
  }

  const czyj =
    v.owner === "human" ? "nalezy do uzytkownika" : "nie ma wlasciciela";

  return `ZASADY - OBOWIAZUJA BEZWZGLEDNIE

Ten plik nie istnieje, a miejsce, w ktorym mialby powstac, ${czyj} (${v.source}).
NIE WOLNO Ci go utworzyc.

  - Nie tworzysz plikow poza swoim terytorium, takze pod pretekstem "to przeciez nowy
    plik, niczego nie nadpisuje".
  - Nie zakladasz tam katalogow ani pakietow.
  - turf_request dotyczy zmian w istniejacym pliku - na nieistniejacy nie przejdzie.

Co MOZESZ zrobic:

  1. Utworzyc ten plik u siebie, w pakiecie, ktory nalezy do Ciebie, i podpiac sie do
     kodu uzytkownika wywolaniem metody.
  2. Powiedziec uzytkownikowi, gdzie i po co potrzebujesz nowego pliku - moze oznaczyc
     ten pakiet jako Twoj.`;
}
