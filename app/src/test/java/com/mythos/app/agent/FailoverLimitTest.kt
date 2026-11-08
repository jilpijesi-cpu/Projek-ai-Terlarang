package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FailoverLimitTest {
    private var now = 1_000_000L

    private fun spec(n: String) = KeySpec(n, ProviderKind.OPENAI_COMPATIBLE, "http://x", "key-$n", "m-$n")
    private fun pool(vararg n: String) = KeyPool(n.map { spec(it) }, clock = { now })
    private fun client(block: () -> String): LlmClient = object : LlmClient {
        override suspend fun complete(system: String, messages: List<Msg>): String = block()
    }

    private val msgs = listOf(Msg(Role.USER, "halo"))
    private val bigSystem = "x".repeat(40_000)          // ~11.400 token menurut perkiraan
    private val tooLarge = "HTTP 413: tokens per minute (TPM): Limit 8000, Requested 11600"

    @Test
    fun learnsLimitFromRejectionAndSkipsThatModelForBigRequests() = runTest {
        val p = pool("a", "b")
        val used = mutableListOf<String>()
        val c = FailoverClient(p, factory = { cfg ->
            client {
                used.add(cfg.apiKey)
                if (cfg.apiKey == "key-a" && used.size >= 1 && lastBig) throw LlmException(tooLarge, false, 413)
                "ok-" + cfg.apiKey
            }
        }, sleep = {})

        lastBig = true
        assertEquals("ok-key-b", c.complete(bigSystem, msgs))
        assertEquals(listOf("key-a", "key-b"), used)
        assertEquals(8000, p.entries[0].maxRequestTokens)

        used.clear()
        assertEquals("ok-key-b", c.complete(bigSystem, msgs))   // a dilewati tanpa panggilan jaringan
        assertEquals(listOf("key-b"), used)

        used.clear()
        lastBig = false
        assertEquals("ok-key-a", c.complete("s", msgs))          // permintaan kecil kembali ke a (tidak dihukum)
        assertEquals(listOf("key-a"), used)
    }

    @Test
    fun failsFastWhenEveryModelIsKnownTooSmall() = runTest {
        val p = pool("a")
        var calls = 0
        val c = FailoverClient(p, factory = {
            client {
                calls++
                throw LlmException(tooLarge, false, 413)
            }
        }, sleep = {})
        try {
            c.complete(bigSystem, msgs)
            fail("harus gagal")
        } catch (e: LlmException) {
            assertEquals(413, e.code)
        }
        assertEquals(1, calls)
        try {
            c.complete(bigSystem, msgs)
            fail("harus gagal")
        } catch (e: LlmException) {
            assertEquals(413, e.code)
            assertEquals(8000, ContextManager.parseLimit(e.message))
        }
        assertEquals(1, calls)                                   // tidak memanggil jaringan lagi
        assertTrue(p.snapshot()[0].healthy)
    }

    @Test
    fun adoptStateKeepsLearnedLimit() {
        val old = pool("a")
        old.entries[0].maxRequestTokens = 6000
        val fresh = pool("a")
        fresh.adoptState(old)
        assertEquals(6000, fresh.entries[0].maxRequestTokens)
    }

    @Test
    fun noKnownLimitMeansNothingIsSkipped() {
        val p = pool("a", "b")
        assertNull(p.biggestLimitIfAllTooSmall(50_000))
        assertEquals("a", p.pick(50_000)!!.spec.label)
    }

    @Test
    fun estimateGrowsWithTextAndImages() {
        val small = estimateTokens("s", msgs)
        assertTrue(estimateTokens(bigSystem, msgs) > small + 10_000)
        assertFalse(estimateTokens("s", msgs) > 1_000)
    }

    private var lastBig = false
}
