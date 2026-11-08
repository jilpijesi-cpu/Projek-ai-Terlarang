package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format GLM (<tool_call>Nama<arg_key>..) dan markup karangan model (<|tool_call|><|begin_of_content|>..) dari rekaman layar. */
class ArgKvToolCallTest {
    private fun parse(raw: String) = Protocol.parse(raw) { "id1" }

    @Test
    fun glmBlockBecomesCall() {
        val r = parse("Saya cari dulu.<tool_call>WebSearch<arg_key>query</arg_key>\n<arg_value>GitHub Actions build</arg_value>\n<arg_key>max_results</arg_key>\n<arg_value>8</arg_value>\n</tool_call>") as Parsed.Reply
        assertEquals("Saya cari dulu.", r.text)
        assertEquals(1, r.calls.size)
        assertEquals("WebSearch", r.calls[0].name)
        assertEquals("GitHub Actions build", r.calls[0].input.getString("query"))
        assertEquals(8, r.calls[0].input.getInt("max_results"))
    }

    @Test
    fun missingCloseTagAndSeveralCalls() {
        val r = parse("<tool_call>Read<arg_key>file_path</arg_key><arg_value>.github/workflows/build.yml</arg_value>\n<tool_call>Glob<arg_key>pattern</arg_key><arg_value>*</arg_value>") as Parsed.Reply
        assertEquals(listOf("Read", "Glob"), r.calls.map { it.name })
        assertEquals(".github/workflows/build.yml", r.calls[0].input.getString("file_path"))
    }

    @Test
    fun truncatedPairIsInvalid() {
        assertTrue(parse("<tool_call>Read<arg_key>file_path</arg_key><arg_value>a.kt") is Parsed.Invalid)
    }

    @Test
    fun fakeSpecialTokensAreInvalidNotAnAnswer() {
        val raw = "<|tool_call|><|begin_of_content|><|tool_read|><|file_path|>ci.yml</|file_path|></|tool_read|><|end_of_content|>"
        assertTrue(parse(raw) is Parsed.Invalid)
    }

    @Test
    fun singleMentionOfTokenIsStillAnAnswer() {
        val r = parse("Token pembuka percakapan di model itu ditulis <|im_start|> lalu peran.") as Parsed.Reply
        assertTrue(r.text.contains("<|im_start|>"))
    }

    @Test
    fun streamingHidesMarkup() {
        assertEquals("Saya cek.", Protocol.visibleText("Saya cek.<tool_call>Read<arg_key>file_path</arg_key><arg_value>a"))
        assertEquals("Halo", Protocol.visibleText("Halo <|tool_call|><|begin_of_content|>abc"))
        assertFalse(Protocol.visibleText("x <arg_key>q").contains("arg_key"))
    }

    @Test
    fun pendingToolReadsGlmName() {
        val p = Protocol.pendingTool("<tool_call>Read<arg_key>file_path</arg_key><arg_value>a/b.kt</arg_value>")
        assertEquals("Read", p?.first)
        assertEquals("a/b.kt", p?.second)
    }

    // ---- pembungkus <dots_function_call> dari rekaman layar

    @Test
    fun emptyDotsWrappersAreDroppedNotShown() {
        val r = parse("Saya akan memeriksa status proyek.\n<dots_function_call>\n\n</dots_function_call>\n<dots_function_call>\n</dots_function_call>") as Parsed.Reply
        assertEquals("Saya akan memeriksa status proyek.", r.text)
        assertEquals(0, r.calls.size)
    }

    @Test
    fun dotsWrapperWithJsonBecomesCall() {
        val r = parse("Saya baca.<dots_function_call>{\"name\":\"Read\",\"arguments\":{\"file_path\":\"a.kt\"}}</dots_function_call>") as Parsed.Reply
        assertEquals("Saya baca.", r.text)
        assertEquals("Read", r.calls.single().name)
        assertEquals("a.kt", r.calls.single().input.getString("file_path"))
    }

    @Test
    fun dotsWrapperWithGlmInsideBecomesCall() {
        val r = parse("<dots_function_call><tool_call>Glob<arg_key>pattern</arg_key><arg_value>*.kts</arg_value></tool_call></dots_function_call>") as Parsed.Reply
        assertEquals("Glob", r.calls.single().name)
    }

    @Test
    fun strayWrapperCloserIsStripped() {
        val r = parse("Halo.\n</dots_function_call>") as Parsed.Reply
        assertEquals("Halo.", r.text)
    }

    @Test
    fun streamingHidesWrapperAndShowsToolLabel() {
        assertEquals("Saya cek.", Protocol.visibleText("Saya cek.\n<dots_function_call>\n{\"name\":\"Read\""))
        assertEquals("Saya cek.", Protocol.visibleText("Saya cek.<dots_func"))
        val p = Protocol.pendingTool("<dots_function_call>{\"name\":\"Read\",\"arguments\":{\"file_path\":\"a/b.kt\"")
        assertEquals("Read", p?.first)
    }

    // ---- fallback umum: tag asing yang tampak seperti panggilan alat

    @Test
    fun invokeXmlBecomesCall() {
        val r = parse("Saya baca.<function_calls><invoke name=\"Read\"><parameter name=\"file_path\">a/b.kt</parameter></invoke></function_calls>") as Parsed.Reply
        assertEquals("Saya baca.", r.text)
        assertEquals("Read", r.calls.single().name)
        assertEquals("a/b.kt", r.calls.single().input.getString("file_path"))
    }

    @Test
    fun unknownCallTagIsInvalidAndNamedInReason() {
        val r = parse("Saya cek dulu.\n<run_tool_now>ls -la</run_tool_now>")
        assertTrue(r is Parsed.Invalid)
        assertTrue((r as Parsed.Invalid).reason.contains("run_tool_now"))
    }

    @Test
    fun unknownCallTagIsHiddenWhileStreaming() {
        assertEquals("Saya cek dulu.", Protocol.visibleText("Saya cek dulu.\n<run_tool_now>ls"))
        assertEquals("Saya cek dulu.", Protocol.visibleText("Saya cek dulu.<xyz_invoke name=\"a\">"))
    }

    @Test
    fun tagsInsideCodeAreJustText() {
        val r = parse("Contoh: `<function_call>` dipakai model tertentu.\n```xml\n<tool_name>x</tool_name>\n```") as Parsed.Reply
        assertTrue(r.text.contains("<function_call>"))
        assertTrue(r.text.contains("<tool_name>"))
    }

    @Test
    fun ordinaryProseAndHtmlAreUntouched() {
        val r = parse("Gunakan <b>tebal</b> dan <div>kotak</div> untuk tampilan.") as Parsed.Reply
        assertEquals("Gunakan <b>tebal</b> dan <div>kotak</div> untuk tampilan.", r.text)
    }

    @Test
    fun leftoverToolCallTagThatCouldNotBeParsedIsInvalid() {
        assertTrue(parse("<tool_call>Read file a.kt</tool_call>") is Parsed.Invalid)
    }
}
