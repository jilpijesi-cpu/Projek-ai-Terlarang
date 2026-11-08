package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** LLM palsu yang streaming: teks dikirim bertahap seperti dari jaringan, lalu dikembalikan utuh. */
private class StreamingLlm(private val replies: List<String>) : LlmClient {
    var calls = 0
    override suspend fun complete(system: String, messages: List<Msg>): String = error("complete tidak dipakai")
    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        val r = replies[calls++]
        var i = 0
        while (i < r.length) {
            i = minOf(r.length, i + 7)
            onText(r.substring(0, i))
        }
        return r
    }
}

private fun toolUse(name: String, input: String) = "<tool_use>{\"name\":\"$name\",\"input\":$input}</tool_use>"

/** Agen meneruskan teks yang sedang tiba sebagai AgentEvent.Streaming, tanpa pernah menampilkan blok alat. */
class AgentStreamingTest {
    private fun agent(llm: LlmClient, dir: File) = Agent(
        llm, ToolRegistry.default(false, withPlan = false),
        ProjectSandbox(dir), Approver { _, _ -> true }, { "" }, null, AgentConfig()
    )

    private fun newDir(): File = Files.createTempDirectory("mythos-stream").toFile()

    @Test
    fun streamingPrecedesThoughtAndNeverShowsTheToolBlock() = runTest {
        val llm = StreamingLlm(
            listOf(
                "Membuat file.\n" + toolUse("Write", """{"file_path":"hello.txt","content":"halo"}"""),
                "Sudah dibuat.",
            )
        )
        val events = mutableListOf<AgentEvent>()
        agent(llm, newDir()).execute("buat hello.txt") { events.add(it) }

        val streams = events.filterIsInstance<AgentEvent.Streaming>()
        assertTrue(streams.isNotEmpty())
        assertTrue(streams.none { it.text.contains("tool_use") || it.text.contains("file_path") })
        // Potongan awal (mis. "Membuat") boleh tampil lebih dulu; yang diperiksa kartu pertama yang sudah mengenal alat dan targetnya.
        val first = streams.first { it.tool != null && it.target != null }
        assertEquals("Membuat file.", first.text)
        assertEquals("Write", first.tool)
        assertEquals("hello.txt", first.target)

        val iStream = events.indexOfFirst { it is AgentEvent.Streaming }
        val iThought = events.indexOfFirst { it is AgentEvent.Thought }
        val iTool = events.indexOfFirst { it is AgentEvent.ToolStart }
        assertTrue(iStream in 0 until iThought)
        assertTrue(iThought < iTool)
    }

    @Test
    fun finalAnswerArrivesAsStreamingTextThenFinal() = runTest {
        val llm = StreamingLlm(listOf("Halo! Ada yang bisa saya bantu hari ini?"))
        val events = mutableListOf<AgentEvent>()
        agent(llm, newDir()).execute("hai") { events.add(it) }

        val last = events.filterIsInstance<AgentEvent.Streaming>().last()
        assertEquals("Halo! Ada yang bisa saya bantu hari ini?", last.text)
        assertNull(last.tool)
        assertTrue(events.indexOfLast { it is AgentEvent.Streaming } < events.indexOfFirst { it is AgentEvent.Final })
    }

    @Test
    fun brokenToolBlockClearsThePreviewBeforeTheRetryNotice() = runTest {
        val llm = StreamingLlm(listOf("<tool_use>{\"name\": \"Read\", \"input\": {", "ok"))
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, newDir()).execute("x") { events.add(it) }
        assertTrue(res.success)

        val iPrepared = events.indexOfFirst { it is AgentEvent.Streaming && it.tool == "Read" }
        val iReset = events.withIndex().firstOrNull { (i, e) -> i > iPrepared && e is AgentEvent.Streaming && e.text.isEmpty() && e.tool == null }?.index ?: -1
        val iNotice = events.indexOfFirst { it is AgentEvent.Notice }
        assertTrue(iPrepared >= 0)
        assertTrue(iPrepared < iReset)
        assertTrue(iReset < iNotice)
        assertEquals("ok", events.filterIsInstance<AgentEvent.Streaming>().last().text)
    }

    @Test
    fun plainTextToolCallIsExecutedAndTheRawTextNeverReachesTheScreen() = runTest {
        val llm = StreamingLlm(
            listOf(
                "AskUserQuestion: {\"question\":\"Mau audit apa?\",\"options\":[\"Izin\",\"Manifest\"]}",
                "Pemeriksaan izin selesai: tidak ada masalah.",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val asked = mutableListOf<String>()
        val a = Agent(
            llm, ToolRegistry.default(false, withPlan = false), ProjectSandbox(newDir()),
            Approver { _, _ -> true }, { q -> asked.add(q); "Izin" }, null, AgentConfig()
        )
        val res = a.execute("audit apk ini") { events.add(it) }

        assertTrue(res.success)
        assertEquals("Pemeriksaan izin selesai: tidak ada masalah.", res.summary)
        assertEquals(1, asked.size)
        assertTrue(asked[0].contains("Mau audit apa?"))
        assertTrue(events.any { it is AgentEvent.ToolStart && it.tool == "AskUserQuestion" })
        assertTrue(events.filterIsInstance<AgentEvent.Final>().none { it.summary.contains("AskUserQuestion") })
        assertTrue(events.filterIsInstance<AgentEvent.Streaming>().none { it.text.contains("AskUserQuestion") })
        // sisa pratinjau dibersihkan sebelum alat berjalan
        val iReset = events.indexOfFirst { it is AgentEvent.Streaming && it.text.isEmpty() && it.tool == null }
        val iStart = events.indexOfFirst { it is AgentEvent.ToolStart }
        assertTrue(iReset in 0 until iStart)
    }
}
