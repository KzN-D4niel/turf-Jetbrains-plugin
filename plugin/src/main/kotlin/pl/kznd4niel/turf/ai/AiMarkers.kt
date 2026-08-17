package pl.kznd4niel.turf.ai

/**
 * Znaczniki, po ktorych poznaje sie kod wygenerowany przez model.
 *
 * Lista jest sztywna i wspolna dla wszystkich jezykow. Wykrywanie idzie po tekscie, nie
 * po drzewie PSI, wiec dziala tak samo w IntelliJ, PyCharmie i WebStormie - i tak samo
 * dla `@Claude` nad metoda w Javie, `# @GPT` nad funkcja w Pythonie i `// @AI` nad
 * funkcja w TypeScripcie. Cena jest taka, ze to heurystyka: zdanie z `@Claude` w srodku
 * komentarza tez sie zalapie, jesli stoi w osobnej linii nad deklaracja.
 */
object AiMarkers {

    /** Kolejnosc ma znaczenie tylko o tyle, ze dluzsze nazwy musza byc przed krotszymi. */
    val NAMES: List<String> = listOf(
        "GeneratedByAI",
        "AIGenerated",
        "Claude",
        "Gemini",
        "Copilot",
        "Codex",
        "GPT",
        "AI",
    )

    /**
     * Linia zlozona wylacznie ze znacznika, z opcjonalnym prefiksem komentarza
     * (dwa ukosniki, krzyzyk, dwa myslniki, gwiazdka, otwarcie komentarza blokowego,
     * otwarcie komentarza XML) i opcjonalnym nawiasem z argumentami.
     * Wymog "cala linia" odsiewa `@Claude` wtracone w srodku zdania.
     */
    private val ALTERNATIVE = NAMES.joinToString("|")

    private val LINE = Regex(
        "^\\s*(?://+|#+|--|\\*|/\\*+|<!--)?\\s*@(" + ALTERNATIVE +
            ")\\b\\s*(?:\\([^)]*\\))?\\s*(?:\\*/|-->)?\\s*\$",
        RegexOption.IGNORE_CASE,
    )

    /** @return nazwa znacznika w kanonicznej pisowni albo null, gdy linia nim nie jest. */
    fun markerOf(line: String): String? {
        val m = LINE.find(line) ?: return null
        val found = m.groupValues[1]
        return NAMES.firstOrNull { it.equals(found, ignoreCase = true) } ?: found
    }

    /** Dopelniacz do napisu na foldzie: "3 Claude's lines folded". */
    fun possessive(marker: String): String =
        if (marker.endsWith("s", ignoreCase = true)) "$marker'" else "$marker's"
}
