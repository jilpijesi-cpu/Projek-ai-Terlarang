package com.mythos.app.agent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class LlmTest {
    @Test
    fun malformedUrlBecomesAClearLlmExceptionNotACrash() {
        val e = assertThrows(LlmException::class.java) { openConn("api.example.com/v1") }
        assertFalse(e.retryable)
        assertTrue(e.message!!, e.message!!.contains("Alamat"))
    }

    @Test
    fun errorObjectInsideA200BodyIsMappedToItsRealCause() {
        val rate = errorException(JSONObject("""{"message":"Rate limit exceeded: free-models-per-day","code":429}"""))
        assertEquals(429, rate.code)
        assertTrue(rate.retryable)
        val auth = errorException(JSONObject("""{"message":"Incorrect API key provided"}"""))
        assertEquals(401, auth.code)
        assertFalse(auth.retryable)
        val unknown = errorException(JSONObject("""{"message":"upstream hiccup"}"""))
        assertEquals(-1, unknown.code)
        assertTrue(unknown.retryable)
    }

    @Test
    fun aQuotaErrorInsideA200BodyGetsALongRestNotAShortNetworkRetry() {
        val f = classify(errorException(JSONObject("""{"message":"You exceeded your current quota","code":"insufficient_quota"}""")))
        assertTrue(f.cooldownMs >= 3 * 3_600_000L)
    }

    @Test
    fun cancellingAHangingRequestDisconnectsImmediately() {
        val server = ServerSocket(0)      // menerima koneksi (backlog OS) tetapi tidak pernah menjawab
        try {
            val url = "http://127.0.0.1:${server.localPort}/v1/chat/completions"
            val t0 = System.nanoTime()
            val outcome = runBlocking {
                try {
                    withTimeout(500) { Http.postJson(url, emptyMap(), JSONObject().put("a", 1), 30_000) }
                    "selesai"
                } catch (e: TimeoutCancellationException) {
                    "dibatalkan"
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals("dibatalkan", outcome)
            assertTrue("butuh $ms ms; harus jauh di bawah read timeout 30 detik", ms < 10_000)
        } finally {
            server.close()
        }
    }
}
