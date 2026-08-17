package pl.kznd4niel.turf

enum class Owner(val id: String, val label: String) {
    HUMAN("human", "Ty"),
    AI("ai", "AI"),
    /**
     * Teren wspolny: AI wolno pisac bez wniosku, ale drobiazgowo - przestawic kolejnosc,
     * dolozyc mala metode - i zawsze zostawic adnotacje znacznikowa, zeby bylo widac,
     * co dopisala. Kod docelowo nalezy do czlowieka, wiec to jego konwencje obowiazuja.
     */
    SHARED("shared", "wspolne"),
    NONE("none", "niczyj");

    companion object {
        fun from(id: String?): Owner = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Granulacja wlasnosci, wybierana per projekt i trzymana w manifescie, zeby serwer MCP
 * orzekal dokladnie tak samo jak drzewo projektu.
 *
 *   FILE    - wlasnosc nadaje sie plikowi; czytane jest `files`.
 *   PACKAGE - wlasnosc nadaje sie katalogowi, plik dziedziczy ja z najblizszego
 *             katalogu w gore; czytane jest `dirs`.
 *
 * Tryby sa rozlaczne. Nieaktywna warstwa zostaje na dysku nietknieta, wiec powrot do
 * poprzedniego trybu przywraca to, co bylo, zamiast mieszac obie granulacje.
 */
enum class TurfMode(val id: String, val label: String) {
    FILE("file", "pliki"),
    PACKAGE("package", "pakiety");

    companion object {
        fun from(id: String?): TurfMode = entries.firstOrNull { it.id == id } ?: FILE
    }
}

class FileEntry {
    @JvmField var owner: String = "none"
    @JvmField var since: String = ""
    @JvmField var by: String = ""
    @JvmField var pending: Boolean? = null
    /**
     * Wpis pliku, ktory ma byc czytany takze w trybie pakietowym - swiadomy wyjatek od
     * wlasnosci pakietu. Bez tej flagi warstwy zostaja rozlaczne, wiec przelaczenie
     * trybow nie wskrzesza oznaczen zrobionych w tym drugim.
     */
    @JvmField var override: Boolean? = null
}

class PatternEntry {
    @JvmField var glob: String = ""
    @JvmField var owner: String = "none"
}

class ManifestData {
    @JvmField var version: Int = 1
    /** Brak pola = manifest sprzed trybow, czyli tryb plikowy. */
    @JvmField var mode: String? = null
    @JvmField var files: MutableMap<String, FileEntry> = LinkedHashMap()
    /** Klucz to sciezka katalogu wzgledem korzenia; "" znaczy cale repozytorium. */
    @JvmField var dirs: MutableMap<String, FileEntry> = LinkedHashMap()
    @JvmField var patterns: MutableList<PatternEntry> = ArrayList()
}

class EditSpec {
    @JvmField var lineStart: Int = 0
    @JvmField var lineEnd: Int = 0
    @JvmField var replacement: String = ""
    @JvmField var oldText: String? = null
}

class EditRequest {
    @JvmField var id: String = ""
    @JvmField var path: String = ""
    @JvmField var reason: String = ""
    @JvmField var edits: MutableList<EditSpec> = ArrayList()
    @JvmField var status: String = "pending"
    @JvmField var createdAt: String = ""
    @JvmField var decidedAt: String? = null
    @JvmField var note: String? = null

    val zakres: String
        get() = edits.joinToString(", ") { "${it.lineStart}-${it.lineEnd}" }
}

/** Katalog nadrzedny sciezki repo-wzglednej. "" dla czegos lezacego w korzeniu. */
fun parentDir(rel: String): String {
    val i = rel.lastIndexOf('/')
    return if (i < 0) "" else rel.substring(0, i)
}

/**
 * Minimalny glob: ** dowolna glebokosc, * w obrebie segmentu, ? jeden znak.
 * Trzymany zgodnie z ta sama implementacja po stronie serwera MCP.
 */
fun globToRegex(glob: String): Regex {
    val out = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                out.append(".*")
                i++
                if (i + 1 < glob.length && glob[i + 1] == '/') i++
            }
            c == '*' -> out.append("[^/]*")
            c == '?' -> out.append("[^/]")
            c in ".+^\${}()|[]\\" -> out.append('\\').append(c)
            else -> out.append(c)
        }
        i++
    }
    out.append('$')
    return Regex(out.toString(), RegexOption.IGNORE_CASE)
}
