package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaming: pembaca SSE, pengurai potongan tiap penyedia, pembaca teks parsial, dan perilaku coba ulang/failover. */
class StreamingTest {
    private val msgs = listOf(Msg(Role.USER, "halo"))

    private fun parse(vararg lines: String): List<Pair<String?, String>> {
        val out = ArrayList<Pair<String?, String>>()
        val p = SseParser { ev, d -> out.add(ev to d) }
        lines.forEach { p.line(it) }
        p.finish()
        return out
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("tidak melempar ${T::class.java.simpleName}")
    }

    // ------------------------------------------------------------ SSE

    @Test
    fun sseJoinsDataLinesIgnoresCommentsAndKeepsEventName() {
        val ev = parse(": keep-alive", "event: content_block_delta", "data: {\"a\":1}", "data: baris2", "", "data:{\"b\":2}", "")
        assertEquals(listOf<Pair<String?, String>>("content_block_delta" to "{\"a\":1}\nbaris2", null to "{\"b\":2}"), ev)
    }

    @Test
    fun sseFinishFlushesEventWithoutTrailingBlankLine() {
        assertEquals(listOf<Pair<String?, String>>(null to "x"), parse("data: x"))
    }

    @Test
    fun sseIgnoresEventsWithoutData() {
        assertEquals(emptyList<Pair<String?, String>>(), parse("event: ping", ""))
    }

    // ------------------------------------------------------------ potongan per penyedia

    @Test
    fun openAiDeltaReadsContentAndSkipsDoneNullAndGarbage() {
        assertEquals("Ha", openAiDelta("""{"choices":[{"delta":{"content":"Ha"}}]}"""))
        assertNull(openAiDelta("[DONE]"))
        assertNull(openAiDelta("""{"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertNull(openAiDelta("""{"choices":[{"delta":{"content":""}}]}"""))
        assertNull(openAiDelta("{bukan json"))
    }

    @Test
    fun openAiDeltaThrowsOnErrorObjectInsideStream() {
        expectThrows<LlmException> { openAiDelta("""{"error":{"message":"limit tercapai","code":429}}""") }
    }

    @Test
    fun anthropicDeltaOnlyTextDeltas() {
        assertEquals(
            "Hai",
            anthropicDelta("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hai"}}""")
        )
        assertNull(
            anthropicDelta("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}""")
        )
        assertNull(anthropicDelta("message_start", """{"type":"message_start","message":{"id":"x"}}"""))
        assertNull(anthropicDelta("ping", """{"type":"ping"}"""))
    }

    @Test
    fun anthropicDeltaThrowsOnErrorEvent() {
        expectThrows<LlmException> {
            anthropicDelta("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
    }

    @Test
    fun geminiDeltaJoinsPartsAndSkipsThoughts() {
        assertEquals("ab", geminiDelta("""{"candidates":[{"content":{"parts":[{"text":"a"},{"text":"b"}]}}]}"""))
        assertNull(geminiDelta("""{"candidates":[{"content":{"parts":[{"text":"pikir","thought":true}]}}]}"""))
        assertNull(geminiDelta("""{"usageMetadata":{}}"""))
    }

    @Test
    fun wholeResponseParsersMirrorComplete() {
        assertEquals("x", openAiWhole("""{"choices":[{"message":{"content":"x"}}]}"""))
        assertEquals("y", anthropicWhole("""{"content":[{"type":"text","text":"y"}]}"""))
        assertEquals("z", geminiWhole("""{"candidates":[{"content":{"parts":[{"text":"pikir","thought":true},{"text":"z"}]}}]}"""))
        assertEquals(
            "ab",
            geminiWhole("""[{"candidates":[{"content":{"parts":[{"text":"a"}]}}]},{"candidates":[{"content":{"parts":[{"text":"b"}]}}]}]""")
        )
    }

    // ------------------------------------------------------------ pembatas laju

    @Test
    fun textStreamThrottlesAndFlushesLastChunk() {
        var now = 1_000L
        val seen = ArrayList<String>()
        val s = TextStream({ seen.add(it) }, minGapMs = 40, clock = { now })
        s.add("a")
        now += 10; s.add("b")
        now += 10; s.add("c")
        assertEquals(listOf("a"), seen)
        now += 40; s.add("d")
        assertEquals(listOf("a", "abcd"), seen)
        now += 5; s.add("e")
        s.flush()
        assertEquals(listOf("a", "abcd", "abcde"), seen)
        s.flush()
        assertEquals(3, seen.size)
        assertEquals("abcde", s.text)
    }

    // ------------------------------------------------------------ teks parsial (Protocol)

    @Test
    fun visibleTextCutsAtToolBlockAndHidesPartialOpener() {
        assertEquals("Saya lihat dulu.", Protocol.visibleText("Saya lihat dulu.\n<tool_use>{\"name\":\"Read\""))
        assertEquals("Saya lihat dulu.", Protocol.visibleText("Saya lihat dulu.\n<tool_u"))
        assertEquals("Saya lihat dulu.", Protocol.visibleText("Saya lihat dulu.\n<"))
        assertEquals("a < b", Protocol.visibleText("a < b"))
        assertEquals("", Protocol.visibleText("<tool_use>{}"))
    }

    @Test
    fun visibleTextKeepsInlineMentionOfToolUseTag() {
        val t = "Format-nya `<tool_use>` lalu JSON."
        assertEquals(t, Protocol.visibleText(t))
    }

    @Test
    fun visibleTextDropsFenceLinesAroundToolBlock() {
        assertEquals("Membuat file.", Protocol.visibleText("Membuat file.\n```xml\n<tool_use>{\"name\":\"Write\"}"))
    }

    @Test
    fun pendingToolReportsNameAndTargetOnlyWhenComplete() {
        assertNull(Protocol.pendingTool("Halo"))
        assertNull(Protocol.pendingTool("<tool_use>{\"na"))
        assertEquals("Write" to null, Protocol.pendingTool("<tool_use>{\"name\": \"Write\", \"input\": {\"file_path\": \"src/Ma"))
        assertEquals(
            "Write" to "src/Main.kt",
            Protocol.pendingTool("<tool_use>{\"name\": \"Write\", \"input\": {\"file_path\": \"src/Main.kt\", \"content\": \"x")
        )
    }

    // ------------------------------------------------------------ klien

    @Test
    fun clientWithoutStreamingFallsBackToComplete() = runTest {
        val c = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String = "halo"
        }
        val seen = ArrayList<String>()
        assertEquals("halo", c.stream("s", msgs) { seen.add(it) })
        assertEquals(listOf("halo"), seen)
    }

    @Test
    fun retryingClientStreamClearsTextBeforeRetrying() = runTest {
        var calls = 0
        val inner = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String = error("tidak dipakai")
            override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
                calls++
                if (calls == 1) {
                    onText("pa")
                    throw LlmException("Jaringan: putus", true)
                }
                onText("ok")
                return "ok"
            }
        }
        val seen = ArrayList<String>()
        assertEquals("ok", RetryingClient(inner).stream("s", msgs) { seen.add(it) })
        assertEquals(listOf("pa", "", "ok"), seen)
    }

    @Test
    fun retryingClientStreamDoesNotRetryPermanentErrors() = runTest {
        var calls = 0
        val inner = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String = error("tidak dipakai")
            override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
                calls++
                throw LlmException("HTTP 401: kunci salah", false, 401)
            }
        }
        val seen = ArrayList<String>()
        expectThrows<LlmException> { kotlinx.coroutines.runBlocking { RetryingClient(inner).stream("s", msgs) { seen.add(it) } } }
        assertEquals(1, calls)
        assertTrue(seen.isEmpty())
    }

    private fun spec(n: String) = KeySpec(n, ProviderKind.OPENAI_COMPATIBLE, "http://x", "key-$n", "m-$n")

    @Test
    fun failoverStreamClearsPartialTextWhenAKeyFails() = runTest {
        val pool = KeyPool(listOf(spec("a"), spec("b")), clock = { 1_000_000L })
        val c = FailoverClient(pool, factory = { cfg ->
            object : LlmClient {
                override suspend fun complete(system: String, messages: List<Msg>): String = error("tidak dipakai")
                override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
                    if (cfg.apiKey == "key-a") {
                        onText("setengah")
                        throw LlmException("HTTP 429: rate limit per minute", true, 429)
                    }
                    onText("utuh")
                    return "utuh"
                }
            }
        }, onNotice = {}, sleep = {})
        val seen = ArrayList<String>()
        assertEquals("utuh", c.stream("s", msgs) { seen.add(it) })
        assertEquals(listOf("setengah", "", "utuh"), seen)
    }
}
