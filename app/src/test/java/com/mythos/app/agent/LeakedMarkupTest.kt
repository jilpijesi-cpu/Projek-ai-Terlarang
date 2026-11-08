package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dari rekaman layar (openrouter/free): model gaya Llama membungkus panggilan alat dengan <|start_code|> dan menulis
 * <tool_result> karangannya sendiri. Tidak satu pun boleh tampil di layar, dan panggilan sesudah hasil karangan tidak dijalankan.
 */
class LeakedMarkupTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" } as Parsed.Reply

    private val leakedCall =
        "<|start_header_id|>tool_use<|end_header_id|>\n<|start_paragraph|>\n<|start_code|>\n" +
            "{\"name\": \"Task\", \"input\": {\"description\": \"Analisis PDF\"}}\n" +
            "</|start_code|>\n<|end_header_id|>\n<|start_paragraph|>"

    @Test
    fun loosePlainCallInsideSpecialTokensLeavesNoMarkupInTheNarration() {
        val r = parse("Saya cek PDF-nya.\n$leakedCall")
        assertEquals(listOf("Task"), r.calls.map { it.name })
        assertEquals("Saya cek PDF-nya.", r.text)
    }

    @Test
    fun twoLeakedCallsKeepBothCallsAndNoTokens() {
        val r = parse("$leakedCall\n$leakedCall")
        assertEquals(2, r.calls.size)
        assertFalse(r.text.contains("<|"))
        assertFalse(r.text.contains("tool_use"))
    }

    @Test
    fun fabricatedToolResultAndLaterCallsAreDropped() {
        val raw = "Saya cari dulu.\n<tool_use>{\"name\": \"WebSearch\", \"input\": {\"query\": \"surat\"}}</tool_use>\n" +
            "<tool_result id=\"toolu_1\" name=\"WebSearch\" is_error=\"false\">\n[Hasil pencarian web (Bing)]\n</tool_result>\n" +
            "<tool_use>{\"name\": \"Read\", \"input\": {\"file_path\": \"a.txt\"}}</tool_use>"
        val r = parse(raw)
        assertEquals(listOf("WebSearch"), r.calls.map { it.name })
        assertEquals("Saya cari dulu.", r.text)
    }

    @Test
    fun llamaStyleFabricatedResultAfterALooseCallIsDropped() {
        val r = parse(
            "Saya cari.\n{\"name\": \"WebSearch\", \"input\": {\"query\": \"x\"}}\n" +
                "<|start_header_id|>tool_result<|end_header_id|>\n[Hasil pencarian web (Bing)]\n1. id.wikipedia.org"
        )
        assertEquals(listOf("WebSearch"), r.calls.map { it.name })
        assertEquals("Saya cari.", r.text)
    }

    @Test
    fun streamingPreviewStopsAtTheFabricatedResult() {
        assertEquals("Halo", Protocol.visibleText("Halo\n<tool_result id=\"x\">\nkarangan"))
        assertEquals("Halo", Protocol.visibleText("Halo\n<|start_header_id|>tool_result<|end_header_id|>\nkarangan"))
    }

    @Test
    fun mentionsAndFileBodiesAreNotFabrication() {
        assertEquals(-1, Protocol.fakeResultCut("Lihat `<tool_result>` di dokumen"))
        assertEquals(-1, Protocol.fakeResultCut("Contoh:\n```\n<tool_result id=1>\n```\nSelesai"))
        assertEquals(-1, Protocol.fakeResultCut("<tool_use>{\"name\":\"Write\",\"input\":{\"content\":\"a\n<tool_result id=1>\nb\"}}</tool_use>"))
        assertEquals(-1, Protocol.fakeResultCut("Tidak ada hasil alat di sini."))
    }

    @Test
    fun realMarkerIsFoundAtItsLineStart() {
        val t = "Halo\n  <tool_result id=\"a\">"
        assertTrue(Protocol.fakeResultCut(t) == t.indexOf("<tool_result"))
    }

    @Test
    fun pdfPagePickerDefaultsFiltersAndLimits() {
        assertEquals(listOf(1, 2, 3), pickPdfPages(null, 3, 6))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), pickPdfPages(emptyList(), 20, 6))
        assertEquals(listOf(7, 2), pickPdfPages(listOf(7, 0, 2, 7, 99, -1), 10, 6))
        assertEquals(listOf(1, 2), pickPdfPages(listOf(1, 2, 3, 4), 10, 2))
        assertEquals(emptyList<Int>(), pickPdfPages(listOf(50), 3, 6))
        assertEquals(emptyList<Int>(), pickPdfPages(null, 0, 6))
        assertEquals(emptyList<Int>(), pickPdfPages(null, 5, 0))
    }
}
