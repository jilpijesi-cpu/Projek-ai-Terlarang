package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeModels(private val behavior: (ProviderConfig) -> String) : LlmClient {
    var cfg: ProviderConfig? = null
    override suspend fun complete(system: String, messages: List<Msg>): String = behavior(cfg!!)
}

class ModelCatalogTest {
    private val base = ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, "https://x/v1", "k", "")

    private fun scanner(listJson: String, behavior: (ProviderConfig) -> String) = ModelScanner(
        fetch = { _, _ -> HttpGet(200, listJson) },
        factory = { cfg -> FakeModels(behavior).also { it.cfg = cfg } },
        parallel = 3,
    )

    @Test
    fun parsesAndFiltersModelLists() {
        val oa = parseModelIds(ProviderKind.OPENAI_COMPATIBLE, """{"data":[{"id":"gpt-4.1"},{"id":"text-embedding-3-small"}]}""")
        assertEquals(listOf("gpt-4.1", "text-embedding-3-small"), oa)
        assertEquals(listOf("gpt-4.1"), oa.filter(::isChatModel))
        val gem = parseModelIds(
            ProviderKind.GEMINI,
            """{"models":[{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}]}"""
        )
        assertEquals(listOf("gemini-2.5-pro"), gem)
    }

    @Test
    fun scoreRanksStrongAboveWeak() {
        assertTrue(modelScore("claude-opus-4") > modelScore("claude-haiku-4"))
        assertTrue(modelScore("gpt-4.1") > modelScore("gpt-4.1-mini"))
        assertTrue(modelScore("llama-3.3-70b-versatile") > modelScore("llama-3.1-8b-instant"))
    }

    @Test
    fun scanClassifiesReadyBusyFailedAndSortsReadyFirst() = runTest {
        val sc = scanner("""{"data":[{"id":"gpt-4.1"},{"id":"gpt-4o"},{"id":"gpt-4.1-mini"}]}""") { cfg ->
            when (cfg.model) {
                "gpt-4.1" -> throw LlmException("HTTP 404: model not found", false, 404)
                "gpt-4o" -> throw LlmException("HTTP 429: rate limit", true, 429)
                else -> "ok"
            }
        }
        val r = sc.scan(base)
        assertEquals("gpt-4.1-mini", r.first().model)
        assertEquals(ModelState.READY, r[0].state)
        assertEquals(ModelState.BUSY, r.first { it.model == "gpt-4o" }.state)
        assertEquals(ModelState.FAILED, r.first { it.model == "gpt-4.1" }.state)
    }

    @Test
    fun retriesWithoutTemperatureWhenRejectedWith400() = runTest {
        val sc = scanner("""{"data":[{"id":"o-reason"}]}""") { cfg ->
            if (cfg.temperature != null || cfg.tokenParam == "max_tokens") throw LlmException("HTTP 400: unsupported parameter", false, 400) else "ok"
        }
        val st = sc.probe(base.copy(model = "o-reason"))
        assertEquals(ModelState.READY, st.state)
        assertNull(st.config.temperature)
        assertEquals("max_completion_tokens", st.config.tokenParam)
    }

    @Test
    fun resolveSpecReplacesDeadConfiguredModel() = runTest {
        val sc = scanner("""{"data":[{"id":"old-model"},{"id":"new-model-70b"}]}""") { cfg ->
            if (cfg.model == "old-model") throw LlmException("HTTP 404", false, 404) else "ok"
        }
        val spec = KeySpec("Uji", ProviderKind.OPENAI_COMPATIBLE, "https://x/v1", "k", "old-model")
        val r = sc.resolveSpec(spec)
        assertNull(r.problem)
        assertEquals("new-model-70b", r.specs.first().model)
    }

    @Test
    fun resolveSpecReportsProblemWhenNothingWorks() = runTest {
        val sc = scanner("""{"data":[{"id":"m1"}]}""") { throw LlmException("HTTP 401", false, 401) }
        val r = sc.resolveSpec(KeySpec("Uji", ProviderKind.OPENAI_COMPATIBLE, "https://x/v1", "k", "m0"))
        assertTrue(r.specs.isEmpty())
        assertTrue(r.problem!!.startsWith("Uji"))
    }

    @Test
    fun geminiModelsAreNotPenalisedAsMini() {
        assertTrue(modelScore("gemini-2.5-pro") > modelScore("gpt-4.1"))
        assertTrue(modelScore("gemini-2.5-flash") > modelScore("gemini-2.5-flash-lite"))
        assertTrue(modelScore("gpt-4.1") > modelScore("gpt-4.1-mini"))
        assertTrue(modelScore("o4-mini") < modelScore("gpt-4.1"))
    }

    @Test
    fun maxOutputTokensAreRaisedOnlyForModelsKnownToAllowIt() {
        val gem = "https://generativelanguage.googleapis.com"
        assertEquals(16_384, defaultMaxOutputTokens(ProviderKind.GEMINI, gem, "gemini-2.5-flash"))
        assertEquals(8_192, defaultMaxOutputTokens(ProviderKind.GEMINI, gem, "gemini-1.5-flash"))
        assertEquals(4_096, defaultMaxOutputTokens(ProviderKind.ANTHROPIC, "https://api.anthropic.com", "claude-3-haiku-20240307"))
        assertEquals(8_192, defaultMaxOutputTokens(ProviderKind.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4"))
        assertEquals(16_384, defaultMaxOutputTokens(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4.1"))
        assertEquals(4_096, defaultMaxOutputTokens(ProviderKind.OPENAI_COMPATIBLE, "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"))
        assertEquals(4_096, defaultMaxOutputTokens(ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"))
        assertEquals(16_384, Presets.gemini("k", "gemini-flash-latest").maxOutputTokens)
    }

    private val five = """{"data":[{"id":"gpt-5"},{"id":"gpt-4.1"},{"id":"gpt-4o"},{"id":"gpt-4.1-mini"},{"id":"gpt-4o-mini"}]}"""

    @Test
    fun findFirstProbesOneByOneAndStopsAtFirstReady() = runTest {
        val probed = ArrayList<String>()
        val sc = scanner(five) { cfg ->
            probed += cfg.model
            when (cfg.model) {
                "gpt-5" -> throw LlmException("HTTP 429: quota exceeded", true, 429)   // kuota habis -> lanjut
                "gpt-4.1" -> throw LlmException("HTTP 404: model not found", false, 404) // tidak ada -> lanjut
                else -> "ok"
            }
        }
        val r = sc.findFirst(base)
        // urut dan berhenti di model pertama yang merespons: dua model terakhir tidak pernah diuji
        assertEquals(listOf("gpt-5", "gpt-4.1", "gpt-4o"), probed)
        assertEquals("gpt-4o", r.first().model)
        assertEquals(ModelState.READY, r.first().state)
        assertEquals(listOf("gpt-4.1-mini", "gpt-4o-mini"), r.filter { it.state == ModelState.UNTESTED }.map { it.model })
        assertTrue(r.none { it.model == "gpt-4.1" })                                    // gagal permanen dibuang
        assertEquals(ModelState.BUSY, r.first { it.model == "gpt-5" }.state)            // sibuk disimpan di belakang
        assertEquals("gpt-5", r.last().model)
    }

    @Test
    fun findFirstUsesPinnedModelFirstAndSkipsRestWhenItWorks() = runTest {
        val probed = ArrayList<String>()
        val sc = scanner(five) { cfg -> probed += cfg.model; "ok" }
        val r = sc.findFirst(base, pinned = "gpt-4o-mini")
        assertEquals(listOf("gpt-4o-mini"), probed)
        assertEquals("gpt-4o-mini", r.first().model)
        assertEquals(4, r.count { it.state == ModelState.UNTESTED })
    }

    @Test
    fun findFirstStillReturnsSparesWhenNothingRespondsWithinMaxProbe() = runTest {
        val probed = ArrayList<String>()
        val sc = scanner(five) { cfg -> probed += cfg.model; throw LlmException("HTTP 429: rate limit", true, 429) }
        val r = sc.findFirst(base, maxProbe = 2)
        assertEquals(2, probed.size)
        assertTrue(r.none { it.state == ModelState.READY })
        assertEquals(3, r.count { it.state == ModelState.UNTESTED })   // tetap bisa dicoba saat tugas jalan
        assertEquals(2, r.count { it.state == ModelState.BUSY })
        assertEquals(5, r.toSpecs("Uji").size)
    }

    @Test
    fun firstReadyIsCachedSoNextTaskStartsInstantly() = runTest {
        var calls = 0
        var now = 0L
        val sc = scanner(five) { calls++; "ok" }
        val rs = ModelResolver(sc, clock = { now }, ttlMs = 60_000L)
        rs.firstReady(base, "")
        rs.firstReady(base, "")
        assertEquals(1, calls)
        now = 61_000L
        rs.firstReady(base, "")
        assertEquals(2, calls)
    }

    @Test
    fun localHttpHostsGetLongerProbeTimeoutThanCloud() {
        assertEquals(10_000, probeTimeoutFor(base))
        assertEquals(60_000, probeTimeoutFor(Presets.ollama("", "http://127.0.0.1:11434")))
    }
}
