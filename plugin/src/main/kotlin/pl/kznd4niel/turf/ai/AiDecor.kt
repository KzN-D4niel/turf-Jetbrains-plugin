package pl.kznd4niel.turf.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
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

/** Jeden kolor na wszystkie znaczniki - rozroznia je napis, nie barwa. */
object AiColors {
    /** Napis na foldzie i etykieta nad rozwinietym blokiem. */
    val ACCENT: JBColor = JBColor(0x7A5AF8, 0x9B82FF)

    /** Tlo rozwinietego bloku. Musi byc na tyle blade, zeby skladnia zostala czytelna. */
    val BACKGROUND: JBColor = JBColor(0xF1EDFF, 0x2A2440)
}

/** Odstep miedzy krawedzia podkladki a napisem. */
private const val PAD = 8

private val DECOR_KEY = Key.create<AiDecor>("turf.ai.decor")

private fun italic(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

private fun metrics(editor: Editor): FontMetrics =
    editor.contentComponent.getFontMetrics(italic(editor))

/**
 * Warstwa edytora nad blokami oznaczonymi znacznikiem AI.
 *
 * Blok ma dwa stany i oba zachowuja sie jak zwykly fold - przelacza je klikniecie w sam
 * napis, bez zadnych strzalek w rynience:
 *
 *   zwiniety   - w miejscu kodu stoi "12 Claude's lines folded" w kolorze Turfa, wciete
 *                tam, gdzie stal kod, zamiast platformowego "...", ktore nie mowi ani
 *                ile, ani czyje;
 *   rozwiniety - kod ma podbarwione tlo, a nad nim etykiete "Claude's 12 Lines", zeby po
 *                rozwinieciu dalej bylo widac, gdzie konczy sie Twoj kod.
 *
 * Zwijanie domyslne dotyczy otwarcia pliku, nie pisania. Blok, ktory powstal pod palcami
 * - bo wlasnie dopisales nad metoda `@Claude` - zostaje rozwiniety, bo zwijanie tego, przy
 * czym ktos wlasnie pracuje, byloby wyrywaniem kartki z reki. Zwinie sie przy nastepnym
 * otwarciu pliku.
 */
class AiDecor(private val editor: Editor) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /**
     * Klucz bloku -> czy zwiniety. Klucz idzie ze znacznika i tresci deklaracji, nie z
     * numeru linii, wiec dopisanie czegos wyzej w pliku nie gubi stanu.
     */
    private val collapsed = LinkedHashMap<String, Boolean>()

    private val folds = LinkedHashMap<CustomFoldRegion, String>()
    private val labels = LinkedHashMap<Inlay<*>, String>()
    private val highlighters = ArrayList<RangeHighlighter>()

    /** Pierwszy skan to otwarcie pliku - tylko wtedy dziala zwijanie domyslne. */
    private var firstScan = true

    /** Ostatnia wartosc globalnego przelacznika. Jej zmiana przestawia wszystkie bloki. */
    private var appliedGlobal: Boolean? = null

    /** Uklad, ktory juz jest narysowany. Bez tego kazde nacisniecie klawisza go przerysowuje. */
    private var signature: String? = null

    private var rebuilding = false
    private var handCursor = false

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
            toggle(folds.getValue(it))
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
        region.isValid && (region.renderer as? AiFoldRenderer)?.hit(region, p) == true
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
        val found = blocks().filter { it.endLine < doc.lineCount }
        val keys = keysOf(found)
        val global = editor.project?.service<TurfService>()?.foldAiBlocks ?: true
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

        val sig = found.indices.joinToString(";") {
            "${found[it].startLine}-${found[it].endLine}:${collapsed[keys[it]]}"
        }
        if (sig == signature) return
        signature = sig

        rebuilding = true
        try {
            clear()
            val toFold = ArrayList<Pair<AiBlock, String>>()
            for ((i, b) in found.withIndex()) {
                if (collapsed[keys[i]] == true) toFold.add(b to keys[i]) else expand(b, keys[i])
            }
            if (toFold.isNotEmpty()) fold(toFold)
        } finally {
            rebuilding = false
        }
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

    private fun fold(list: List<Pair<AiBlock, String>>) {
        val model = editor.foldingModel as? FoldingModelEx ?: return
        model.runBatchFoldingOperation({
            for ((b, key) in list) {
                val region = model.addCustomLinesFolding(b.startLine, b.endLine, AiFoldRenderer(b))
                    ?: continue
                folds[region] = key
            }
        }, true, false)
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
        val inlay = editor.inlayModel.addBlockElement(start, false, true, 0, LabelRenderer(label, b.indent))
        if (inlay != null) labels[inlay] = key
    }

    private fun clear() {
        if (folds.isNotEmpty()) {
            val model = editor.foldingModel as? FoldingModelEx
            model?.runBatchFoldingOperation({
                folds.keys.forEach { if (it.isValid) model.removeFoldRegion(it) }
            }, true, false)
            folds.clear()
        }
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        labels.keys.forEach { Disposer.dispose(it) }
        labels.clear()
    }

    override fun dispose() {
        (editor as? EditorEx)?.setCustomCursor(this, null)
        clear()
    }

    // ------------------------------------------------------------ rejestracja

    companion object {
        /** Powyzej tego rozmiaru nie skanujemy - to i tak nie jest plik do czytania. */
        private const val MAX_CHARS = 500_000

        /** Skan idzie po kazdej zmianie dokumentu, wiec z opoznieniem. */
        private const val DEBOUNCE_MS = 300

        fun of(editor: Editor): AiDecor? = editor.getUserData(DECOR_KEY)

        fun attach(editor: Editor) {
            if (editor.getUserData(DECOR_KEY) != null) return
            editor.putUserData(DECOR_KEY, AiDecor(editor))
        }

        fun detach(editor: Editor) {
            val decor = editor.getUserData(DECOR_KEY) ?: return
            editor.putUserData(DECOR_KEY, null)
            Disposer.dispose(decor)
        }
    }
}

/**
 * Napis w miejscu zwinietego kodu. Stoi na wcieciu deklaracji, ma wlasna podkladke i
 * klika sie go tak jak platformowe "...".
 */
private class AiFoldRenderer(private val block: AiBlock) : CustomFoldRegionRenderer {

    private fun text() = "${block.lineCount} ${AiMarkers.possessive(block.marker)} lines folded"

    private fun indentPx(editor: Editor) = metrics(editor).charWidth(' ') * block.indent

    private fun textWidth(editor: Editor) = metrics(editor).stringWidth(text()) + 2 * PAD

    override fun calcWidthInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        return indentPx(editor) + textWidth(editor)
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int = region.editor.lineHeight

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        target: Rectangle2D,
        attributes: TextAttributes,
    ) {
        val editor = region.editor
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val x = target.x.toFloat() + indentPx(editor)
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
    fun hit(region: CustomFoldRegion, p: Point): Boolean {
        val loc = region.location ?: return false
        val x = loc.x + indentPx(region.editor)
        return p.x >= x && p.x < x + textWidth(region.editor) &&
            p.y >= loc.y && p.y < loc.y + region.heightInPixels
    }
}

/** Etykieta nad rozwinietym blokiem. Klikniecie w nia zwija blok z powrotem. */
private class LabelRenderer(private val label: String, private val indent: Int) :
    EditorCustomElementRenderer {

    private fun indentPx(inlay: Inlay<*>) = metrics(inlay.editor).charWidth(' ') * indent

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        indentPx(inlay) + metrics(inlay.editor).stringWidth(label) + 2 * PAD

    override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, attributes: TextAttributes) {
        val editor = inlay.editor
        (g as? Graphics2D)?.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
        )
        val x = region.x + indentPx(inlay)
        g.color = AiColors.BACKGROUND
        g.fillRect(x, region.y, region.width - indentPx(inlay), region.height)
        g.font = italic(editor)
        val fm = g.fontMetrics
        g.color = AiColors.ACCENT
        g.drawString(label, x + PAD, region.y + (region.height - fm.height) / 2 + fm.ascent)
    }
}
