package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class PromptAware(private val reply: (String) -> String) : LlmClient {
    override suspend fun complete(system: String, messages: List<Msg>): String = reply(system)
}

/** Uji kedua saat memilih model otomatis: model yang hanya menjawab "ok" tetapi tidak bisa menulis panggilan alat diturunkan. */
class ToolProbeTest {
    private val base = ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, "https://x/v1", "k", "")
    private val goodCall = "<tool_use>{\"name\": \"Read\", \"input\": {\"file_path\": \"notes.txt\"}}</tool_use>"
    private val prose = "Baik, saya akan membaca file itu."

    /** [answer] menerima (model, true bila ini uji alat) dan mengembalikan balasan; uji \"ok\" selalu dijawab \"ok\". */
    private fun scanner(models: List<String>, checkTools: Boolean, answer: (String, Boolean) -> String) = ModelScanner(
        fetch = { _, _ -> HttpGet(200, "{\"data\":[" + models.joinToString(",") { "{\"id\":\"$it\"}" } + "]}") },
        factory = { cfg ->
            PromptAware { sys -> if (sys.contains("coding agent")) answer(cfg.model, true) else "ok" }
        },
        parallel = 1,
        checkTools = checkTools,
    )

    private val models = listOf("gpt-4.1", "gpt-4o", "gpt-4.1-mini")

    @Test
    fun modelThatCannotWriteAToolCallIsDemotedAndTheNextOneChosen() = runTest {
        val sc = scanner(models, checkTools = true) { m, _ -> if (m == "gpt-4.1") prose else goodCall }
        val r = sc.findFirst(base)
        assertEquals("gpt-4o", r.first().model)
        assertEquals(ModelState.READY, r.first().state)
        val bad = r.first { it.model == "gpt-4.1" }
        assertEquals(ModelState.BUSY, bad.state)
        assertEquals(TOOL_FORMAT_FAIL, bad.detail)
        assertTrue(r.indexOf(bad) > 0)
    }

    @Test
    fun withoutTheCheckTheTopScoredModelIsStillChosen() = runTest {
        val sc = scanner(models, checkTools = false) { _, _ -> prose }
        assertEquals("gpt-4.1", sc.findFirst(base).first().model)
    }

    @Test
    fun whenNoModelWritesToolCallsNoneIsReadyButAllStayUsableAsLastResort() = runTest {
        val sc = scanner(models, checkTools = true) { _, _ -> prose }
        val r = sc.findFirst(base)
        assertTrue(r.none { it.state == ModelState.READY })
        assertEquals(models.toSet(), r.filter { it.state == ModelState.BUSY }.map { it.model }.toSet())
        assertEquals(models.size, r.toSpecs("Uji").size)
    }

    @Test
    fun inconclusiveToolProbeDoesNotPenaliseTheModel() = runTest {
        val sc = scanner(models, checkTools = true) { _, _ -> throw LlmException("HTTP 500: gangguan", true, 500) }
        val r = sc.findFirst(base)
        assertEquals("gpt-4.1", r.first().model)
        assertEquals(ModelState.READY, r.first().state)
    }

    @Test
    fun replyFormatsThatTheAppCanParseAsAReadCallPass() {
        assertTrue(toolProbeOk(goodCall))
        assertTrue(toolProbeOk("Saya baca.\n$goodCall"))
        // Gaya Llama dengan JSON polos: aplikasi sudah bisa membersihkan dan menjalankannya.
        assertTrue(
            toolProbeOk(
                "<|start_header_id|>tool_use<|end_header_id|>\n<|start_code|>\n" +
                    "{\"name\": \"Read\", \"input\": {\"file_path\": \"notes.txt\"}}\n</|start_code|>"
            )
        )
    }

    @Test
    fun proseWrongToolOrNoCallFails() {
        assertFalse(toolProbeOk(prose))
        assertFalse(toolProbeOk(""))
        assertFalse(toolProbeOk("<tool_use>{\"name\": \"LS\", \"input\": {\"path\": \".\"}}</tool_use>"))
    }
}
