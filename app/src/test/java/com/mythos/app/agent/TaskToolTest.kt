package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private class SubLlm(private val replies: List<String>) : LlmClient {
    var calls = 0
    val seen = mutableListOf<List<Msg>>()
    override suspend fun complete(system: String, messages: List<Msg>): String {
        seen.add(messages.toList())
        return replies[calls++]
    }
}

class TaskToolTest {
    @Test
    fun registeredOnlyWhenSubAgentClientGiven() {
        assertEquals(null, ToolRegistry.default(false).get("Task"))
        assertNotNull(ToolRegistry.default(false, subAgentLlm = SubLlm(emptyList())).get("Task"))
    }

    @Test
    fun subAgentReportReachesMainAgentAndStaysReadOnly() = runTest {
        val dir = Files.createTempDirectory("mythos-task").toFile()
        java.io.File(dir, "a.txt").writeText("halo")
        val task = "<tool_use>{\"name\":\"Task\",\"input\":{\"description\":\"cek a.txt\",\"prompt\":\"Baca a.txt dan laporkan isinya\"}}</tool_use>"
        val llm = SubLlm(
            listOf(
                "Saya serahkan penelitian ke sub-agen.\n$task",       // utama: memanggil Task
                "Ditemukan: a.txt berisi halo.",                         // sub-agen: laporan akhir
                "a.txt berisi halo.",                                    // utama: jawaban akhir
            )
        )
        val agent = Agent(
            llm, ToolRegistry.default(false, subAgentLlm = llm), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, null, AgentConfig()
        )
        val res = agent.execute("apa isi a.txt?") { }
        assertTrue(res.success)
        assertEquals("a.txt berisi halo.", res.summary)
        assertEquals(3, llm.calls)
        // laporan sub-agen masuk ke riwayat agen utama sebagai hasil alat
        assertTrue(llm.seen[2].any { it.text.contains("Ditemukan: a.txt berisi halo.") })
        // sub-agen punya konteks sendiri dan tidak memiliki alat tulis
        assertTrue(llm.seen[1].first().text.contains("Baca a.txt dan laporkan isinya"))
        assertFalse(ToolRegistry(listOf(LS(), Glob(), Grep(), Read(), WebSearch())).names.contains("Write"))
    }
}
