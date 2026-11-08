package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private class Script(private val replies: List<String>) : LlmClient {
    private var i = 0
    override suspend fun complete(system: String, messages: List<Msg>): String = replies[i++]
}

class AckFirstTest {
    private val write = "<tool_use>{\"name\":\"Write\",\"input\":{\"file_path\":\"a.txt\",\"content\":\"x\"}}</tool_use>"

    private fun agent(llm: LlmClient, cfg: AgentConfig) =
        Agent(llm, ToolRegistry.default(false), ProjectSandbox(Files.createTempDirectory("mythos-ack").toFile()), Approver { _, _ -> true }, { "" }, null, cfg)

    @Test
    fun modelOpeningSentenceComesBeforeTheFirstTool() = runTest {
        val events = mutableListOf<AgentEvent>()
        agent(Script(listOf("Siap, saya akan membuatkan kalkulator.\n$write", "Selesai.")), AgentConfig()).execute("buat kalkulator") { events.add(it) }
        val thoughts = events.filterIsInstance<AgentEvent.Thought>()
        assertEquals(1, thoughts.size)
        assertEquals("Siap, saya akan membuatkan kalkulator.", thoughts[0].text)
        assertTrue(events.indexOfFirst { it is AgentEvent.Thought } < events.indexOfFirst { it is AgentEvent.ToolStart })
    }

    @Test
    fun missingOpeningSentenceGetsAFallbackOnlyOnce() = runTest {
        val events = mutableListOf<AgentEvent>()
        agent(Script(listOf(write, write.replace("a.txt", "b.txt"), "Selesai.")), AgentConfig()).execute("buat file") { events.add(it) }
        val thoughts = events.filterIsInstance<AgentEvent.Thought>()
        assertEquals(listOf("Baik BOS, saya mulai mengerjakannya."), thoughts.map { it.text })
        assertTrue(events.indexOfFirst { it is AgentEvent.Thought } < events.indexOfFirst { it is AgentEvent.ToolStart })
    }

    @Test
    fun canBeDisabled() = runTest {
        val events = mutableListOf<AgentEvent>()
        agent(Script(listOf(write, "Selesai.")), AgentConfig(ackFirst = false)).execute("buat file") { events.add(it) }
        assertEquals(0, events.count { it is AgentEvent.Thought })
    }
}
