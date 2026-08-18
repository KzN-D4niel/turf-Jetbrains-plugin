package pl.kznd4niel.turf.ai

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Licznik zwinietego bloku, dopisany do numeru linii nad nim: "14 / 5".
 *
 * @param rect  pole klikalne i podswietlane - od konca kolumny numerow az do paska zmian
 *              gita. Sam numer linii zostaje poza nim, bo nalezy do widocznej linii, a nie
 *              do zwinietego bloku.
 * @param textX miejsce, w ktorym zaczyna sie " / " - tuz za numerem linii.
 */
internal class Counter(
    val key: String,
    val text: String,
    /** Pole lapiace mysz. Przy rozwinietym bloku obejmuje wiersz kodu i wiersz etykiety. */
    val rect: Rectangle,
    /**
     * Kawalek podkladki rysowany przez ta warstwe. Null tam, gdzie rysuje ja rynienka -
     * czyli nad wierszami kodu, zeby nie przykryc numeru linii ani paska zmian gita.
     */
    val chipRect: Rectangle?,
    val textX: Int,
    /** Gora wiersza, w ktorym stoi liczba - nie zawsze gora calego pola. */
    val textTop: Int,
    /** Ukosnik ma sens tylko obok numeru linii; wiersz etykiety numeru nie ma. */
    val slash: Boolean,
    val collapsed: Boolean,
)

/**
 * Warstwa rysujaca liczbe zwinietych linii w kolumnie numerow linii.
 *
 * Rynienki nie da sie poprosic o numer w wierszu zwinietego bloku ani o numer w innym
 * kolorze - wiec ten numer rysuje sie sam, na wierzchu rynienki. Zeby byl nie do
 * odroznienia od pozostalych, wszystko jest brane stamtad, skad bierze to platforma:
 *
 *   czcionka  - EditorFontType.PLAIN ze schematu, powiekszona o `editor.gutter.linenumber
 *               .font.size.delta`, dokladnie jak w getFontForLineNumbers();
 *   x         - prawa krawedz kolumny numerow minus szerokosc napisu, czyli to samo
 *               wyrownanie do prawej;
 *   linia bazowa - gorna krawedz wiersza plus editor.getAscent().
 *
 * Rozni sie wylacznie kolorem, bo tylko po nim ma byc widac, ze to nie jest numer linii.
 *
 * Komponent lezy na calej rynience, ale `contains` przepuszcza go tylko nad samymi
 * licznikami. Dzieki temu reszta rynienki dziala jak zawsze, a najechanie i klikniecie
 * lapie sie dokladnie na tym prostokacie, ktory sie podswietla - nie na calym wierszu.
 */
internal class AiGutterNumbers(private val editor: EditorEx, private val decor: AiDecor) :
    JComponent() {

    private var hovered: String? = null

    /** Czy mysz stoi na wierszu kodu bloku (wtedy swieca sie oba wiersze), czy na etykiecie. */
    private var hoveredOnHost = true

    /** Trzymany, zeby dalo sie go zdjac - inaczej zostalby na rynience po wtyczce. */
    private val onGutterResized = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) = syncBounds()
    }

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val c = cellAt(e.x, e.y) ?: return
                decor.toggleFromGutter(c.key)
            }

            override fun mouseMoved(e: MouseEvent) {
                hover(e)
                passToGutter(e)
            }

            override fun mouseEntered(e: MouseEvent) {
                hover(e)
                passToGutter(e)
            }

            override fun mouseExited(e: MouseEvent) = setHovered(null)

            /**
             * Z wiersza kodu widac caly blok, wiec swieca sie oba wiersze. Z wiersza
             * etykiety - juz tylko ona: kod jest wtedy rozwiniety i sam sie broni.
             */
            private fun hover(e: MouseEvent) {
                val c = cellAt(e.x, e.y)
                setHovered(c, c?.chipRect?.contains(e.x, e.y) != true)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun setHovered(c: Counter?, onHost: Boolean = true) {
        if (hovered == c?.key && onHost == hoveredOnHost) return
        hovered = c?.key
        hoveredOnHost = onHost
        decor.setHovered(c?.key, onHost)
        toolTipText = c?.let {
            if (it.collapsed) "${it.text} linii zwinietego kodu AI. Kliknij, zeby rozwinac."
            else "${it.text} linii kodu AI. Kliknij, zeby zwinac."
        }
        // Podkladke rysuje rynienka, wiec to ona musi sie odswiezyc, nie sama warstwa.
        parent?.repaint() ?: repaint()
    }

    /**
     * Oddaje rynience ruch myszy, ale zawsze na wysokosci kolumny numerow linii.
     *
     * Ta warstwa zjada zdarzenia nad licznikiem, wiec rynienka - a przez nia edytor -
     * przestawala je dostawac i jej wlasny stan zamarzal na ostatnim wierszu, jaki
     * widziala: duch punktu wstrzymania zostawal zapalony wiersz wyzej. Przekazanie ruchu
     * odmraza ja, a przyciecie x do kolumny numerow sprawia, ze widzi ruch poza obszarem
     * znacznikow - czyli nie zapala tego ducha na naszym wierszu.
     */
    private fun passToGutter(e: MouseEvent) {
        val gutter = editor.gutterComponentEx
        if (parent !== gutter) return
        // Pas zwijania, bo w nowym interfejsie punkt wstrzymania stawia sie klikajac
        // zarowno w numery linii, jak i w ikony - ruch podany tam nie gasil ducha, tylko
        // go zapalal. Pas zwijania jest dla niego obojetny.
        val x = (gutter.foldingAreaOffset + 1).coerceIn(0, (gutter.width - 1).coerceAtLeast(0))
        gutter.dispatchEvent(
            MouseEvent(gutter, MouseEvent.MOUSE_MOVED, e.`when`, e.modifiersEx, x, e.y, 0, false)
        )
    }

    private fun cellAt(x: Int, y: Int): Counter? =
        decor.counters().firstOrNull { it.rect.contains(x, y) }

    /** Poza licznikami komponent jest przezroczysty dla myszy - inaczej zaslonilby rynienke. */
    override fun contains(x: Int, y: Int): Boolean = cellAt(x, y) != null

    override fun paintComponent(g: Graphics) {
        val counters = decor.counters()
        if (counters.isEmpty()) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = lineNumberFont(editor)
            val fm = g2.fontMetrics
            // Ukosnik jest tylko przecinkiem miedzy dwiema liczbami, wiec ma byc bledszy
            // od obu - inaczej ciagnie oko na siebie zamiast je przepuscic dalej.
            val gray = ColorUtil.withAlpha(
                editor.colorsScheme.getColor(EditorColors.LINE_NUMBERS_COLOR) ?: JBColor.GRAY,
                0.5,
            )
            for (c in counters) {
                val baseline = c.textTop + editor.ascent
                // Wiersz etykiety nie ma pod soba linii kodu, wiec nie ma tez czego
                // przykryc - podkladke rysuje wtedy ta warstwa, a nie rynienka. Prostokat
                // pelnej wysokosci i bez zaokraglen, zeby stykal sie z podkladem etykiety
                // w jedna plaszczyzne, a nie dwa osobne ksztalty.
                val chip = c.chipRect
                if (chip != null && c.key == hovered) {
                    g2.color = AiColors.BACKGROUND
                    g2.fillRect(chip.x, chip.y, chip.width, chip.height)
                }
                // Ukosnik w barwie numerow, liczba w barwie Turfa: numer linii i licznik
                // stoja obok siebie, wiec tylko kolor mowi, ktore jest ktore.
                if (c.slash) {
                    g2.color = gray
                    g2.drawString(SEPARATOR, c.textX, baseline)
                }
                g2.color = AiColors.ACCENT
                // Liczba stoi w tej samej kolumnie niezaleznie od ukosnika, zeby liczniki
                // zwinietych i rozwinietych blokow tworzyly jeden pion.
                g2.drawString(c.text, c.textX + fm.stringWidth(SEPARATOR), baseline)
            }
        } finally {
            g2.dispose()
        }
    }

    fun refresh() {
        syncBounds()
        repaint()
    }

    private fun syncBounds() {
        val gutter = editor.gutterComponentEx
        if (parent !== gutter) return
        val want = Rectangle(0, 0, gutter.width, gutter.height)
        if (bounds != want) bounds = want
    }

    companion object {
        /** To, co oddziela numer linii od licznika. Odstepy sa czescia napisu. */
        const val SEPARATOR = " / "

        /** Odtworzone z EditorGutterComponentImpl.getFontForLineNumbers(). */
        fun lineNumberFont(editor: Editor): Font {
            val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            val delta = runCatching {
                AdvancedSettings.getInt("editor.gutter.linenumber.font.size.delta")
            }.getOrDefault(0)
            return base.deriveFont(maxOf(1f, base.size2D + delta))
        }

        fun attach(editor: EditorEx, decor: AiDecor): AiGutterNumbers {
            val gutter = editor.gutterComponentEx
            val overlay = AiGutterNumbers(editor, decor)
            // Indeks 0 to wierzch: rynienka rysuje najpierw siebie, potem dzieci.
            gutter.add(overlay, 0)
            gutter.addComponentListener(overlay.onGutterResized)
            overlay.syncBounds()
            return overlay
        }

        fun detach(overlay: AiGutterNumbers) {
            val parent = overlay.parent ?: return
            parent.removeComponentListener(overlay.onGutterResized)
            parent.remove(overlay)
            SwingUtilities.invokeLater { parent.repaint() }
        }
    }
}
