package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OnUseTest {
    private fun spec(n: String) = KeySpec(n, ProviderKind.OPENAI_COMPATIBLE, "http://x", "key-$n", "m-$n")
    private fun client(block: () -> String): LlmClient = object : LlmClient {
        override suspend fun complete(system: String, messages: List<Msg>): String = block()
    }

    @Test
    fun reportsEveryKeyTriedSoTheChipFollowsFailover() = runTest {
        val pool = KeyPool(listOf(spec("a"), spec("b")), clock = { 1_000_000L })
        val seen = mutableListOf<String>()
        val c = FailoverClient(
            pool,
            factory = { cfg ->
                client {
                    if (cfg.apiKey == "key-a") throw LlmException("HTTP 429: rate limit per minute", true, 429)
                    "ok"
                }
            },
            onUse = { seen.add(it.spec.label) },
        )
        assertEquals("ok", c.complete("s", listOf(Msg(Role.USER, "hai"))))
        assertEquals(listOf("a", "b"), seen)
        assertEquals(1, pool.healthyCount())
    }
}
