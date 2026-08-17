package pl.kznd4niel.turf.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Skaner jest heurystyka, wiec test pilnuje przede wszystkim tego, gdzie blok sie konczy.
 * Zle ustalony koniec jest gorszy niz brak wykrycia: zwinelby cudzy kod.
 */
class AiScannerTest {

    @Test
    fun `java metoda konczy sie na klamrze`() {
        val src = """
            class A {
                @Claude
                int suma(int a, int b) {
                    return a + b;
                }

                int moje() { return 0; }
            }
        """.trimIndent()

        val b = AiScanner.scan(src).single()
        assertEquals("Claude", b.marker)
        assertEquals(1, b.startLine)
        assertEquals(4, b.endLine)
        assertEquals(4, b.lineCount)
    }

    @Test
    fun `zagniezdzone klamry nie ucinaja bloku za wczesnie`() {
        val src = """
            class A {
                // @GPT
                void f() {
                    if (x) { g(); }
                    while (y) { h(); }
                }
            }
        """.trimIndent()

        val b = AiScanner.scan(src).single()
        assertEquals("GPT", b.marker)
        assertEquals(5, b.endLine)
    }

    @Test
    fun `klamra w napisie nie liczy sie do zagniezdzenia`() {
        val src = """
            class A {
                // @AI
                void f() {
                    log("}");
                }
                void moje() {}
            }
        """.trimIndent()

        assertEquals(4, AiScanner.scan(src).single().endLine)
    }

    @Test
    fun `python bierze sie wcieciem`() {
        val src = """
            class A:
                # @Gemini
                def suma(self, a, b):
                    return a + b

                def moje(self):
                    return 1
        """.trimIndent()

        val b = AiScanner.scan(src).single()
        assertEquals("Gemini", b.marker)
        assertEquals(1, b.startLine)
        assertEquals(3, b.endLine)
    }

    @Test
    fun `pole bez ciala to jedna linia`() {
        val src = """
            class A {
                @Claude
                private int licznik = 0;
                private int moje = 1;
            }
        """.trimIndent()

        assertEquals(2, AiScanner.scan(src).single().endLine)
    }

    @Test
    fun `znacznik w srodku zdania nie liczy sie`() {
        val src = """
            // Tu @Claude cos poprawil, ale to tylko notatka.
            void f() {
                g();
            }
        """.trimIndent()

        assertEquals(emptyList<AiBlock>(), AiScanner.scan(src))
    }

    @Test
    fun `dwa bloki obok siebie sa rozdzielone`() {
        val src = """
            // @Claude
            function a() {
                return 1;
            }
            // @GPT
            function b() {
                return 2;
            }
        """.trimIndent()

        val bs = AiScanner.scan(src)
        assertEquals(2, bs.size)
        assertEquals(0 to 3, bs[0].startLine to bs[0].endLine)
        assertEquals(4 to 7, bs[1].startLine to bs[1].endLine)
    }

    @Test
    fun `znacznik z argumentami tez sie liczy`() {
        assertEquals("Claude", AiMarkers.markerOf("    @Claude(\"refactor\")"))
        assertEquals("GeneratedByAI", AiMarkers.markerOf("# @GeneratedByAI"))
        assertNull(AiMarkers.markerOf("    @Override"))
        assertNull(AiMarkers.markerOf("    int aiCount = 0;"))
    }
}
