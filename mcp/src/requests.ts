import * as fs from "node:fs";
import * as path from "node:path";
import { MAX_REQUEST_LINES } from "./rules.js";
import { toAbs } from "./ownership.js";

export interface EditSpec {
  /** 1-indeksowane, wlacznie z obiema granicami. */
  lineStart: number;
  lineEnd: number;
  /** Nowa tresc tych linii. Pusty string = usuniecie. */
  replacement: string;
  /** Tresc zastana w chwili skladania wniosku - do wykrycia, ze plik sie zmienil. */
  oldText?: string;
}

export interface EditRequest {
  id: string;
  path: string;
  reason: string;
  edits: EditSpec[];
  status: "pending" | "accepted" | "rejected";
  createdAt: string;
  decidedAt?: string;
  note?: string;
}

function dir(root: string): string {
  return path.join(root, ".turf", "requests");
}

export function validate(edits: EditSpec[]): string | null {
  if (!edits.length) return "Wniosek bez zadnej zmiany.";
  if (edits.length > MAX_REQUEST_LINES) {
    return `Za duzo blokow: ${edits.length}. Limit to ${MAX_REQUEST_LINES}.`;
  }
  let total = 0;
  for (const e of edits) {
    if (!Number.isInteger(e.lineStart) || !Number.isInteger(e.lineEnd)) {
      return "lineStart i lineEnd musza byc liczbami calkowitymi.";
    }
    if (e.lineStart < 1 || e.lineEnd < e.lineStart) {
      return `Nieprawidlowy zakres linii ${e.lineStart}-${e.lineEnd}.`;
    }
    total += e.lineEnd - e.lineStart + 1;
  }
  if (total > MAX_REQUEST_LINES) {
    return `Wniosek obejmuje ${total} linii. Limit to ${MAX_REQUEST_LINES}. ` +
      `Wieksza zmiana po stronie uzytkownika nie przechodzi przez wniosek - ` +
      `napisz to u siebie i podepnij sie metoda.`;
  }
  const newLines = edits.reduce(
    (n, e) => n + (e.replacement === "" ? 0 : e.replacement.split("\n").length),
    0
  );
  if (newLines > MAX_REQUEST_LINES) {
    return `Nowa tresc ma ${newLines} linii. Limit to ${MAX_REQUEST_LINES}.`;
  }
  return null;
}

/** Dokleja do wniosku tresc zastana, zeby dalo sie pokazac diff i wykryc rozjazd. */
export function captureOld(root: string, rel: string, edits: EditSpec[]): void {
  const abs = toAbs(root, rel);
  if (!fs.existsSync(abs)) return;
  const lines = fs.readFileSync(abs, "utf8").split("\n");
  for (const e of edits) {
    e.oldText = lines.slice(e.lineStart - 1, e.lineEnd).join("\n");
  }
}

export function create(root: string, req: Omit<EditRequest, "id" | "status" | "createdAt">): EditRequest {
  const d = dir(root);
  fs.mkdirSync(d, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const slug = req.path.split("/").pop()?.replace(/[^a-zA-Z0-9._-]/g, "_") ?? "plik";
  const full: EditRequest = {
    id: `${stamp}-${slug}`,
    status: "pending",
    createdAt: new Date().toISOString(),
    ...req,
  };
  const tmp = path.join(d, full.id + ".json.tmp");
  fs.writeFileSync(tmp, JSON.stringify(full, null, 2) + "\n", "utf8");
  fs.renameSync(tmp, path.join(d, full.id + ".json"));
  return full;
}

export function list(root: string): EditRequest[] {
  const d = dir(root);
  if (!fs.existsSync(d)) return [];
  const out: EditRequest[] = [];
  for (const f of fs.readdirSync(d)) {
    if (!f.endsWith(".json")) continue;
    try {
      out.push(JSON.parse(fs.readFileSync(path.join(d, f), "utf8")));
    } catch {
      // pojedynczy uszkodzony wniosek nie moze wywalic calej listy
    }
  }
  return out.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}
