package pl.kznd4niel.turf.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import pl.kznd4niel.turf.TurfService
import java.awt.Component
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
import javax.swing.Icon

/**
 * Jeden kolor na wszystkie znaczniki - rozroznia je napis, nie barwa.
 *
 * Pomaranczowy, bo w tej samej barwie motywy rysuja adnotacje, a fold zastepuje wlasnie
 * kod pod adnotacja. Niebieski jest zajety przez pasek zmian gita w rynience.
 */
object AiColors {
    /** Napis na foldzie i etykieta nad rozwinietym blokiem. */
    val ACCENT: JBColor = JBColor(0xB0690A, 0xE0A458)

    /** Tlo rozwinietego bloku. Musi byc na tyle blade, zeby skladnia zostala czytelna. */
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
        model.runBatchFoldingOperation({
            for ((b, key) in list) {
                val renderer =
                    if (style == AiFoldStyle.COUNTER) CounterFoldRenderer(b, key, this)
                    else AiFoldRenderer(b)
                val region = model.addCustomLinesFolding(b.startLine, b.endLine, renderer) ?: continue
                folds[region] = key
            }
        }, true, false)
    }

    /** Wolane z ikony w rynience - stad widoczne poza klasa. */
    internal fun expandFromGutter(key: String) = toggle(key)

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
        if (editor.getUserData(DECOR_KEY) === this) editor.putUserData(DECOR_KEY, null)
        (editor as? EditorEx)?.setCustomCursor(this, null)
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

/** Fold, ktory da sie rozwinac klikajac w to, co widac. */
private interface ClickableFold {
    fun hit(region: CustomFoldRegion, p: Point): Boolean
}

/**
 * Zwiniety blok w stylu licznikowym nie rysuje w kodzie nic - caly slad to liczba przy
 * numerach linii.
 *
 * Ikona idzie przez renderer, a nie przez highlighter na linii, bo wiersz zwinietego
 * bloku nie ma juz numeru linii ani ikon z podswietlen: platforma pyta o rynienke
 * wylacznie ten renderer. Highlighter na tej linii nie rysowal nic i blok znikal bez
 * sladu poza przeskokiem numeracji.
 *
 * Sam wiersz zostaje klikalny na szerokosc trzech znakow, zeby blok nie stal sie
 * nierozwijalny, gdyby ikona gdzies nie doszla.
 */
private class CounterFoldRenderer(
    private val block: AiBlock,
    private val key: String,
    private val decor: AiDecor,
) : CustomFoldRegionRenderer, ClickableFold {

    override fun calcWidthInPixels(region: CustomFoldRegion): Int =
        metrics(region.editor).charWidth(' ') * 3

    override fun calcHeightInPixels(region: CustomFoldRegion): Int = region.editor.lineHeight

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        target: Rectangle2D,
        attributes: TextAttributes,
    ) = Unit

    override fun calcGutterIconRenderer(region: CustomFoldRegion): GutterIconRenderer =
        CounterGutterIcon(block, key, decor, region.editor)

    override fun hit(region: CustomFoldRegion, p: Point): Boolean {
        val loc = region.location ?: return false
        return p.x >= loc.x && p.x < loc.x + region.widthInPixels &&
            p.y >= loc.y && p.y < loc.y + region.heightInPixels
    }
}

/**
 * Sama liczba zwinietych linii, tuz obok numerow linii.
 *
 * Czcionka, jej rozmiar i wysokosc wiersza sa brane z edytora - dokladnie tak, jak
 * rysuje sie numery linii - zeby od numeru rozniala ja wylacznie barwa. Szerokosc jest
 * przyciasna naumyslnie: ikona idzie wtedy do lewej krawedzi swojego paska, czyli tak
 * blisko kolumny numerow, jak platforma pozwala.
 */
private class CountIcon(private val text: String, editor: Editor) : Icon {

    private val font: Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    private val fm: FontMetrics = editor.contentComponent.getFontMetrics(font)
    private val height: Int = editor.lineHeight

    override fun getIconWidth(): Int = fm.stringWidth(text) + JBUI.scale(2)

    override fun getIconHeight(): Int = height

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = font
            g2.color = AiColors.ACCENT
            val m = g2.fontMetrics
            g2.drawString(text, x, y + (height - m.height) / 2 + m.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** Licznik w rynience jest w tym stylu jedynym przyciskiem, wiec to on rozwija blok. */
private class CounterGutterIcon(
    private val block: AiBlock,
    private val key: String,
    private val decor: AiDecor,
    editor: Editor,
) : GutterIconRenderer() {

    private val icon = CountIcon(block.lineCount.toString(), editor)

    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String =
        "${block.lineCount} linii od ${block.marker}. Kliknij, zeby rozwinac."

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = decor.expandFromGutter(key)
    }

    override fun equals(other: Any?): Boolean =
        other is CounterGutterIcon && other.key == key && other.block == block

    override fun hashCode(): Int = key.hashCode() * 31 + block.hashCode()
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
