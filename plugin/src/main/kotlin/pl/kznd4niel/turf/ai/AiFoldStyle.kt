package pl.kznd4niel.turf.ai

/**
 * Jak wyglada zwiniety blok AI. Wybor jest per projekt i zapisuje sie miedzy sesjami.
 *
 *   TEXT    - napis "12 Claude's lines folded" w miejscu kodu. Mowi wszystko, ale zajmuje
 *             linie i widac go z drugiego konca ekranu.
 *   COUNTER - w kodzie nie ma nic, a jedyny slad to kolorowa liczba zwinietych linii przy
 *             numerze linii. Dla kogos, kto chce czytac swoj kod bez przypominania na
 *             kazdym kroku, ze obok siedzi model.
 */
enum class AiFoldStyle(val id: String, val label: String) {
    TEXT("text", "napis w kodzie"),
    COUNTER("counter", "liczba w rynience");

    companion object {
        fun from(id: String?): AiFoldStyle = entries.firstOrNull { it.id == id } ?: TEXT
    }
}
