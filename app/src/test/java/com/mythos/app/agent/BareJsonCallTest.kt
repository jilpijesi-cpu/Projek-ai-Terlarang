package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model lemah menulis pemanggilan alat sebagai JSON polos ({"status":..,"name":..,"input":..}) atau <invokename=...>: tidak boleh bocor ke obrolan. */
class BareJsonCallTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" } as Parsed.Reply

    @Test
    fun bareJsonLineBecomesACall() {
        val r = parse("""Saya periksa dulu.
{"status": "Memeriksa folder lampiran", "name": "LS", "input": {"path": "uploads", "depth": 1}}""")
        assertEquals("Saya periksa dulu.", r.text)
        assertEquals(1, r.calls.size)
        assertEquals("LS", r.calls[0].name)
        assertEquals("uploads", r.calls[0].input.getString("path"))
        assertEquals("Memeriksa folder lampiran", r.calls[0].status)
    }

    @Test
    fun severalBareLinesBecomeSeveralCalls() {
        val r = parse(
            "{\"status\": \"a\", \"name\": \"LS\", \"input\": {\"path\": \".\"}}\n" +
                "{\"status\": \"b\", \"name\": \"Read\", \"input\": {\"file_path\": \"x.kt\"}}"
        )
        assertEquals("", r.text)
        assertEquals(listOf("LS", "Read"), r.calls.map { it.name })
    }

    @Test
    fun jsonWithoutKnownToolIsPlainText() {
        val r = parse("""{"name": "Budi", "umur": 3}""")
        assertTrue(r.calls.isEmpty())
        assertTrue(r.text.contains("Budi"))
    }

    @Test
    fun fencedBareJsonIsNotExecuted() {
        val r = parse("Contoh:\n```json\n{\"status\": \"x\", \"name\": \"LS\", \"input\": {}}\n```")
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun streamingHidesBareJson() {
        val v = Protocol.visibleText("Saya periksa.\n{\"status\": \"Memeriksa folder")
        assertEquals("Saya periksa.", v)
        assertEquals("LS", Protocol.pendingTool("{\"status\": \"a\", \"name\": \"LS\", \"input\": {\"pa")?.first)
    }

    @Test
    fun invokeWithoutSpaceIsParsed() {
        val r = parse("<invokename=\"Glob\">\n<parameter name=\"pattern\">\n/\n</parameter>")
        assertEquals(1, r.calls.size)
        assertEquals("Glob", r.calls[0].name)
        assertEquals("/", r.calls[0].input.getString("pattern").trim())
        assertTrue(!r.text.contains("invoke"))
    }

    @Test
    fun fencedPrettyJsonCallFromRecordingBecomesCall() {
        val r = parse(
            """Saya akan membuat berkas KASUS.md agar fakta tercatat.

```json
{
  "status": "Membuat berkas kasus",
  "name": "Write",
  "input": {
    "file_path": "KASUS.md",
    "content": "# Kasus\n- Pelapor: Ibu Agenia\n## Langkah\n1. Aduan"
  }
}
```

Langkah paling berguna sekarang adalah mengadu."""
        )
        assertEquals("Saya akan membuat berkas KASUS.md agar fakta tercatat.", r.text)
        assertEquals(1, r.calls.size)
        assertEquals("Write", r.calls[0].name)
        assertEquals("KASUS.md", r.calls[0].input.getString("file_path"))
        assertTrue(r.calls[0].input.getString("content").contains("Pelapor"))
        assertEquals("Membuat berkas kasus", r.calls[0].status)
    }

    @Test
    fun fencedPrettyJsonAfterExampleHintIsNotExecuted() {
        val r = parse("Contoh format:\n```json\n{\n  \"status\": \"x\",\n  \"name\": \"LS\",\n  \"input\": {\"path\": \".\"}\n}\n```")
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun fencedJsonWithoutStatusOrKnownToolIsNotExecuted() {
        assertTrue(parse("Hasil:\n```json\n{\"name\": \"LS\", \"input\": {}}\n```").calls.isEmpty())
        assertTrue(parse("Hasil:\n```json\n{\"status\": \"x\", \"name\": \"Budi\", \"input\": {}}\n```").calls.isEmpty())
    }

    @Test
    fun prettyPrintedUnfencedBareJsonBecomesCall() {
        val r = parse("Saya periksa.\n{\n  \"status\": \"Memeriksa folder\",\n  \"name\": \"LS\",\n  \"input\": {\"path\": \"uploads\"}\n}")
        assertEquals("Saya periksa.", r.text)
        assertEquals(listOf("LS"), r.calls.map { it.name })
    }

    @Test
    fun streamingHidesFencedCallButNotOrdinaryCode() {
        assertEquals("Saya akan menulis.", Protocol.visibleText("Saya akan menulis.\n```json\n{"))
        assertEquals("Saya akan menulis.", Protocol.visibleText("Saya akan menulis.\n```json\n{\n  \"sta"))
        assertEquals("Saya akan menulis.", Protocol.visibleText("Saya akan menulis.\n```json\n{\n  \"status\": \"Membuat berk"))
        assertTrue(Protocol.visibleText("Ini:\n```kotlin\nval x = 1").contains("val x = 1"))
    }
}
