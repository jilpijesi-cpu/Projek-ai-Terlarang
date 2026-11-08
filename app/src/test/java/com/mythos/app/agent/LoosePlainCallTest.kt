package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model lemah menulis pemanggilan alat sebagai teks polos ("AskUserQuestion: {...}"): dikenali, tetapi kutipan format tidak menjalankan apa pun. */
class LoosePlainCallTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" } as Parsed.Reply

    @Test
    fun askUserQuestionLineBecomesACall() {
        val r = parse("""AskUserQuestion: {"question":"Mau audit apa?","options":["Izin","Manifest"]}""")
        assertEquals("", r.text)
        assertEquals(1, r.calls.size)
        assertEquals("AskUserQuestion", r.calls[0].name)
        assertEquals("Mau audit apa?", r.calls[0].input.getString("question"))
        assertEquals(2, r.calls[0].input.getJSONArray("options").length())
    }

    @Test
    fun emptyOptionsAreFine() {
        val r = parse("""AskUserQuestion: {"question":"Kirim manifest?","options":[]}""")
        assertEquals(1, r.calls.size)
        assertEquals(0, r.calls[0].input.getJSONArray("options").length())
    }

    @Test
    fun narrationBeforeAndAfterIsKept() {
        val r = parse("Saya perlu tahu satu hal.\nAskUserQuestion: {\"question\":\"Apa?\"}\nSaya tunggu jawaban Anda.")
        assertEquals("Saya perlu tahu satu hal.\nSaya tunggu jawaban Anda.", r.text)
        assertEquals(1, r.calls.size)
    }

    @Test
    fun otherSpellingsAreAccepted() {
        assertEquals("Read", parse("""Read {"file_path":"a.kt"}""").calls[0].name)
        assertEquals("Read", parse("""Read({"file_path":"a.kt"})""").calls[0].name)
        assertEquals("Grep", parse("""- Grep: {"pattern":"x"}""").calls[0].name)
        assertEquals("Edit", parse("""Edit = {"file_path":"a.kt","old_string":"a","new_string":"b"}""").calls[0].name)
    }

    @Test
    fun fullFormInputIsUnwrapped() {
        val r = parse("""AskUserQuestion: {"name":"AskUserQuestion","input":{"question":"Apa?"}}""")
        assertEquals("Apa?", r.calls[0].input.getString("question"))
    }

    @Test
    fun severalLooseCallsKeepTheirOrder() {
        val r = parse("Membaca.\nRead {\"file_path\":\"a.kt\"}\nRead {\"file_path\":\"b.kt\"}")
        assertEquals(listOf("a.kt", "b.kt"), r.calls.map { it.input.getString("file_path") })
        assertEquals("Membaca.", r.text)
    }

    @Test
    fun multiLineJsonIsMatchedAcrossLines() {
        val r = parse("AskUserQuestion: {\n  \"question\": \"Apa?\",\n  \"options\": [\"A\", \"B\"]\n}")
        assertEquals(1, r.calls.size)
        assertEquals("", r.text)
    }

    @Test
    fun proseAfterTheObjectMeansItIsJustText() {
        val raw = """Read {"file_path":"a.kt"} — membaca satu file"""
        val r = parse(raw)
        assertTrue(r.calls.isEmpty())
        assertEquals(raw, r.text)
    }

    @Test
    fun fencedExamplesAndInlineMentionsRunNothing() {
        assertTrue(parse("Contoh:\n```\nRead {\"file_path\":\"a.kt\"}\n```").calls.isEmpty())
        assertTrue(parse("Format-nya `Read {\"file_path\":\"a.kt\"}` ya.").calls.isEmpty())
        assertTrue(parse("Tulis seperti `AskUserQuestion: {\"question\":\"x\"}`").calls.isEmpty())
    }

    @Test
    fun brokenJsonUnknownNamesAndPlainWordsAreIgnored() {
        assertTrue(parse("AskUserQuestion: {\"question\": \"x\"").calls.isEmpty())
        assertTrue(parse("""Foo: {"a":1}""").calls.isEmpty())
        assertTrue(parse("Read the file and report back.").calls.isEmpty())
        assertTrue(parse("Readme {\"a\":1}").calls.isEmpty())
    }

    @Test
    fun realToolUseBlocksTakePrecedence() {
        val r = parse("Cek.\n<tool_use>{\"name\":\"Read\",\"input\":{\"file_path\":\"x.kt\"}}</tool_use>\nRead {\"file_path\":\"y.kt\"}")
        assertEquals(1, r.calls.size)
        assertEquals("x.kt", r.calls[0].input.getString("file_path"))
    }

    @Test
    fun streamingPreviewHidesTheLooseCallAndNamesTheTool() {
        assertEquals("Saya cek.", Protocol.visibleText("Saya cek.\nAskUserQuestion: {\"question\":\"Ap"))
        assertEquals("", Protocol.visibleText("AskUserQuestion: {"))
        assertEquals("AskUserQuestion" to null, Protocol.pendingTool("Saya cek.\nAskUserQuestion: {\"question\":\"Ap"))
        assertNull(Protocol.pendingTool("Saya cek dulu."))
        // sebutan nama alat dalam kalimat biasa tidak disembunyikan
        assertEquals("Saya akan Read file itu.", Protocol.visibleText("Saya akan Read file itu."))
    }
}

/** Format XML model (<function=Nama><parameter=k>v</parameter></function>) dibaca sebagai pemanggilan alat dan tidak bocor ke obrolan. */
class FunctionTagCallTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" } as Parsed.Reply

    @Test
    fun functionBlockBecomesACall() {
        val r = parse("Saya cari TODO.\n<function=Grep>\n<parameter=pattern>\nTODO|FIXME\n</parameter>\n<parameter=glob>\n*.kt\n</parameter>\n</function>")
        assertEquals("Saya cari TODO.", r.text)
        assertEquals("Grep", r.calls[0].name)
        assertEquals("TODO|FIXME", r.calls[0].input.getString("pattern"))
        assertEquals("*.kt", r.calls[0].input.getString("glob"))
    }

    @Test
    fun streamingHidesFunctionBlockAndReportsTool() {
        val partial = "Saya cari TODO.\n<function=Grep>\n<parameter=pattern>\nTODO\n</parameter>"
        assertEquals("Saya cari TODO.", Protocol.visibleText(partial))
        assertEquals("Grep" to "TODO", Protocol.pendingTool(partial))
        assertEquals("Halo", Protocol.visibleText("Halo\n<func"))
    }

    @Test
    fun unclosedFunctionBlockIsInvalidTruncated() {
        val r = Protocol.parse("<function=Read>\n<parameter=file_path>a.kt")
        assertTrue(r is Parsed.Invalid && r.truncated)
    }
}

/** Frasa "status" dari model: dibaca dari JSON alat (sebelum "input"), dipakai sebagai teks baris aktivitas. */
class StatusLineTest {
    @Test
    fun statusIsReadFromToolUse() {
        val r = Protocol.parse("""Saya mulai. <tool_use>{"status": "Memeriksa alur streaming.", "name": "Read", "input": {"file_path": "A.kt"}}</tool_use>""") { "id1" } as Parsed.Reply
        assertEquals("Memeriksa alur streaming.", r.calls[0].status)
        assertEquals("A.kt", r.calls[0].input.getString("file_path"))
    }

    @Test
    fun missingStatusIsEmpty() {
        val r = Protocol.parse("""<tool_use>{"name": "Read", "input": {"file_path": "A.kt"}}</tool_use>""") { "id1" } as Parsed.Reply
        assertEquals("", r.calls[0].status)
    }

    @Test
    fun pendingStatusWaitsForClosingQuoteAndIgnoresInput() {
        assertNull(Protocol.pendingStatus("""<tool_use>{"status": "Memeriksa al"""))
        assertEquals("Memeriksa alur", Protocol.pendingStatus("""<tool_use>{"status": "Memeriksa alur", "name": "Read", "input": {"file_path": "a"}}"""))
        assertNull(Protocol.pendingStatus("""<tool_use>{"name": "Write", "input": {"content": "\"status\": \"palsu\""}}"""))
    }
}
