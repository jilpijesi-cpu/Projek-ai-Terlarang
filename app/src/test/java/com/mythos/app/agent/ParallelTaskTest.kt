package com.mythos.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private fun task(desc: String, prompt: String, toolName: String = "Task") =
    "<tool_use>{\"name\":\"$toolName\",\"input\":{\"description\":\"$desc\",\"prompt\":\"$prompt\"}}</tool_use>"

/**
 * LLM palsu yang tidak bergantung urutan panggilan: agen utama dikenali dari pesan pertamanya (tidak memuat teks sub-agen).
 * Setiap sub-agen baru boleh menjawab setelah SEMUA sub-agen sudah mulai, jadi bila Task dijalankan berurutan tes ini gagal (timeout).
 */
private class ParallelLlm(private val mainFirst: String, private val expectedSubs: Int) : LlmClient {
    private val started = CompletableDeferred<Unit>()
    private var subCount = 0
    private var mainCount = 0
    var totalSubs = 0
    val mainSeen = mutableListOf<List<Msg>>()

    override suspend fun complete(system: String, messages: List<Msg>): String {
        val isSub = messages.first().text.contains("read-only research sub-agent")
        if (!isSub) {
            mainSeen.add(messages.toList())
            return if (mainCount++ == 0) mainFirst else "Selesai."
        }
        val me = ++subCount
        totalSubs = me
        if (me >= expectedSubs) started.complete(Unit)
        withTimeout(5_000) { started.await() }
        val text = messages.first().text
        return when {
            text.contains("AREA-A") -> "Laporan A: aman."
            text.contains("AREA-B") -> "Laporan B: ada temuan."
            else -> "Laporan lain."
        }
    }
}

class ParallelTaskTest {
    private fun runAgent(llm: LlmClient, events: MutableList<AgentEvent>) = runTest {
        val dir = Files.createTempDirectory("mythos-par").toFile()
        val agent = Agent(
            llm, ToolRegistry.default(false, subAgentLlm = llm), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, null, AgentConfig()
        )
        agent.execute("audit dua area") { events.add(it) }
    }

    @Test
    fun twoTasksInOneReplyRunConcurrentlyAndReportsKeepCallOrder() {
        val first = "Saya bagi audit ke dua sub-agen.\n" + task("audit A", "AREA-A cek") + "\n" + task("audit B", "AREA-B cek", toolName = "task")
        val llm = ParallelLlm(first, expectedSubs = 2)
        val events = mutableListOf<AgentEvent>()
        runAgent(llm, events)
        assertEquals(2, llm.totalSubs)
        val back = llm.mainSeen[1].last().text
        assertTrue(back.contains("Laporan A: aman."))
        assertTrue(back.contains("Laporan B: ada temuan."))
        // urutan hasil mengikuti urutan panggilan, bukan urutan selesai
        assertTrue(back.indexOf("Laporan A") < back.indexOf("Laporan B"))
        assertEquals(2, events.count { it is AgentEvent.ToolStart && it.tool == "Task" })
        val ends = events.filterIsInstance<AgentEvent.ToolEnd>().filter { it.tool == "Task" }
        assertEquals(setOf("Task audit A", "Task audit B"), ends.map { it.summary }.toSet())
        assertTrue(ends.all { it.ok })
    }

    @Test
    fun extraTasksBeyondLimitAreRejectedNotRun() {
        val calls = (1..7).joinToString("\n") { task("area $it", "AREA-A nomor $it") }
        // hanya 3 sub-agen boleh berjalan bersamaan, jadi pintu dibuka saat 3 pertama sudah mulai
        val llm = ParallelLlm("Mulai.\n$calls", expectedSubs = 3)
        val events = mutableListOf<AgentEvent>()
        runAgent(llm, events)
        assertEquals(6, llm.totalSubs)
        assertTrue(llm.mainSeen[1].last().text.contains("Too many Task calls"))
    }
}
