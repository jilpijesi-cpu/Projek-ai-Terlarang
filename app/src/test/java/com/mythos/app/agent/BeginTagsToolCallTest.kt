package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format <|tool_call_begin|><|tool_name|>Nama<|/tool_name|><|tool_input|>{json}<|/tool_call_begin|> dari rekaman layar (dibungkus pagar ```tool_use). */
class BeginTagsToolCallTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" }

    @Test
    fun fencedCallFromRecordingBecomesCall() {
        val raw = "```tool_use\n<|tool_call_begin|>\n<|tool_name|>\nViewImages\n<|/tool_name|>\n<|tool_input|>\n" +
            "{\n  \"images\": [\"uploads/Surat Penyelesaian Pengaduan Konsumen atas nama Ibu Agenia _1_.pdf\"],\n  \"pages\": [7, 8]\n}\n" +
            "<|/tool_call_begin|>\n```"
        val r = parse(raw) as Parsed.Reply
        assertEquals(1, r.calls.size)
        assertEquals("ViewImages", r.calls[0].name)
        assertTrue(r.calls[0].input.getJSONArray("images").getString(0).endsWith("_1_.pdf"))
        assertEquals(2, r.calls[0].input.getJSONArray("pages").length())
        assertTrue(r.text, r.text.isBlank())
    }

    @Test
    fun plainCallKeepsNarration() {
        val r = parse("Saya lihat dulu.<|tool_call_begin|><|tool_name|>Read<|/tool_name|><|tool_input|>{\"file_path\": \"a.kt\"}<|/tool_call_begin|>") as Parsed.Reply
        assertEquals("Saya lihat dulu.", r.text)
        assertEquals(1, r.calls.size)
        assertEquals("Read", r.calls[0].name)
        assertEquals("a.kt", r.calls[0].input.getString("file_path"))
    }

    @Test
    fun truncatedJsonIsInvalidAndFlaggedTruncated() {
        val r = parse("<|tool_call_begin|><|tool_name|>Write<|/tool_name|><|tool_input|>{\"file_path\": \"a.md\", \"content\": \"abc")
        assertTrue(r is Parsed.Invalid)
        assertTrue((r as Parsed.Invalid).truncated)
    }

    @Test
    fun blockAndItsFenceAreHiddenWhileStreaming() {
        assertEquals("Baik.", Protocol.visibleText("Baik.\n```tool_use\n<|tool_call_begin|>\n<|tool_name|>Re"))
        assertEquals("Baik.", Protocol.visibleText("Baik.<|tool_call_begin|><|tool_name|>Read"))
    }
}
