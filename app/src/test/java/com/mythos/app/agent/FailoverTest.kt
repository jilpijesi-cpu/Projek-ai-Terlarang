package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FailoverTest {
    private var now = 1_000_000L

    private fun spec(n: String) = KeySpec(n, ProviderKind.OPENAI_COMPATIBLE, "http://x", "key-$n", "m-$n")
    private fun pool(vararg n: String) = KeyPool(n.map { spec(it) }, clock = { now })
    private fun client(block: () -> String): LlmClient = object : LlmClient {
        override suspend fun complete(system: String, messages: List<Msg>): String = block()
    }
    private val msgs = listOf(Msg(Role.USER, "hai"))

    @Test
    fun rotatesOnRateLimitAndStaysOnHealthyKey() = runTest {
        val p = pool("a", "b")
        val used = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val c = FailoverClient(p, factory = { cfg ->
            client {
                used.add(cfg.apiKey)
                if (cfg.apiKey == "key-a") throw LlmException("HTTP 429: rate limit per minute", true, 429)
                "ok-" + cfg.apiKey
            }
        }, onNotice = { notices.add(it) }, sleep = {})

        assertEquals("ok-key-b", c.complete("s", msgs))
        assertEquals(listOf("key-a", "key-b"), used)
        assertTrue(notices.any { it.contains("gagal") })
        assertTrue(p.snapshot()[0].state.contains("istirahat"))

        c.complete("s", msgs)                       // lengket di b, tidak mencoba a lagi
        assertEquals("key-b", used.last())
        assertEquals(3, used.size)
    }

    @Test
    fun invalidKeyIsDisabledUntilReset() = runTest {
        val p = pool("a", "b")
        val used = mutableListOf<String>()
        val c = FailoverClient(p, factory = { cfg ->
            client {
                used.add(cfg.apiKey)
                if (cfg.apiKey == "key-a") throw LlmException("HTTP 401: invalid", false, 401)
                "ok"
            }
        }, sleep = {})
        c.complete("s", msgs)
        c.complete("s", msgs)
        assertEquals(listOf("key-a", "key-b", "key-b"), used)
        assertTrue(p.snapshot()[0].state.startsWith("nonaktif"))
        p.reset()
        assertTrue(p.snapshot()[0].healthy)
    }

    @Test
    fun cooledDownHigherPriorityKeyIsUsedAgain() = runTest {
        val p = pool("a", "b")
        var aFails = true
        val used = mutableListOf<String>()
        val c = FailoverClient(p, factory = { cfg ->
            client {
                used.add(cfg.apiKey)
                if (cfg.apiKey == "key-a" && aFails) throw LlmException("HTTP 429: slow down", true, 429)
                "ok"
            }
        }, sleep = {})
        c.complete("s", msgs)           // a gagal -> b
        aFails = false
        now += 61_000                   // cooldown a selesai
        c.complete("s", msgs)
        assertEquals("key-a", used.last())
    }

    @Test
    fun allKeysFailingThrowsWithoutLeakingKeys() = runTest {
        val p = pool("a", "b")
        val c = FailoverClient(p, factory = { cfg ->
            client { throw LlmException("HTTP 401: bad key ${cfg.apiKey}", false, 401) }
        }, sleep = {})
        try {
            c.complete("s", msgs)
            fail("seharusnya gagal")
        } catch (e: LlmException) {
            assertFalse(e.message!!.contains("key-a"))
            assertFalse(e.message!!.contains("key-b"))
        }
    }

    @Test
    fun rotateEverySpreadsLoad() = runTest {
        val p = KeyPool(listOf(spec("a"), spec("b")), clock = { now }, rotateEvery = 1)
        val used = mutableListOf<String>()
        val c = FailoverClient(p, factory = { cfg -> client { used.add(cfg.apiKey); "ok" } }, sleep = {})
        repeat(4) { c.complete("s", msgs) }
        assertEquals(listOf("key-a", "key-b", "key-a", "key-b"), used)
    }

    @Test
    fun classifyMapsErrorsToActions() {
        assertTrue(classify(LlmException("HTTP 401", false, 401)).disable)
        assertTrue(classify(LlmException("HTTP 404: model not found", false, 404)).disable)
        assertTrue(classify(LlmException("HTTP 403: API key revoked", false, 403)).disable)
        assertEquals(false, classify(LlmException("HTTP 403: not available in your region", false, 403)).disable)
        assertEquals(false, classify(LlmException("HTTP 403", false, 403)).disable)
        assertEquals(60_000L, classify(LlmException("HTTP 429: too many requests", true, 429)).cooldownMs)
        assertTrue(classify(LlmException("HTTP 429: Quota exceeded PerDay", true, 429)).cooldownMs >= 3 * 3600_000L)
        assertEquals(25_500L, classify(LlmException("HTTP 429: Please retry in 23.5s", true, 429)).cooldownMs)
        assertEquals(20_000L, classify(LlmException("HTTP 503", true, 503)).cooldownMs)
        assertTrue(classify(LlmException("HTTP 429: insufficient_quota", true, 429)).cooldownMs >= 3 * 3600_000L)
    }

    @Test
    fun badRequestRejectedByEveryKeyFailsFastAndDoesNotLockThePool() = runTest {
        val p = pool("a", "b")
        val c = FailoverClient(p, factory = { cfg ->
            client { throw LlmException("HTTP 400: context too long ${cfg.apiKey}", false, 400) }
        }, sleep = {})
        try {
            c.complete("s", msgs)
            fail("seharusnya gagal")
        } catch (e: LlmException) {
            assertTrue(e.message!!, e.message!!.contains("ditolak"))
            assertFalse(e.message!!, e.message!!.contains("key-a"))   // kunci tidak bocor
        }
        // permintaannya yang salah, bukan kuncinya: tidak ada yang dihukum
        assertTrue(p.snapshot().all { it.healthy })
        val ok = FailoverClient(p, factory = { cfg -> client { "ok-" + cfg.apiKey } }, sleep = {})
        assertEquals("ok-key-a", ok.complete("s", msgs))
    }

    @Test
    fun badRequestWhenOtherKeyIsOnQuotaCooldownFailsFastWithoutSleeping() = runTest {
        val p = pool("a", "b")
        p.onFailure(p.entries[0], classify(LlmException("HTTP 429: Quota exceeded PerDay", true, 429)))
        var slept = 0
        val c = FailoverClient(p, factory = { _ ->
            client { throw LlmException("HTTP 400: context too long", false, 400) }
        }, sleep = { slept++ })
        try {
            c.complete("s", msgs)
            fail("seharusnya gagal")
        } catch (e: LlmException) {
            assertTrue(e.message!!, e.message!!.contains("ditolak"))
        }
        assertEquals(0, slept)
        assertTrue(p.snapshot()[1].healthy)
    }

    @Test
    fun badRequestOnOneKeyOnlyRestsItBriefly() = runTest {
        val p = pool("a", "b")
        val c = FailoverClient(p, factory = { cfg ->
            client {
                if (cfg.apiKey == "key-a") throw LlmException("HTTP 400: parameter tidak didukung", false, 400)
                "ok-" + cfg.apiKey
            }
        }, sleep = {})
        assertEquals("ok-key-b", c.complete("s", msgs))
        now += 46_000          // istirahat 45 detik sudah lewat
        assertTrue(p.snapshot()[0].healthy)
    }

    @Test
    fun classifyTreatsRequestShapedErrorsAsShortRests() {
        for (code in listOf(400, 413, 422)) {
            val f = classify(LlmException("HTTP $code: x", false, code))
            assertFalse(f.disable)
            assertTrue(f.cooldownMs <= 60_000L)
        }
    }

    // ---------- penyesuaian otomatis model reasoning (pengganti tombol "Mode model reasoning") ----------

    @Test
    fun adaptsOnceWhenProviderRejectsTemperatureAndRemembersIt() = runTest {
        val p = pool("a")
        val seen = mutableListOf<Pair<Double?, String>>()
        val resets = mutableListOf<Int>()
        val c = FailoverClient(p, factory = { cfg ->
            client {
                seen.add(cfg.temperature to cfg.tokenParam)
                if (cfg.temperature != null || cfg.tokenParam == "max_tokens")
                    throw LlmException("HTTP 400: Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens'", false, 400)
                "ok"
            }
        }, sleep = {})
        assertEquals("ok", c.complete("s", msgs))
        assertEquals(listOf<Pair<Double?, String>>(0.2 to "max_tokens", null to "max_completion_tokens"), seen)
        assertTrue(p.snapshot()[0].healthy)                       // kunci tidak dihukum
        assertEquals("max_completion_tokens", p.entries[0].spec.tokenParam)

        c.complete("s", msgs)                                     // panggilan berikutnya langsung memakai parameter yang benar
        assertEquals(3, seen.size)
        assertEquals(null to "max_completion_tokens", seen.last())
    }

    @Test
    fun doesNotAdaptForUnrelated400OrOtherProviderKinds() {
        val s = spec("a")
        assertEquals(null, adaptedForReasoning(s, LlmException("HTTP 400: context length exceeded", false, 400)))
        assertEquals(null, adaptedForReasoning(s, LlmException("HTTP 429: temperature", true, 429)))
        val claude = s.copy(kind = ProviderKind.ANTHROPIC)
        assertEquals(null, adaptedForReasoning(claude, LlmException("HTTP 400: temperature unsupported", false, 400)))
        // sudah disesuaikan: tidak diulang tanpa akhir
        val adapted = s.copy(temperature = null, tokenParam = "max_completion_tokens")
        assertEquals(null, adaptedForReasoning(adapted, LlmException("HTTP 400: unsupported parameter", false, 400)))
    }

    @Test
    fun zeroQuota429IsLongRestNotAMinuteRetry() {
        val google = LlmException("HTTP 429: You exceeded your current quota. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_input_token_count, limit: 0, model: gemini-x. input tokens per minute limit exceed", true, 429)
        assertTrue(isZeroQuota(google))
        assertTrue(classify(google).cooldownMs >= 3 * 3600_000L)
        // Batas per menit biasa tetap istirahat singkat; limit bukan nol juga.
        assertEquals(60_000L, classify(LlmException("HTTP 429: too many requests", true, 429)).cooldownMs)
        assertFalse(isZeroQuota(LlmException("HTTP 429: limit: 15 requests per minute", true, 429)))
        assertFalse(isZeroQuota(LlmException("HTTP 500: limit: 0", true, 500)))
    }

    @Test
    fun retryingClientDoesNotRetryZeroQuota() = runTest {
        var calls = 0
        val inner = client { calls++; throw LlmException("HTTP 429: limit: 0, model: x", true, 429) }
        try {
            RetryingClient(inner).complete("s", msgs)
            fail("harus gagal")
        } catch (e: LlmException) {
            assertEquals(1, calls)
        }
    }
}
