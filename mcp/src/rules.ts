import type { Owner, Resolution } from "./ownership.js";

export const MAX_REQUEST_LINES = 3;

export interface Verdict {
  rel: string;
  exists: boolean;
  owner: Owner;
  source: string;
  canEdit: boolean;
  canCreate: boolean;
  reservedForAi: boolean;
}

const CONTRACT = `KONTRAKT TURF - PODZIAL TERYTORIUM

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

4. Plik niczyj (bez wpisu w manifescie): NIE EDYTUJESZ i NIE WNIOSKUJESZ. Wlasciciel
   musi go najpierw oznaczyc. Powiedz o tym uzytkownikowi i zajmij sie czyms innym.

5. Nowy plik: turf_check rezerwuje go dla Ciebie w momencie sprawdzenia. Po
   utworzeniu jest Twoj i mozesz w nim pracowac bez pytania.

6. Obejscie granicy jest naruszeniem kontraktu, nawet jesli technicznie sie uda.
   Plugin IDE wykrywa zapisy z zewnatrz do plikow, ktore nie naleza do Ciebie, i
   pokazuje je uzytkownikowi. Nie probuj.`;

export function contract(): string {
  return CONTRACT;
}

export function verdictText(v: Verdict, absPath: string): string {
  const head = [
    `PLIK: ${v.rel}`,
    `SCIEZKA: ${absPath}`,
    `ISTNIEJE: ${v.exists ? "tak" : "nie"}`,
    `WLASCICIEL: ${ownerLabel(v.owner)}  (${v.source})`,
    `MOZESZ EDYTOWAC: ${v.canEdit ? "TAK" : "NIE"}`,
  ].join("\n");

  return `${head}\n\n${body(v)}`;
}

function ownerLabel(o: Owner): string {
  if (o === "ai") return "ai (Ty)";
  if (o === "human") return "human (uzytkownik)";
  return "none (niczyj)";
}

function body(v: Verdict): string {
  if (!v.exists && v.reservedForAi) {
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

Plik jest Twoj. Edytuj normalnie - Edit, Write, co potrzebujesz.

  - Nie przenos tu kodu, ktory nalezy do uzytkownika, zeby go "odblokowac".
  - Mozesz wywolywac dowolne publiczne metody z jego plikow.
  - Jesli refactor wymaga zmiany po jego stronie, zloz turf_request na jego plik.`;
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

  return `ZASADY - OBOWIAZUJA BEZWZGLEDNIE

Plik nie ma wpisu w manifescie, wiec jest niczyj. Domyslna odpowiedz to odmowa.

  - NIE EDYTUJESZ go.
  - NIE skladasz na niego wniosku - wniosek dotyczy tylko plikow uzytkownika.
  - Nie "przejmujesz" go sam. Wlasnosc nadaje wylacznie uzytkownik w IDE.

Co masz zrobic: powiedziec uzytkownikowi, ze ten plik jest nieoznaczony i ze musi
zdecydowac, czyj jest, zanim cokolwiek sie w nim wydarzy. Do tego czasu zajmij sie
reszta zadania.`;
}
