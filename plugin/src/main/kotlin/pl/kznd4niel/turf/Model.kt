package pl.kznd4niel.turf

enum class Owner(val id: String, val label: String) {
    HUMAN("human", "Ty"),
    AI("ai", "AI"),
    NONE("none", "niczyj");

    companion object {
        fun from(id: String?): Owner = entries.firstOrNull { it.id == id } ?: NONE
    }
}

class FileEntry {
    @JvmField var owner: String = "none"
    @JvmField var since: String = ""
    @JvmField var by: String = ""
    @JvmField var pending: Boolean? = null
}

class PatternEntry {
    @JvmField var glob: String = ""
    @JvmField var owner: String = "none"
}

class ManifestData {
    @JvmField var version: Int = 1
    @JvmField var files: MutableMap<String, FileEntry> = LinkedHashMap()
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

/** Zapis z zewnatrz do pliku, ktory nie nalezy do AI. */
data class Violation(
    val path: String,
    val owner: Owner,
    val at: Long,
)

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
