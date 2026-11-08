package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSplashTest {
    @Test
    fun shapeIs12x8AndHasSixtyFourCells() {
        assertEquals(INTRO_ROWS, INTRO_SHAPE.size)
        INTRO_SHAPE.forEach { assertEquals(INTRO_COLS, it.length) }
        assertEquals(64, buildIntroCells().size)
    }

    @Test
    fun cellsAreUniqueAndAllLandBeforeTheImpact() {
        val cells = buildIntroCells()
        assertEquals(cells.size, cells.map { it.col to it.row }.toSet().size)
        for (c in cells) {
            assertTrue("sel (${c.col},${c.row}) mendarat ${c.arrival} > benturan", c.arrival <= INTRO_IMPACT_MS + 0.01f)
            assertTrue(c.delay >= 0f && c.duration > 0f)
        }
    }

    @Test
    fun introEndsAfterTheImpactWithRoomForTheWordmark() {
        assertTrue(INTRO_END_MS > INTRO_IMPACT_MS + 1500f)
    }
}
