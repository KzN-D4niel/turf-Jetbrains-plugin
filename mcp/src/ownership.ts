import * as fs from "node:fs";
import * as path from "node:path";

export type Owner = "human" | "ai" | "none";

export interface FileEntry {
  owner: Owner;
  since: string;
  by: string;
  /** Rezerwacja zrobiona przy turf_check na nieistniejacy plik. Znika, jesli plik nie powstanie. */
  pending?: boolean;
}

export interface PatternEntry {
  glob: string;
  owner: Owner;
}

export interface Manifest {
  version: number;
  files: Record<string, FileEntry>;
  patterns: PatternEntry[];
}

const MANIFEST_DIR = ".turf";
const MANIFEST_NAME = "ownership.json";

/** Po tylu godzinach rezerwacja bez pliku na dysku jest kasowana. */
const PENDING_TTL_H = 24;

export function findRoot(start: string): string {
  const forced = process.env.TURF_ROOT;
  if (forced && fs.existsSync(forced)) return path.resolve(forced);

  let dir = path.resolve(start);
  for (;;) {
    if (fs.existsSync(path.join(dir, ".git"))) return dir;
    const up = path.dirname(dir);
    if (up === dir) return path.resolve(start);
    dir = up;
  }
}

export function manifestPath(root: string): string {
  return path.join(root, MANIFEST_DIR, MANIFEST_NAME);
}

/** Sciezka repo-wzgledna, zawsze z ukosnikami w przod. */
export function toRel(root: string, p: string): string {
  const abs = path.isAbsolute(p) ? p : path.resolve(root, p);
  return path.relative(root, abs).split(path.sep).join("/");
}

export function toAbs(root: string, rel: string): string {
  return path.resolve(root, rel);
}

function emptyManifest(): Manifest {
  return { version: 1, files: {}, patterns: [] };
}

export function load(root: string): Manifest {
  const mp = manifestPath(root);
  if (!fs.existsSync(mp)) return emptyManifest();
  try {
    const raw = JSON.parse(fs.readFileSync(mp, "utf8"));
    const m: Manifest = {
      version: typeof raw.version === "number" ? raw.version : 1,
      files: raw.files && typeof raw.files === "object" ? raw.files : {},
      patterns: Array.isArray(raw.patterns) ? raw.patterns : [],
    };
    return pruneStalePending(root, m);
  } catch {
    // Uszkodzony manifest to nie jest powod, zeby nagle wszystko przepuszczac.
    // Pusty manifest = wszystko "none" = AI i tak nic nie ruszy.
    return emptyManifest();
  }
}

function pruneStalePending(root: string, m: Manifest): Manifest {
  const cutoff = Date.now() - PENDING_TTL_H * 3600_000;
  let changed = false;
  for (const [rel, e] of Object.entries(m.files)) {
    if (!e.pending) continue;
    if (fs.existsSync(toAbs(root, rel))) {
      delete e.pending;
      changed = true;
      continue;
    }
    if (Date.parse(e.since) < cutoff) {
      delete m.files[rel];
      changed = true;
    }
  }
  if (changed) save(root, m);
  return m;
}

export function save(root: string, m: Manifest): void {
  const mp = manifestPath(root);
  fs.mkdirSync(path.dirname(mp), { recursive: true });
  const tmp = mp + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(m, null, 2) + "\n", "utf8");
  fs.renameSync(tmp, mp);
}

/** Minimalny glob: ** (dowolna glebokosc), * (w obrebie segmentu), ? (jeden znak). */
function globToRegExp(glob: string): RegExp {
  let out = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*") {
      if (glob[i + 1] === "*") {
        out += ".*";
        i++;
        if (glob[i + 1] === "/") i++;
      } else {
        out += "[^/]*";
      }
    } else if (c === "?") {
      out += "[^/]";
    } else {
      out += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
    }
  }
  return new RegExp("^" + out + "$", "i");
}

export interface Resolution {
  owner: Owner;
  /** Skad wynika ta wlasnosc: wpis pliku, wzorzec, albo brak. */
  source: string;
  entry?: FileEntry;
}

export function resolveOwner(m: Manifest, rel: string): Resolution {
  const exact = m.files[rel] ?? findCaseInsensitive(m.files, rel);
  if (exact) return { owner: exact.owner, source: "wpis pliku", entry: exact };

  // Ostatni pasujacy wzorzec wygrywa - dzieki temu bardziej szczegolowe
  // reguly dopisuje sie na koniec listy.
  let hit: PatternEntry | undefined;
  for (const p of m.patterns) {
    if (globToRegExp(p.glob).test(rel)) hit = p;
  }
  if (hit) return { owner: hit.owner, source: `wzorzec ${hit.glob}` };

  return { owner: "none", source: "brak wpisu" };
}

function findCaseInsensitive(
  files: Record<string, FileEntry>,
  rel: string
): FileEntry | undefined {
  const lower = rel.toLowerCase();
  for (const [k, v] of Object.entries(files)) {
    if (k.toLowerCase() === lower) return v;
  }
  return undefined;
}

export function setOwner(
  root: string,
  rel: string,
  owner: Owner,
  by: string,
  pending = false
): void {
  const m = load(root);
  if (owner === "none") {
    delete m.files[rel];
  } else {
    m.files[rel] = {
      owner,
      since: new Date().toISOString(),
      by,
      ...(pending ? { pending: true } : {}),
    };
  }
  save(root, m);
}

export function counts(m: Manifest): Record<Owner, number> {
  const c: Record<Owner, number> = { human: 0, ai: 0, none: 0 };
  for (const e of Object.values(m.files)) c[e.owner]++;
  return c;
}
