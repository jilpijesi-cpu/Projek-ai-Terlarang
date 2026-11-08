package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestyAndLimitTest {
    @Test
    fun systemPromptHasHonestySection() {
        val p = Protocol.systemPrompt(ToolRegistry.default(false), false)
        assertTrue(p.contains("# Honesty"))
        assertTrue(p.contains("Never claim to be Claude, GPT, Grok"))
    }

    @Test
    fun engineNoteStatesTheRealModelAndHost() {
        val n = engineNote(KeySpec("Groq 2", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "k", "openai/gpt-oss-20b"))
        assertTrue(n.contains("openai/gpt-oss-20b"))
        assertTrue(n.contains("api.groq.com"))
    }

    @Test
    fun engineNoteAdmitsRouterHidesRealModel() {
        val n = engineNote(KeySpec("OpenRouter", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "k", "openrouter/free", locked = true))
        assertTrue(n.contains("NOT known to you"))
    }

    @Test
    fun failoverAppendsRuntimeFactsToSystemPrompt() = runTest {
        var seen = ""
        val spec = KeySpec("A", ProviderKind.OPENAI_COMPATIBLE, "https://api.example.com/v1", "k", "model-x")
        val c = FailoverClient(KeyPool(listOf(spec)), factory = {
            object : LlmClient {
                override suspend fun complete(system: String, messages: List<Msg>): String { seen = system; return "ok" }
            }
        }, sleep = {})
        c.complete("BASE", listOf(Msg(Role.USER, "hai")))
        assertTrue(seen.startsWith("BASE"))
        assertTrue(seen.contains("model-x"))
    }

    @Test
    fun shrunkBudgetUsesLimitOverRequested() {
        val msg = "Request too large ... tokens per minute (TPM): Limit 8000, Requested 8751, please reduce"
        val b = ContextManager.shrunkBudget(40_000, msg)
        assertTrue(b in 20_000..30_000)               // 40.000 * 8000/8751 * 0.7 ~ 25.600
        assertEquals(20_000, ContextManager.shrunkBudget(40_000, "tidak ada angka"))
        assertEquals(3_000, ContextManager.shrunkBudget(1_000, null))
    }

    @Test
    fun squeezeCutsBiggestMessageAndKeepsSmallOnes() {
        val h = listOf(Msg(Role.USER, "x".repeat(10_000)), Msg(Role.ASSISTANT, "jawaban pendek"))
        val out = ContextManager.squeeze(h, 3_000)
        assertTrue(out.sumOf { it.text.length } <= 3_200)
        assertEquals("jawaban pendek", out[1].text)
        assertTrue(out[0].text.contains("dipotong"))
    }

    @Test
    fun squeezeDoesNotReCutTheSameMessageRepeatedlyAtFloor() {
        val h = (1..10).map { Msg(Role.USER, "m$it:" + "x".repeat(800)) }
        val out = ContextManager.squeeze(h, 3_000)
        // setiap pesan yang dipotong hanya boleh memiliki satu penanda "... [dipotong ..."
        for (m in out) {
            val count = Regex("\\[dipotong ").findAll(m.text).count()
            assertTrue("Pesan dipotong berulang kali: ${m.text}", count <= 1)
        }
        assertFalse(out.last().text.contains("[dipotong "))
    }

    @Test
    fun squeezeLeavesFittingHistoryUntouched() {
        val h = listOf(Msg(Role.USER, "halo"))
        assertTrue(ContextManager.squeeze(h, 3_000) === h)
    }

    @Test
    fun describeTooLargeHidesRawJsonAndKeepsNumbers() {
        val raw = "HTTP 413: {\"error\":{\"message\":\"Request too large ... org_secret123 ... tokens per minute (TPM): Limit 8000, Requested 8751\"}}"
        val d = ContextManager.describeTooLarge(raw)
        assertTrue(d.contains("8000") && d.contains("8751") && d.contains("per menit"))
        assertTrue(!d.contains("org_secret123"))
        assertEquals("Permintaan terlalu besar untuk penyedia.", ContextManager.describeTooLarge("kosong"))
        assertTrue(!ContextManager.describeTooLarge("Max size. Limit 8000 Requested 9000").contains("per menit"))
    }
}
