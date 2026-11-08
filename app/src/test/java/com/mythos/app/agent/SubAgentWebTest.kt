package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private class SeqLlmWeb(private val replies: List<String>) : LlmClient {
    var calls = 0
    val systems = mutableListOf<String>()
    val seen = mutableListOf<List<Msg>>()
    override suspend fun complete(system: String, messages: List<Msg>): String {
        systems.add(system)
        seen.add(messages.toList())
        return replies[calls++]
    }
}

class SubAgentWebTest {
    private fun call(name: String, input: String) = "<tool_use>{\"name\":\"$name\",\"input\":$input}</tool_use>"

    @Test
    fun webFetchNeverAsksApprovalInAnyMode() {
        for (mode in PermissionMode.values()) {
            val tool = ToolRegistry.default(false).get("WebFetch")!!
            assertEquals(Risk.SAFE, tool.risk)
            assertFalse(tool.risk == Risk.DANGEROUS || (tool.risk == Risk.WRITE && mode == PermissionMode.DEFAULT))
        }
    }

    @Test
    fun subAgentCanRunWebFetchEvenWhenApproverRejectsEverything() = runTest {
        val dir = Files.createTempDirectory("mythos-subweb-exec").toFile()
        var approverCalled = false
        val fakeFetch = object : Tool {
            override val name = "WebFetch"
            override val doc = "WebFetch {\"url\":\"string\"}"
            override val risk = Risk.SAFE
            override val effect = Effect.READ
            override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult =
                ToolResult(true, "Isi halaman dari ${args.optString("url")}")
        }
        val llm = SeqLlmWeb(
            listOf(
                call("WebFetch", "{\"url\":\"https://example.org/artikel\"}"),
                "Sudah dibuka dan dibaca.",
            )
        )
        val reg = ToolRegistry(listOf(fakeFetch))
        val agent = Agent(
            llm, reg, ProjectSandbox(dir),
            Approver { _, _ -> approverCalled = true; false },
            { "" }, null, AgentConfig(permissionMode = PermissionMode.DEFAULT)
        )
        val res = agent.execute("buka artikel") { }
        assertTrue(res.success)
        assertFalse(approverCalled)
        assertTrue(llm.seen[1].last().text.contains("Isi halaman dari https://example.org/artikel"))
    }

    @Test
    fun subAgentHasWebToolsInItsPromptWhenWebIsOn() = runTest {
        val dir = Files.createTempDirectory("mythos-subweb").toFile()
        val llm = SeqLlmWeb(
            listOf(
                "Saya serahkan pencarian ke sub-agen.\n" + call("Task", "{\"description\":\"cari sumber\",\"prompt\":\"Cari referensi X dan beri URL\"}"),
                "Laporan: tidak ada.",
                "Selesai.",
            )
        )
        val agent = Agent(llm, ToolRegistry.default(false, subAgentLlm = llm), ProjectSandbox(dir), Approver { _, _ -> true }, { "" }, null, AgentConfig())
        assertTrue(agent.execute("cari referensi X") { }.success)
        assertTrue(llm.systems[1].contains("WebFetch {"))
        assertTrue(llm.systems[1].contains("WebSearch {"))
        assertTrue(llm.seen[1].first().text.contains("OPENED the page"))       // laporan wajib memuat URL + status dibuka/cuplikan
    }

    @Test
    fun webToggleOffMeansNoWebInSubAgentAndMainPromptHasSourceRules() = runTest {
        val dir = Files.createTempDirectory("mythos-subweb3").toFile()
        val llm = SeqLlmWeb(
            listOf(
                "Saya serahkan ke sub-agen.\n" + call("Task", "{\"description\":\"cek\",\"prompt\":\"Cek sesuatu\"}"),
                "Laporan.",
                "Selesai.",
            )
        )
        val agent = Agent(llm, ToolRegistry.default(false, withWeb = false, subAgentLlm = llm), ProjectSandbox(dir), Approver { _, _ -> true }, { "" }, null, AgentConfig())
        assertTrue(agent.execute("tes") { }.success)
        assertTrue(llm.systems[0].contains("# Sources and references"))
        assertFalse(llm.systems[1].contains("WebFetch {"))
        assertFalse(llm.systems[1].contains("WebSearch {"))
    }
}
