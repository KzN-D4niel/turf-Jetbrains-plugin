#!/usr/bin/env node
import * as fs from "node:fs";
import * as path from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import {
  findRoot,
  load,
  resolveOwner,
  setOwner,
  toAbs,
  toRel,
  counts,
} from "./ownership.js";
import { contract, verdictText, MAX_REQUEST_LINES, type Verdict } from "./rules.js";
import * as requests from "./requests.js";

const ROOT = findRoot(process.cwd());

function text(s: string) {
  return { content: [{ type: "text" as const, text: s }] };
}

/** Sciezka poza repo to zawsze odmowa - granica konczy sie na korzeniu projektu. */
function resolveInRepo(p: string): { rel: string; abs: string } | null {
  const abs = path.isAbsolute(p) ? path.resolve(p) : path.resolve(ROOT, p);
  const rel = toRel(ROOT, abs);
  if (rel.startsWith("..") || path.isAbsolute(rel)) return null;
  return { rel, abs };
}

const server = new McpServer({ name: "turf", version: "1.0.0" });

server.registerTool(
  "turf_rules",
  {
    title: "Turf: kontrakt",
    description:
      "Zwraca pelny kontrakt podzialu repozytorium na kod czlowieka i kod AI. " +
      "Wywolaj raz na poczatku sesji, zanim tkniesz jakikolwiek plik.",
    inputSchema: {},
  },
  async () => text(contract())
);

server.registerTool(
  "turf_check",
  {
    title: "Turf: sprawdz plik",
    description:
      "OBOWIAZKOWE przed kazda modyfikacja pliku. Zwraca wlasciciela i twarde zasady " +
      "dla tej sciezki. Samo w sobie NIC nie zapisuje w pliku - to orzeczenie, nie kanal " +
      "zapisu. Dla nieistniejacej sciezki rezerwuje ja jako plik AI.",
    inputSchema: {
      path: z
        .string()
        .describe("Sciezka do pliku, bezwzgledna albo wzgledem korzenia repozytorium."),
    },
  },
  async ({ path: p }) => {
    const r = resolveInRepo(p);
    if (!r) {
      return text(
        `ODMOWA\n\nSciezka ${p} lezy poza repozytorium ${ROOT}.\n` +
          `Turf obejmuje wylacznie to repozytorium. Poza nim nie masz orzeczenia, ` +
          `wiec traktuj to jak zakaz i zapytaj uzytkownika.`
      );
    }

    const exists = fs.existsSync(r.abs);
    const m = load(ROOT);
    const res = resolveOwner(m, r.rel);

    let reservedForAi = false;
    let owner = res.owner;
    let source = res.source;

    if (!exists && owner === "none") {
      setOwner(ROOT, r.rel, "ai", "turf_check (rezerwacja nowego pliku)", true);
      reservedForAi = true;
      owner = "ai";
      source = "rezerwacja nowego pliku";
    }

    const v: Verdict = {
      rel: r.rel,
      exists,
      owner,
      source,
      canEdit: owner === "ai",
      canCreate: !exists && owner === "ai",
      reservedForAi,
    };
    return text(verdictText(v, r.abs));
  }
);

server.registerTool(
  "turf_request",
  {
    title: "Turf: wniosek o zmiane",
    description:
      `Sklada wniosek o zmiane w pliku uzytkownika. Maksymalnie ${MAX_REQUEST_LINES} linijki ` +
      `lacznie. Nie zmienia pliku - wniosek trafia do IDE, gdzie uzytkownik go przyjmuje ` +
      `albo odrzuca. Po zlozeniu wniosku NIE CZEKAJ, tylko rob dalej reszte zadania.`,
    inputSchema: {
      path: z.string().describe("Plik uzytkownika, ktorego dotyczy wniosek."),
      reason: z
        .string()
        .describe("Po co ta zmiana. Jedno-dwa zdania, konkretnie."),
      edits: z
        .array(
          z.object({
            lineStart: z.number().int().describe("Pierwsza linia zakresu, 1-indeksowana."),
            lineEnd: z.number().int().describe("Ostatnia linia zakresu, wlacznie."),
            replacement: z
              .string()
              .describe("Nowa tresc tych linii. Pusty string oznacza usuniecie."),
          })
        )
        .describe(`Zmiany. Lacznie maksymalnie ${MAX_REQUEST_LINES} linii.`),
    },
  },
  async ({ path: p, reason, edits }) => {
    const r = resolveInRepo(p);
    if (!r) return text(`ODMOWA\n\nSciezka ${p} lezy poza repozytorium ${ROOT}.`);

    const m = load(ROOT);
    const res = resolveOwner(m, r.rel);

    if (res.owner === "ai") {
      return text(
        `WNIOSEK ZBEDNY\n\n${r.rel} jest Twoj. Edytuj go normalnie przez Edit albo Write.`
      );
    }
    // Brak wpisu = wlasnosc uzytkownika, wiec wniosek przechodzi tak samo jak na
    // plik jawnie oznaczony. Osobna kategoria "niczyj" z zakazem wnioskowania tworzyla
    // martwy stan: plik, ktorego nie da sie ani tknac, ani o niego poprosic.
    if (!fs.existsSync(r.abs)) {
      return text(`ODMOWA\n\n${r.rel} nie istnieje, wiec nie ma czego zmieniac.`);
    }

    const bad = requests.validate(edits);
    if (bad) {
      return text(
        `WNIOSEK ODRZUCONY PRZEZ WALIDACJE\n\n${bad}\n\n` +
          `Limit ${MAX_REQUEST_LINES} linii jest twardy. Jesli zmiana jest wieksza, ` +
          `zaimplementuj ja u siebie i podepnij sie do kodu uzytkownika wywolaniem metody.`
      );
    }

    const copy = edits.map((e) => ({ ...e }));
    requests.captureOld(ROOT, r.rel, copy);
    const created = requests.create(ROOT, { path: r.rel, reason, edits: copy });

    return text(
      `WNIOSEK ZLOZONY\n\nid: ${created.id}\nplik: ${r.rel}\nlinie: ` +
        copy.map((e) => `${e.lineStart}-${e.lineEnd}`).join(", ") +
        `\n\nUzytkownik zobaczy go w IDE w oknie Turf.\n\n` +
        `NIE CZEKAJ na decyzje. Zajmij sie reszta zadania. Wynik sprawdzisz przez ` +
        `turf_status, kiedy bedzie Ci potrzebny.`
    );
  }
);

server.registerTool(
  "turf_status",
  {
    title: "Turf: stan",
    description:
      "Podsumowanie podzialu repozytorium i lista Twoich wnioskow wraz z decyzjami " +
      "uzytkownika. Tu sprawdzasz, czy zlozony wczesniej wniosek zostal przyjety.",
    inputSchema: {},
  },
  async () => {
    const m = load(ROOT);
    const c = counts(m);
    const rs = requests.list(ROOT);

    const lines: string[] = [
      `REPOZYTORIUM: ${ROOT}`,
      ``,
      `WLASNOSC`,
      `  Twoje (ai):        ${c.ai}`,
      `  Uzytkownika:       ${c.human}`,
      `  Wzorce:            ${m.patterns.length}`,
      `  Wszystko poza tym jest bez wpisu, czyli traktowane jak uzytkownika:`,
      `  nie edytujesz, ale mozesz zlozyc wniosek.`,
      ``,
      `WNIOSKI (${rs.length})`,
    ];

    if (!rs.length) {
      lines.push(`  brak`);
    } else {
      for (const r of rs) {
        const zakres = r.edits.map((e) => `${e.lineStart}-${e.lineEnd}`).join(",");
        lines.push(`  [${r.status}] ${r.id}`);
        lines.push(`      ${r.path} linie ${zakres}`);
        if (r.note) lines.push(`      komentarz uzytkownika: ${r.note}`);
      }
    }
    return text(lines.join("\n"));
  }
);

async function main() {
  if (!fs.existsSync(path.join(ROOT, ".git"))) {
    process.stderr.write(
      `[turf] Uwaga: ${ROOT} nie wyglada na repozytorium git.\n`
    );
  }
  process.stderr.write(`[turf] korzen: ${ROOT}\n`);
  await server.connect(new StdioServerTransport());
}

main().catch((e) => {
  process.stderr.write(`[turf] blad startu: ${e?.stack ?? e}\n`);
  process.exit(1);
});
