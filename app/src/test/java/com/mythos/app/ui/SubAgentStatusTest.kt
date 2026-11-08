package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentStatusTest {
    @Test
    fun labelDropsTaskPrefix() {
        assertEquals("audit keamanan", subAgentLabel("Task audit keamanan"))
        assertEquals("sub-agen", subAgentLabel("Task"))
    }

    @Test
    fun noPhraseWhenNothingRuns() {
        assertNull(SubAgentStatus().phrase(0))
    }

    @Test
    fun singleSubAgentShowsOnlyItsLabel() {
        val s = SubAgentStatus().started("audit A")
        assertEquals("Sub-agen: audit A", s.phrase(0))
        assertEquals("Sub-agen: audit A", s.phrase(5))
    }

    @Test
    fun textRotatesBetweenRunningSubAgentsWithProgress() {
        val s = SubAgentStatus().started("audit A").started("audit B").started("audit C")
        assertEquals("Sub-agen: audit A · 0/3 selesai", s.phrase(0))
        assertEquals("Sub-agen: audit B · 0/3 selesai", s.phrase(1))
        val after = s.finished("audit A")
        assertEquals(2, after.running.size)
        assertEquals("Sub-agen: audit B · 1/3 selesai", after.phrase(0))
        assertEquals("Sub-agen: audit C · 1/3 selesai", after.phrase(1))
    }

    @Test
    fun finishingEverythingHidesTheLine() {
        val s = SubAgentStatus().started("a").started("b").finished("b").finished("a")
        assertNull(s.phrase(0))
        assertEquals(2, s.done)
    }

    @Test
    fun finishWithUnknownLabelStillCountsOne() {
        val s = SubAgentStatus().started("a").finished("")
        assertNull(s.phrase(0))
        assertEquals(1, s.done)
    }
}
