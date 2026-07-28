// Smoke test serwera turf-mcp. Buduje tymczasowe repo, odpala serwer przez stdio
// i przechodzi kazda sciezke werdyktu. Uruchomienie: node test/smoke.mjs
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const serverEntry = path.resolve(here, "..", "dist", "index.js");

const root = fs.mkdtempSync(path.join(os.tmpdir(), "turf-smoke-"));
fs.mkdirSync(path.join(root, ".git"));
fs.mkdirSync(path.join(root, "src"), { recursive: true });

const mojPlik = "src/Moje.java";
fs.writeFileSync(
  path.join(root, mojPlik),
  ["class Moje {", "    int a = 1;", "    int b = 2;", "    int c = 3;", "}"].join("\n")
);
fs.writeFileSync(path.join(root, "src/Niczyj.java"), "class Niczyj {}\n");

fs.mkdirSync(path.join(root, ".turf"), { recursive: true });
fs.writeFileSync(
  path.join(root, ".turf", "ownership.json"),
  JSON.stringify(
    {
      version: 1,
      files: { [mojPlik]: { owner: "human", since: new Date().toISOString(), by: "test" } },
      patterns: [],
    },
    null,
    2
  )
);

let failed = 0;
function check(name, cond, detail = "") {
  const mark = cond ? "OK  " : "FAIL";
  if (!cond) failed++;
  console.log(`${mark} ${name}${detail && !cond ? "\n       " + detail : ""}`);
}

const client = new Client({ name: "turf-smoke", version: "1.0.0" });
await client.connect(
  new StdioClientTransport({
    command: process.execPath,
    args: [serverEntry],
    env: { ...process.env, TURF_ROOT: root },
  })
);

const call = async (name, args = {}) => {
  const r = await client.callTool({ name, arguments: args });
  return r.content.map((c) => c.text ?? "").join("\n");
};

const tools = (await client.listTools()).tools.map((t) => t.name).sort();
check("cztery narzedzia", tools.length === 4, tools.join(","));
check(
  "nazwy narzedzi",
  JSON.stringify(tools) ===
    JSON.stringify(["turf_check", "turf_request", "turf_rules", "turf_status"]),
  tools.join(",")
);

const kontrakt = await call("turf_rules");
check("kontrakt wspomina o wywolywaniu", kontrakt.includes("WYWOLYWAC"));
check("kontrakt wspomina o edycji", kontrakt.includes("EDYTOWAC"));

const human = await call("turf_check", { path: mojPlik });
check("plik uzytkownika: zakaz edycji", human.includes("MOZESZ EDYTOWAC: NIE"), human);
check("plik uzytkownika: wskazuje wniosek", human.includes("turf_request"));

const niczyj = await call("turf_check", { path: "src/Niczyj.java" });
check("plik niczyj: zakaz edycji", niczyj.includes("MOZESZ EDYTOWAC: NIE"), niczyj);
check("plik niczyj: wniosek dozwolony", niczyj.includes("turf_request"), niczyj);

const nowy = await call("turf_check", { path: "src/NowyAi.java" });
check("nowy plik: zgoda", nowy.includes("MOZESZ EDYTOWAC: TAK"), nowy);
const manifest = JSON.parse(fs.readFileSync(path.join(root, ".turf/ownership.json"), "utf8"));
check(
  "nowy plik: zarezerwowany jako ai",
  manifest.files["src/NowyAi.java"]?.owner === "ai" && manifest.files["src/NowyAi.java"]?.pending === true,
  JSON.stringify(manifest.files["src/NowyAi.java"])
);

const poza = await call("turf_check", { path: path.join(os.tmpdir(), "gdziekolwiek.txt") });
check("poza repo: odmowa", poza.startsWith("ODMOWA"), poza);

const dobry = await call("turf_request", {
  path: mojPlik,
  reason: "test",
  edits: [{ lineStart: 2, lineEnd: 3, replacement: "    int a = 10;\n    int b = 20;" }],
});
check("wniosek 2 linie: przechodzi", dobry.includes("WNIOSEK ZLOZONY"), dobry);
check("wniosek: kaze nie czekac", dobry.includes("NIE CZEKAJ"));

const zaDuzy = await call("turf_request", {
  path: mojPlik,
  reason: "test",
  edits: [{ lineStart: 1, lineEnd: 5, replacement: "x" }],
});
check("wniosek 5 linii: odrzucony", zaDuzy.includes("ODRZUCONY PRZEZ WALIDACJE"), zaDuzy);

const naNiczyj = await call("turf_request", {
  path: "src/Niczyj.java",
  reason: "test",
  edits: [{ lineStart: 1, lineEnd: 1, replacement: "x" }],
});
check("wniosek na niczyj: przechodzi jak na plik uzytkownika",
  naNiczyj.includes("WNIOSEK ZLOZONY"), naNiczyj);

const naSwoj = await call("turf_request", {
  path: "src/NowyAi.java",
  reason: "test",
  edits: [{ lineStart: 1, lineEnd: 1, replacement: "x" }],
});
check("wniosek na wlasny plik: zbedny", naSwoj.includes("WNIOSEK ZBEDNY"), naSwoj);

const stan = await call("turf_status");
check("status liczy wnioski", stan.includes("WNIOSKI (2)"), stan);
check("status liczy wlasnosc", stan.includes("Uzytkownika:       1"), stan);

const zapisany = JSON.parse(
  fs.readFileSync(
    path.join(root, ".turf/requests", fs.readdirSync(path.join(root, ".turf/requests"))[0]),
    "utf8"
  )
);
check(
  "wniosek zapisal stara tresc",
  zapisany.edits[0].oldText === "    int a = 1;\n    int b = 2;",
  JSON.stringify(zapisany.edits[0].oldText)
);

await client.close();
fs.rmSync(root, { recursive: true, force: true });

console.log(failed ? `\n${failed} testow nie przeszlo` : "\nwszystko przeszlo");
process.exit(failed ? 1 : 0);
