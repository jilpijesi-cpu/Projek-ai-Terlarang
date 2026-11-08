package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTraceTest {
    private var now = 0L

    private fun fresh() {
        now = 1_000L
        LatencyTrace.useClock { now }
        LatencyTrace.begin()
    }

    @Test
    fun ringkasanMemuatSemuaTahap() {
        fresh()
        now += 600; LatencyTrace.ready()
        LatencyTrace.modelCall()
        LatencyTrace.retry("HTTP 429 sibuk", 1_500)
        now += 6_000; LatencyTrace.modelToken()
        now += 200; LatencyTrace.visibleText()
        now += 1_000
        val s = LatencyTrace.finish()
        assertTrue(s, s.contains("persiapan 0,6 dtk"))
        assertTrue(s, s.contains("token model pertama 6,6 dtk"))
        assertTrue(s, s.contains("teks pertama 6,8 dtk"))
        assertTrue(s, s.contains("total 7,8 dtk"))
        assertTrue(s, s.contains("coba ulang 1x (1,5 dtk menunggu: HTTP 429 sibuk)"))
    }

    @Test
    fun hanyaTokenPertamaYangDicatat() {
        fresh()
        now += 100; LatencyTrace.modelToken()
        now += 900; LatencyTrace.modelToken()
        val s = LatencyTrace.finish()
        assertTrue(s, s.contains("token model pertama 0,1 dtk"))
    }

    @Test
    fun beginMengosongkanGiliranSebelumnya() {
        fresh()
        LatencyTrace.retry("x", 1_000)
        fresh()
        assertTrue(!LatencyTrace.finish().contains("coba ulang"))
    }

    @Test
    fun keepDanSummaryOf() {
        fresh()
        assertNull(LatencyTrace.summaryOf(7))
        LatencyTrace.keep(7, "abc")
        assertEquals("abc", LatencyTrace.summaryOf(7))
    }
}
