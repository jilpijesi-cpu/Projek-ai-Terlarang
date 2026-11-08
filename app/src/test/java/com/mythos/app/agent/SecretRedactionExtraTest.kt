package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pola kunci tambahan (Hugging Face, Tavily, Brave, DeepSeek, token GitHub lain) juga tidak boleh sampai ke GitHub. */
class SecretRedactionExtraTest {
    @Test
    fun extraProvidersAreRedacted() {
        val samples = listOf(
            "hf_" + "A".repeat(34),
            "tvly-" + "b".repeat(24),
            "BSA" + "c".repeat(28),
            "sk-" + "0123456789abcdef".repeat(2),
            "ghs_" + "D".repeat(36),
        )
        for (k in samples) {
            val (out, n) = redactSecrets("val x = \"$k\"")
            assertEquals(k, 1, n)
            assertFalse(k, out.contains(k))
        }
    }

    @Test
    fun ordinaryTextIsUntouched() {
        assertEquals(0, redactSecrets("hf_ sk-pendek tvly BSA biasa").second)
    }
}
