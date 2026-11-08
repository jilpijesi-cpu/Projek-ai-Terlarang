package com.mythos.app.ui

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotPatternsTest {
    @Test
    fun hasTwentyTwoPatterns() {
        assertEquals(22, DOT_PATTERNS)
    }

    @Test
    fun patternsAddedInV64LoopSeamlessly() {
        // u=0 dan u=1 harus sama persis supaya tidak ada lompatan saat polanya berulang atau diganti.
        for (p in 16 until DOT_PATTERNS) for (i in 0 until 3) {
            val a = dotPattern(p, i, 0f)
            val b = dotPattern(p, i, 1f)
            assertEquals("pola $p bola $i x", a.x, b.x, 0.01f)
            assertEquals("pola $p bola $i y", a.y, b.y, 0.01f)
            assertEquals("pola $p bola $i r", a.r, b.r, 0.01f)
        }
    }

    @Test
    fun everyPatternStaysInsideTheLoaderCanvasAndIsFinite() {
        // Kanvas LoaderDots 15,33 x 20 dp: setengah lebar 7,67; u dari -0,3 (peralihan antar pola) sampai 1.
        for (p in 0 until DOT_PATTERNS) for (i in 0 until 3) for (n in -30..100) {
            val u = n / 100f
            val d = dotPattern(p, i, u)
            val tag = "pola $p bola $i u=$u"
            assertTrue("$tag tidak hingga", d.x.isFinite() && d.y.isFinite() && d.r.isFinite())
            assertTrue("$tag keluar kanvas (x=${d.x}, r=${d.r})", abs(d.x) + d.r <= 7.7f)
            assertTrue("$tag keluar kanvas (y=${d.y}, r=${d.r})", abs(d.y) + d.r <= 9.5f)
            assertTrue("$tag jari-jari ${d.r}", d.r in 1.0f..2.6f)
        }
    }

    @Test
    fun newPatternsMoveSmoothlyWithoutJumps() {
        for (p in 7 until DOT_PATTERNS) for (i in 0 until 3) for (n in -30 until 100) {
            val a = dotPattern(p, i, n / 100f)
            val b = dotPattern(p, i, (n + 1) / 100f)
            val step = hypot(b.x - a.x, b.y - a.y)
            assertTrue("pola $p bola $i u=${n / 100f} melompat $step dp", step <= 1.0f)
        }
    }

    @Test
    fun jugglingKeepsTheThreeSlotsOccupied() {
        // Di u=0 dan u=1 ketiga bola tepat di tiga slot (-5,32; 0; 5,32) pada garis datar.
        for (u in listOf(0f, 1f)) {
            val xs = (0 until 3).map { dotPattern(7, it, u) }.onEach { assertEquals(0f, it.y, 0.001f) }.map { it.x }.sorted()
            assertEquals(-5.32f, xs[0], 0.01f)
            assertEquals(0f, xs[1], 0.01f)
            assertEquals(5.32f, xs[2], 0.01f)
        }
    }
}
