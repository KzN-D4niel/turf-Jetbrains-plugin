package pl.kznd4niel.turf.ai

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import pl.kznd4niel.turf.TurfService
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import javax.swing.Icon

/** Jeden kolor na wszystkie znaczniki - rozroznia je napis, nie barwa. */
object AiColors {
    /** Napis na foldzie, etykieta nad blokiem i pasek przy krawedzi. */
    val ACCENT: JBColor = JBColor(0x7A5AF8, 0x9B82FF)

    /** Tlo rozwinietego bloku. Musi byc na tyle blade, zeby skladnia zostala czytelna. */
    val BACKGROUND: JBColor = JBColor(0xF1EDFF, 0x2A2440)
}

private val DECOR_KEY = Key.create<AiDecor>("turf.ai.decor")

/**
 * Warstwa edytora nad blokami oznaczonymi znacznikiem AI.
 *
 * Blok ma dwa stany i oba sa widoczne na pierwszy rzut oka:
 *
 *   zwiniety  - w miejscu kodu stoi wlasny napis "12 Claude's lines folded" w kolorze
 *               Turfa, zamiast platformowego "...", ktore niczego nie mowi;
 *   rozwiniety - kod ma podbarwione tlo, a nad nim wisi etykieta "Claude's 12 Lines",
 *               zeby po rozwinieciu dalej bylo widac, gdzie konczy sie Twoj kod.
 *
 * W obu stanach przy lewej krawedzi siedzi przycisk, ktory ten stan przelacza. To jest
 * fold Turfa, nie platformowy: platformowe zwijanie zostaje nietkniete i dziala obok.
 */
class AiDecor(private val editor: Editor) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Klucz to linia startu bloku - przezywa przeskanowanie po edycji. */
    private val collapsed = HashMap<Int, Boolean>()

    private val folds = ArrayList<CustomFoldRegion>()
    private val highlighters = ArrayList<RangeHighlighter>()
    private val inlays = ArrayList<Inlay<*>>()

    /** Chroni przed reagowaniem na zmiany foldow, ktore sami wlasnie robimy. */
    private var rebuilding = false

    /** Ostatnia wartosc globalnego przelacznika. Jej zmiana przestawia wszystkie bloki. */
    private var appliedGlobal: Boolean? = null

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule()
        }, this)

        val svc = editor.project?.service<TurfService>()
        if (svc != null) {
            val onChanged = Runnable { schedule() }
            svc.addListener(onChanged)
            Disposer.register(this) { svc.removeListener(onChanged) }
        }

        rebuild()
    }

    private fun schedule() {
        if (alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuild() }, DEBOUNCE_MS)
    }

    fun toggle(startLine: Int) {
        collapsed[startLine] = !(collapsed[startLine] ?: true)
        rebuild()
    }

    fun blocks(): List<AiBlock> {
        val text = editor.document.immutableCharSequence
        if (text.length > MAX_CHARS) return emptyList()
        return AiScanner.scan(text)
    }

    private fun rebuild() {
        if (editor.isDisposed || rebuilding) return
        rebuilding = true
        try {
            clear()
            val doc = editor.document
            val defaultCollapsed = editor.project?.service<TurfService>()?.foldAiBlocks ?: true
            val found = blocks()
            // Bloki, ktorych juz nie ma, nie moga trzymac stanu w nieskonczonosc.
            collapsed.keys.retainAll(found.map { it.startLine }.toSet())
            // Przelaczenie globalne dotyczy takze plikow otwartych wczesniej, inaczej
            // jeden skrot dawalby dwa rozne wyniki zaleznie od kolejnosci otwierania.
            if (appliedGlobal != null && appliedGlobal != defaultCollapsed) {
                collapsed.keys.forEach { collapsed[it] = defaultCollapsed }
            }
            appliedGlobal = defaultCollapsed

            val toFold = ArrayList<AiBlock>()
            for (b in found) {
                if (b.endLine >= doc.lineCount) continue
                val isCollapsed = collapsed.getOrPut(b.startLine) { defaultCollapsed }
                if (isCollapsed) toFold.add(b) else decorateExpanded(b)
            }
            if (toFold.isNotEmpty()) foldAll(toFold)
        } finally {
            rebuilding = false
        }
    }

    private fun foldAll(list: List<AiBlock>) {
        val model = editor.foldingModel as? FoldingModelEx ?: return
        model.runBatchFoldingOperation({
            for (b in list) {
                val region = model.addCustomLinesFolding(
                    b.startLine, b.endLine, AiFoldRenderer(b, this)
                ) ?: continue
                folds.add(region)
            }
        }, true, false)
    }

    private fun decorateExpanded(b: AiBlock) {
        val doc = editor.document
        val start = doc.getLineStartOffset(b.startLine)
        val end = doc.getLineEndOffset(b.endLine)

        val attrs = TextAttributes().apply { backgroundColor = AiColors.BACKGROUND }
        val hl = editor.markupModel.addRangeHighlighter(
            start, end,
            // Pod zaznaczeniem i pod podswietleniem bledow: to tlo ma informowac,
            // a nie przykrywac.
            HighlighterLayer.CARET_ROW - 1,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        hl.gutterIconRenderer = ToggleGutterIcon(
            b, this, AllIcons.Actions.Collapseall, "Turf: zwin ${AiMarkers.possessive(b.marker)} blok"
        )
        highlighters.add(hl)

        val label = "${AiMarkers.possessive(b.marker)} ${b.lineCount} Lines"
        val inlay = editor.inlayModel.addBlockElement(
            start, false, true, 0, LabelRenderer(label)
        )
        if (inlay != null) inlays.add(inlay)
    }

    private fun clear() {
        if (folds.isNotEmpty()) {
            val model = editor.foldingModel as? FoldingModelEx
            model?.runBatchFoldingOperation({
                folds.forEach { if (it.isValid) model.removeFoldRegion(it) }
            }, true, false)
            folds.clear()
        }
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        inlays.forEach { Disposer.dispose(it) }
        inlays.clear()
    }

    override fun dispose() {
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
            val decor = AiDecor(editor)
            editor.putUserData(DECOR_KEY, decor)
        }

        fun detach(editor: Editor) {
            val decor = editor.getUserData(DECOR_KEY) ?: return
            editor.putUserData(DECOR_KEY, null)
            Disposer.dispose(decor)
        }
    }
}

/** Napis w miejscu zwinietego kodu - to on zastepuje platformowe "...". */
private class AiFoldRenderer(
    private val block: AiBlock,
    private val decor: AiDecor,
) : CustomFoldRegionRenderer {

    private fun text() =
        "${block.lineCount} ${AiMarkers.possessive(block.marker)} lines folded"

    private fun font(editor: Editor): Font =
        editor.colorsScheme.getFont(EditorFontType.ITALIC)

    override fun calcWidthInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        val fm = editor.contentComponent.getFontMetrics(font(editor))
        return fm.stringWidth(text()) + 4 * editor.colorsScheme.editorFontSize
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int =
        region.editor.lineHeight

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
        g.fill(Rectangle2D.Float(x, y, target.width.toFloat(), h))
        g.color = AiColors.ACCENT
        // Pionowa belka: bez niej blady prostokat gubi sie w tle edytora.
        g.fill(Rectangle2D.Float(x, y, 2f, h))

        g.font = font(editor)
        val fm = g.fontMetrics
        val baseline = y + (h - fm.height) / 2 + fm.ascent
        g.drawString(text(), x + 10, baseline)
    }

    override fun calcGutterIconRenderer(region: CustomFoldRegion): GutterIconRenderer =
        ToggleGutterIcon(
            block, decor, AllIcons.Actions.Expandall,
            "Turf: rozwin ${AiMarkers.possessive(block.marker)} blok",
        )
}

/** Etykieta nad rozwinietym blokiem. */
private class LabelRenderer(private val label: String) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fm = editor.contentComponent.getFontMetrics(
            editor.colorsScheme.getFont(EditorFontType.ITALIC)
        )
        return fm.stringWidth(label) + 20
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, attributes: TextAttributes) {
        val editor = inlay.editor
        (g as? Graphics2D)?.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
        )
        g.color = AiColors.BACKGROUND
        g.fillRect(region.x, region.y, region.width, region.height)
        g.color = AiColors.ACCENT
        g.fillRect(region.x, region.y, 2, region.height)
        g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
        val fm = g.fontMetrics
        g.drawString(label, region.x + 10, region.y + (region.height - fm.height) / 2 + fm.ascent)
    }
}

/** Przycisk przy krawedzi edytora: jedno klikniecie przelacza fold Turfa. */
private class ToggleGutterIcon(
    private val block: AiBlock,
    private val decor: AiDecor,
    private val icon: Icon,
    private val tooltip: String,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String = tooltip

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = decor.toggle(block.startLine)
    }

    override fun equals(other: Any?): Boolean =
        other is ToggleGutterIcon && other.block == block && other.icon === icon

    override fun hashCode(): Int = block.hashCode() * 31 + icon.hashCode()
}
