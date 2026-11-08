package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format asli sebagian model: <|tool_call_start|>[Read(file_path='a')]<|tool_call_end|> harus dijalankan, bukan tampil sebagai teks. */
class NativeToolCallTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" } as Parsed.Reply

    @Test
    fun pythonStyleListBecomesCalls() {
        val r = parse("Saya cek dulu.<|tool_call_start|>[Read(file_path='/a/b.xml'), Grep(pattern=\"x\", limit=3)]<|tool_call_end|>")
        assertEquals("Saya cek dulu.", r.text)
        assertEquals(2, r.calls.size)
        assertEquals("Read", r.calls[0].name)
        assertEquals("/a/b.xml", r.calls[0].input.getString("file_path"))
        assertEquals(3, r.calls[1].input.getInt("limit"))
    }

    @Test
    fun missingCloseTagStillParses() {
        val r = parse("<|tool_call_start|>[LS(path='uploads')]")
        assertEquals(1, r.calls.size)
        assertEquals("LS", r.calls[0].name)
    }

    @Test
    fun garbageIsInvalidNotShownAsAnswer() {
        assertTrue(Protocol.parse("<|tool_call_start|>[Read(file_path=]<|tool_call_end|>") is Parsed.Invalid)
    }

    @Test
    fun streamingHidesNativeBlock() {
        val partial = "Saya baca.<|tool_call_start|>[Read(file_path='x.kt'"
        assertEquals("Saya baca.", Protocol.visibleText(partial))
        assertEquals("Read", Protocol.pendingTool(partial)?.first)
    }

    @Test
    fun strayCloseTagIsNotShown() {
        val r = parse("</tool_use>\nSaya baca file.<tool_use>{\"name\":\"Read\",\"input\":{\"file_path\":\"a\"}}</tool_use></tool_use>")
        assertEquals("Saya baca file.", r.text)
        assertEquals(1, r.calls.size)
        assertEquals("Saya baca.", Protocol.visibleText("</tool_use>Saya baca."))
    }
}
