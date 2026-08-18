package pl.kznd4niel.turf.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import pl.kznd4niel.turf.TurfService
import java.awt.Cursor
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * Jeden kolor na wszystkie znaczniki - rozroznia je napis, nie barwa.
 *
 * Pomaranczowy, bo w tej samej barwie motywy rysuja adnotacje, a fold zastepuje wlasnie
 * kod pod adnotacja. Niebieski jest zajety przez pasek zmian gita w rynience.
 */
object AiColors {
    /** Napis na foldzie, etykieta nad blokiem i licznik w rynience. */
    val ACCENT: JBColor = JBColor(0xB0690A, 0xE0A458)

    /** Tlo rozwinietego bloku i podkladka pod mysza. */
    val BACKGROUND: JBColor = JBColor(0xFDF1DF, 0x3A2E1C)
}

/** Odstep miedzy krawedzia podkladki a napisem. */
private const val PAD = 8

/**
 * Typ jest luzny naumyslnie. Klucze rozpoznaje sie po nazwie, wiec ten sam klucz widzi
 * takze poprzednia wersja wtyczki - a jej obiekt jest inna klasa mimo tej samej nazwy.
 * Bezpieczne rzutowanie zamienia to z wyjatku w zwykle "nie ma nic".
 */
private val DECOR_KEY = Key.create<Any>("turf.ai.decor")

private fun italic(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

private fun metrics(editor: Editor): FontMetrics =
    editor.contentComponent.getFontMetrics(italic(editor))

/**
 * Warstwa edytora nad blokami oznaczonymi znacznikiem AI.
 *
 * Blok ma dwa stany i oba zachowuja sie jak zwykly fold - przelacza je klikniecie w to,
 * co widac, bez zadnych strzalek w rynience:
 *
 *   zwiniety   - w stylu napisowym "12 Claude's lines folded" w miejscu kodu, w stylu
 *                licznikowym sama liczba w kolumnie numerow linii;
 *   rozwiniety - kod ma podbarwione tlo, a nad nim etykiete "Claude's 12 Lines", zeby po
 *                rozwinieciu dalej bylo widac, gdzie konczy sie Twoj kod.
 *
 * Zwijanie domyslne dotyczy otwarcia pliku, nie pisania. Blok, ktory powstal pod palcami
 * - bo wlasnie dopisales nad metoda `@Claude` - zostaje rozwiniety, bo zwijanie tego, przy
 * czym ktos wlasnie pracuje, byloby wyrywaniem kartki z reki. Zwinie sie przy nastepnym
 * otwarciu pliku.
 */
class AiDecor(private val editor: Editor) : Disposable {

    private class FoldInfo(val key: String, val block: AiBlock)

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /**
     * Klucz bloku -> czy zwiniety. Klucz idzie ze znacznika i tresci deklaracji, nie z
     * numeru linii, wiec dopisanie czegos wyzej w pliku nie gubi stanu.
     */
    private val collapsed = LinkedHashMap<String, Boolean>()

    private val folds = LinkedHashMap<CustomFoldRegion, FoldInfo>()

    /** Zwiniecia stylu licznikowego - zwykle, bez wlasnego wiersza. */
    private val plainFolds = LinkedHashMap<FoldRegion, FoldInfo>()
    private val labels = LinkedHashMap<Inlay<*>, String>()

    /** Bloki aktualnie rozwiniete - licznik w rynience potrzebuje ich rozmiaru. */
    private val expanded = LinkedHashMap<String, AiBlock>()
    private val highlighters = ArrayList<RangeHighlighter>()

    /** Warstwa z liczbami w kolumnie numerow linii. Rysuje tylko w stylu licznikowym. */
    private var numbers: AiGutterNumbers? = null

    /** Pierwszy skan to otwarcie pliku - tylko wtedy dziala zwijanie domyslne. */
    private var firstScan = true

    /** Ostatnia wartosc globalnego przelacznika. Jej zmiana przestawia wszystkie bloki. */
    private var appliedGlobal: Boolean? = null

    /** Uklad, ktory juz jest narysowany. Bez tego kazde nacisniecie klawisza go przerysowuje. */
    private var signature: String? = null

    private var counterStyle = false
    private var rebuilding = false
    private var handCursor = false

    /** Blok, nad ktorego licznikiem stoi mysz. Trzymany tu, bo czyta go tez rynienka. */
    private var hoveredKey: String? = null

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(DEBOUNCE_MS)
        }, this)

        val svc = editor.project?.service<TurfService>()
        if (svc != null) {
            val onChanged = Runnable { schedule(0) }
            svc.addListener(onChanged)
            Disposer.register(this) { svc.removeListener(onChanged) }
        }

        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) = onClick(e)
        }, this)

        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = onMove(e)
        }, this)

        (editor as? EditorEx)?.let { numbers = AiGutterNumbers.attach(it, this) }

        // Zwijanie w trakcie tworzenia edytora bywa za wczesnie - platforma dopiero
        // uklada swoje foldy. Jedna tura petli zdarzen wystarczy.
        schedule(0)
    }

    private fun schedule(delay: Int) {
        if (alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuild() }, delay)
    }

    fun blocks(): List<AiBlock> {
        val text = editor.document.immutableCharSequence
        if (text.length > MAX_CHARS) return emptyList()
        return AiScanner.scan(text)
    }

    // ------------------------------------------------------------------ licznik

    /**
     * Prostokaty licznikow, liczone na biezaco. Wiersz zwinietego bloku przesuwa sie po
     * kazdym zwinieciu czegokolwiek wyzej w pliku, wiec zapamietany prostokat i tak byly
     * by nieaktualny przy nastepnym rysowaniu.
     */
    internal fun counters(): List<Counter> {
        if (!counterStyle || editor.isDisposed) return emptyList()
        val gutter = (editor as? EditorEx)?.gutterComponentEx ?: return emptyList()
        val doc = editor.document
        // Licznik zaczyna sie tuz za kolumna numerow. Przy schowanych numerach kolumna ma
        // zerowa szerokosc i licznik i tak stanie na jej miejscu, czyli przy krawedzi.
        val textX = gutter.lineNumberAreaOffset + gutter.lineNumberAreaWidth
        val right = hoverRight(gutter, textX)
        val height = editor.lineHeight

        // Pole obejmuje caly pas rynienki, od jej lewej krawedzi po tekst - wiersz nalezy
        // w calosci do zwinietego bloku. Zaczynanie go dopiero przy numerze zostawialo z
        // lewej pasek nieobjety niczym, przez co podswietlenie wygladalo na uciete.
        val left = 0

        val out = ArrayList<Counter>()

        hostLines().forEach { (line, key) ->
            if (line >= doc.lineCount) return@forEach
            val count = countOf(key) ?: return@forEach
            val y = editor.visualLineToY(editor.offsetToVisualPosition(doc.getLineStartOffset(line)).line)
            out.add(
                Counter(
                    key, count.toString(), Rectangle(left, y, right - left, height), textX,
                    slash = true, ownChip = false, collapsed = true,
                )
            )
        }

        // Rozwiniety blok tez ma licznik - na wysokosci swojej etykiety. Bez niego zwiniecie
        // z powrotem szlo wylacznie klikiem w etykiete, a rynienka w tym wierszu stala pusta.
        labels.forEach { (inlay, key) ->
            if (!inlay.isValid) return@forEach
            val b = expanded[key] ?: return@forEach
            val bounds = inlay.bounds ?: return@forEach
            out.add(
                Counter(
                    key, b.lineCount.toString(),
                    Rectangle(left, bounds.y, right - left, bounds.height), textX,
                    slash = false, ownChip = true, collapsed = false,
                )
            )
        }
        return out
    }

    private fun countOf(key: String): Int? =
        (plainFolds.values + folds.values).firstOrNull { it.key == key }?.block?.lineCount

    internal fun toggleFromGutter(key: String) = toggle(key)

    /** Czyta to podkladka w rynience przy kazdym rysowaniu. */
    internal fun isHovered(key: String): Boolean = hoveredKey == key

    internal fun setHovered(key: String?) {
        hoveredKey = key
    }

    // ------------------------------------------------------------------ klikanie

    private fun onClick(e: EditorMouseEvent) {
        if (e.mouseEvent.button != MouseEvent.BUTTON1 || e.mouseEvent.isPopupTrigger) return
        val p = e.mouseEvent.point

        labelAt(p)?.let {
            e.consume()
            toggle(labels.getValue(it))
            return
        }
        if (e.area != EditorMouseEventArea.EDITING_AREA) return
        foldAt(p)?.let {
            e.consume()
            toggle(folds.getValue(it).key)
        }
    }

    private fun onMove(e: EditorMouseEvent) {
        val p = e.mouseEvent.point
        val over = foldAt(p) != null || labelAt(p) != null
        if (over == handCursor) return
        handCursor = over
        // Kursor lapki to jedyne, co mowi "to sie klika" - napisu nie da sie podkreslic
        // najechaniem tak, jak linku.
        (editor as? EditorEx)?.setCustomCursor(
            this,
            if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null,
        )
    }

    private fun foldAt(p: Point): CustomFoldRegion? = folds.keys.firstOrNull { region ->
        region.isValid && (region.renderer as? ClickableFold)?.hit(region, p) == true
    }

    private fun labelAt(p: Point): Inlay<*>? = labels.keys.firstOrNull { inlay ->
        inlay.isValid && inlay.bounds?.contains(p) == true
    }

    private fun toggle(key: String) {
        collapsed[key] = !(collapsed[key] ?: true)
        rebuild()
    }

    // ------------------------------------------------------------------ rysowanie

    private fun rebuild() {
        if (editor.isDisposed || rebuilding) return

        val doc = editor.document
        val svc = editor.project?.service<TurfService>()
        val found = blocks().filter { it.endLine < doc.lineCount }
        val keys = keysOf(found)
        val style = svc?.foldStyle ?: AiFoldStyle.TEXT
        val global = svc?.foldAiBlocks ?: true
        val globalChanged = appliedGlobal != null && appliedGlobal != global

        for (key in keys) {
            val prev = collapsed[key]
            collapsed[key] = when {
                globalChanged -> global
                prev != null -> prev
                // Blok znany od otwarcia pliku zwija sie domyslnie; blok, ktory dopiero
                // co powstal pod palcami - nie.
                firstScan -> global
                else -> false
            }
        }
        collapsed.keys.retainAll(keys.toSet())
        appliedGlobal = global
        firstScan = false

        val sig = style.id + "|" + found.indices.joinToString(";") {
            "${found[it].startLine}-${found[it].endLine}:${collapsed[keys[it]]}"
        }
        if (sig == signature) return
        signature = sig
        counterStyle = style == AiFoldStyle.COUNTER

        rebuilding = true
        try {
            clear()
            val toFold = ArrayList<Pair<AiBlock, String>>()
            for ((i, b) in found.withIndex()) {
                if (collapsed[keys[i]] == true) toFold.add(b to keys[i]) else expand(b, keys[i])
            }
            if (toFold.isNotEmpty()) fold(toFold, style)
        } finally {
            rebuilding = false
        }
        numbers?.refresh()
    }

    /** Bloki nierozroznialne trescia dostaja numer, zeby nie dzielily jednego stanu. */
    private fun keysOf(found: List<AiBlock>): List<String> {
        val seen = HashMap<String, Int>()
        return found.map { b ->
            val base = "${b.marker}|${b.declText}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base#$n"
        }
    }

    private fun fold(list: List<Pair<AiBlock, String>>, style: AiFoldStyle) {
        val model = editor.foldingModel as? FoldingModelEx ?: return
        val doc = editor.document
        model.runBatchFoldingOperation({
            for ((b, key) in list) {
                if (style != AiFoldStyle.COUNTER) {
                    val region = model.addCustomLinesFolding(b.startLine, b.endLine, AiFoldRenderer(b))
                    if (region != null) folds[region] = FoldInfo(key, b)
                    continue
                }

                // Styl licznikowy nie ma wlasnego wiersza: blok chowa sie w koncu linii
                // NAD nim, tak jak zwykle zwiniecie ciala metody chowa sie w jej naglowku.
                // Dzieki temu nie zostaje pusta linia, a licznik ma numer, przy ktorym
                // moze stanac.
                val host = b.startLine - 1
                val from =
                    if (host >= 0) doc.getLineEndOffset(host) else doc.getLineStartOffset(b.startLine)
                // neverExpands zdejmuje strzalke zwijania z rynienki: rozwija sie to
                // licznikiem, a nie platformowym daszkiem, wiec daszek byl drugim
                // przyciskiem do tego samego, stojacym tuz obok liczby.
                val plain = model.createFoldRegion(
                    from, doc.getLineEndOffset(b.endLine), "", null, true
                )
                if (plain != null) {
                    plain.isExpanded = false
                    plainFolds[plain] = FoldInfo(key, b)
                    continue
                }
                // Zakres zajety przez cudze zwiniecie - wtedy zostaje wlasny wiersz,
                // bo lepszy licznik na osobnej linii niz brak zwiniecia.
                val fallback = model.addCustomLinesFolding(b.startLine, b.endLine, CounterFoldRenderer)
                if (fallback != null) folds[fallback] = FoldInfo(key, b)
            }
        }, true, false)

        if (style == AiFoldStyle.COUNTER) hostLines().forEach { (line, key) -> hoverBar(line, key) }
    }

    /** Wiersz, przy ktorego numerze stoi licznik, dla kazdego zwinietego bloku. */
    private fun hostLines(): List<Pair<Int, String>> {
        val doc = editor.document
        val out = ArrayList<Pair<Int, String>>()
        plainFolds.forEach { (region, info) ->
            // Rozwiniete przez platforme, np. "Expand All" - wtedy kod widac, wiec licznik
            // nie ma czego liczyc.
            if (region.isValid && !region.isExpanded) {
                out.add(doc.getLineNumber(region.startOffset) to info.key)
            }
        }
        folds.forEach { (region, info) ->
            if (region.isValid) out.add(doc.getLineNumber(region.startOffset) to info.key)
        }
        return out
    }

    /**
     * Podkladka pod mysza nie jest rysowana przez warstwe nad rynienka, tylko przez samą
     * rynienke - jako znacznik linii na niskiej warstwie. Rynienka rysuje znaczniki w
     * kolejnosci warstw, a pasek zmian gita siedzi na 5999, wiec idzie po nas: podkladka
     * moze byc pelnej szerokosci i mimo to go nie zakryje.
     */
    private fun hoverBar(line: Int, key: String) {
        val doc = editor.document
        if (line >= doc.lineCount) return
        val hl = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(line),
            doc.getLineEndOffset(line),
            HighlighterLayer.SYNTAX,
            null,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        hl.lineMarkerRenderer = HoverBarRenderer(key, this)
        highlighters.add(hl)
    }

    private fun expand(b: AiBlock, key: String) {
        val doc = editor.document
        val start = doc.getLineStartOffset(b.startLine)
        val end = doc.getLineEndOffset(b.endLine)

        val attrs = TextAttributes().apply { backgroundColor = AiColors.BACKGROUND }
        highlighters.add(
            editor.markupModel.addRangeHighlighter(
                start, end,
                // Nad podswietleniem linii z kursorem: nizej tlo bloku znikalo dokladnie
                // tam, gdzie akurat patrzysz.
                HighlighterLayer.CARET_ROW + 1,
                attrs,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        )

        val label = "${AiMarkers.possessive(b.marker)} ${b.lineCount} Lines"
        val inlay = editor.inlayModel.addBlockElement(start, false, true, 0, LabelRenderer(label))
        if (inlay != null) {
            labels[inlay] = key
            expanded[key] = b
        }
    }

    private fun clear() {
        if (folds.isNotEmpty() || plainFolds.isNotEmpty()) {
            val model = editor.foldingModel as? FoldingModelEx
            model?.runBatchFoldingOperation({
                folds.keys.forEach { if (it.isValid) model.removeFoldRegion(it) }
                plainFolds.keys.forEach { if (it.isValid) model.removeFoldRegion(it) }
            }, true, false)
            folds.clear()
            plainFolds.clear()
        }
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        labels.keys.forEach { Disposer.dispose(it) }
        labels.clear()
        expanded.clear()
    }

    override fun dispose() {
        if (editor.getUserData(DECOR_KEY) === this) editor.putUserData(DECOR_KEY, null)
        (editor as? EditorEx)?.setCustomCursor(this, null)
        numbers?.let { AiGutterNumbers.detach(it) }
        numbers = null
        clear()
    }

    // ------------------------------------------------------------ rejestracja

    companion object {
        /** Powyzej tego rozmiaru nie skanujemy - to i tak nie jest plik do czytania. */
        private const val MAX_CHARS = 500_000

        /** Skan idzie po kazdej zmianie dokumentu, wiec z opoznieniem. */
        private const val DEBOUNCE_MS = 300

        fun of(editor: Editor): AiDecor? = editor.getUserData(DECOR_KEY) as? AiDecor

        fun attach(editor: Editor) {
            if (of(editor) != null) return
            val svc = editor.project?.service<TurfService>() ?: return
            val decor = AiDecor(editor)
            // Rodzicem jest serwis projektu, wiec wylaczenie wtyczki zdejmuje nasluchy
            // z edytora. Bez tego stara wersja dalej lapalaby klikniecia.
            try {
                Disposer.register(svc, decor)
            } catch (e: Throwable) {
                Disposer.dispose(decor)
                return
            }
            editor.putUserData(DECOR_KEY, decor)
        }

        fun detach(editor: Editor) {
            Disposer.dispose(of(editor) ?: return)
        }
    }
}

/**
 * Prawa krawedz pola licznika: sam koniec rynienki, tam gdzie zaczyna sie tekst. Podkladka
 * moze isc na cala szerokosc, bo rysuje ja rynienka przed paskiem zmian gita, a nie warstwa
 * nad nim - git ida po niej i zostaje widoczny.
 */
internal fun hoverRight(gutter: EditorGutterComponentEx, left: Int): Int =
    gutter.width.takeIf { it > left } ?: (left + 1)

/** Podkladka pod mysza, rysowana przez rynienke - stad pod paskiem zmian gita. */
private class HoverBarRenderer(private val key: String, private val decor: AiDecor) :
    LineMarkerRendererEx {

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.CUSTOM

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        if (!decor.isHovered(key)) return
        val gutter = (editor as? EditorEx)?.gutterComponentEx ?: return
        val x = 0
        val right = hoverRight(gutter, x)
        val h = if (r.height > 0) r.height else editor.lineHeight

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = AiColors.BACKGROUND
            g2.fill(
                RoundRectangle2D.Float(
                    x.toFloat(), r.y + 1f, (right - x).toFloat(), h - 2f, 6f, 6f
                )
            )
        } finally {
            g2.dispose()
        }
    }
}

/** Fold, ktory da sie rozwinac klikajac w to, co widac. */
private interface ClickableFold {
    fun hit(region: CustomFoldRegion, p: Point): Boolean
}

/**
 * Napis w miejscu zwinietego kodu. Stoi zawsze przy lewej krawedzi, niezaleznie od
 * zagniezdzenia kodu, ktory zastapil - inaczej kolejne bloki tanczylyby po szerokosci
 * ekranu i trudno bylo je zlapac wzrokiem. Klika sie go tak jak platformowe "...".
 */
private class AiFoldRenderer(private val block: AiBlock) : CustomFoldRegionRenderer, ClickableFold {

    private fun text() = "${block.lineCount} ${AiMarkers.possessive(block.marker)} lines folded"

    private fun textWidth(editor: Editor) = metrics(editor).stringWidth(text()) + 2 * PAD

    override fun calcWidthInPixels(region: CustomFoldRegion): Int = textWidth(region.editor)

    override fun calcHeightInPixels(region: CustomFoldRegion): Int = region.editor.lineHeight

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        target: Rectangle2D,
        attributes: TextAttributes,
    ) {
        val editor = region.editor
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val x = target.x.toFloat()
        val y = target.y.toFloat()
        val h = target.height.toFloat()

        g.color = AiColors.BACKGROUND
        g.fill(RoundRectangle2D.Float(x, y + 1f, textWidth(editor).toFloat(), h - 2f, 6f, 6f))

        g.font = italic(editor)
        val fm = g.fontMetrics
        g.color = AiColors.ACCENT
        g.drawString(text(), x + PAD, y + (h - fm.height) / 2 + fm.ascent)
    }

    /** Klikalny jest sam napis, nie cala szerokosc linii. */
    override fun hit(region: CustomFoldRegion, p: Point): Boolean {
        val loc = region.location ?: return false
        return p.x >= loc.x && p.x < loc.x + textWidth(region.editor) &&
            p.y >= loc.y && p.y < loc.y + region.heightInPixels
    }
}

/**
 * Zwiniety blok w stylu licznikowym nie rysuje w kodzie nic - caly slad to liczba w
 * kolumnie numerow linii, ktora rysuje AiGutterNumbers. Wiersz musi jednak istniec, bo
 * to on wyznacza y licznika.
 */
private object CounterFoldRenderer : CustomFoldRegionRenderer {
    override fun calcWidthInPixels(region: CustomFoldRegion): Int = 1
    override fun calcHeightInPixels(region: CustomFoldRegion): Int = region.editor.lineHeight
    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        target: Rectangle2D,
        attributes: TextAttributes,
    ) = Unit
}

/**
 * Etykieta nad rozwinietym blokiem, przy lewej krawedzi - tak samo jak napis na foldzie,
 * zeby jedno przechodzilo w drugie w tym samym miejscu. Klikniecie zwija blok z powrotem.
 */
private class LabelRenderer(private val label: String) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        metrics(inlay.editor).stringWidth(label) + 2 * PAD

    override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, attributes: TextAttributes) {
        val editor = inlay.editor
        (g as? Graphics2D)?.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
        )
        g.color = AiColors.BACKGROUND
        g.fillRect(region.x, region.y, region.width, region.height)
        g.font = italic(editor)
        val fm = g.fontMetrics
        g.color = AiColors.ACCENT
        g.drawString(label, region.x + PAD, region.y + (region.height - fm.height) / 2 + fm.ascent)
    }
}
