package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    private fun result(i: Int, size: Int) =
        Msg(Role.USER, "<tool_result id=\"toolu_$i\" name=\"Read\" is_error=\"false\">\n" + "y".repeat(size) + "\n</tool_result>")

    private fun history(steps: Int, size: Int): List<Msg> {
        val h = ArrayList<Msg>()
        h.add(Msg(Role.USER, "tugas awal"))
        for (i in 1..steps) {
            h.add(Msg(Role.ASSISTANT, "narasi $i"))
            h.add(result(i, size))
        }
        return h
    }

    @Test
    fun smallHistoryIsReturnedUntouched() {
        val h = history(3, 100)
        val p = ContextManager.compactWithInfo(h, 60_000, 12)
        assertFalse(p.compacted)
        assertTrue(p.view === h)
    }

    @Test
    fun compactionReportsHowManyRecentMessagesStayIntact() {
        val h = history(10, 12_000)
        val p = ContextManager.compactWithInfo(h, 60_000, 12)
        assertTrue(p.compacted)
        assertTrue("keep=${p.keep}", p.keep in 4..10)
        assertEquals(h.last().text, p.view.last().text)               // yang terbaru utuh
        assertEquals(h.first().text, p.view.first().text)             // pesan pertama selalu utuh
        assertTrue(p.view[2].text.contains("content cleared"))        // hasil lama dipadatkan
        assertTrue(p.view.sumOf { it.text.length } <= 60_000)
    }

    @Test
    fun oldCompactApiStillReturnsTheSameView() {
        val h = history(10, 12_000)
        val a = ContextManager.compactWithInfo(h, 60_000, 12).view.map { it.text }
        val b = ContextManager.compact(h, 60_000, 12).map { it.text }
        assertEquals(a, b)
    }

    @Test
    fun errorResultsAreClippedNotCleared() {
        val h = ArrayList<Msg>()
        h.add(Msg(Role.USER, "tugas"))
        h.add(Msg(Role.ASSISTANT, "coba"))
        h.add(Msg(Role.USER, "<tool_result id=\"toolu_1\" name=\"Edit\" is_error=\"true\">\n" + "e".repeat(5_000) + "\n</tool_result>"))
        repeat(6) { i -> h.add(Msg(Role.ASSISTANT, "n$i")); h.add(result(i + 2, 12_000)) }
        val p = ContextManager.compactWithInfo(h, 30_000, 4)
        assertTrue(p.compacted)
        assertTrue(p.view[2].text.contains("is_error=\"true\""))
        assertFalse(p.view[2].text.contains("content cleared"))
        assertTrue(p.view[2].text.length < 1_000)
    }
}
