package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysTest {
    private val k1 = "gsk_" + "a".repeat(30)
    private val k2 = "gsk_" + "b".repeat(30)
    private val k3 = "AIza" + "c".repeat(30)

    @Test
    fun parseSplitsAndClassifies() {
        val r = ExtraKeys.parse("$k1\n\n  $k2, $k1 ; \"$k3\" pendek x!", listOf(k2))
        assertEquals(listOf(k1, k3), r.added)
        assertEquals(2, r.duplicates)   // k2 sudah ada, k1 ditempel dua kali
        assertEquals(2, r.rejected)     // "pendek" dan "x!"
    }

    @Test
    fun expandWithoutExtraKeepsSpecsAsIs() {
        val specs = listOf(KeySpec("Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", k1, "m1"))
        assertSame(specs, ExtraKeys.expand(specs, emptyList()))
    }

    @Test
    fun expandIsModelMajorKeyMinor() {
        val a = KeySpec("Groq", ProviderKind.OPENAI_COMPATIBLE, "https://x", k1, "m1")
        val b = KeySpec("Groq", ProviderKind.OPENAI_COMPATIBLE, "https://x", k1, "m2")
        val out = ExtraKeys.expand(listOf(a, b), listOf(k2, k1))   // k1 duplikat kunci utama: diabaikan
        assertEquals(listOf("m1" to k1, "m1" to k2, "m2" to k1, "m2" to k2), out.map { it.model to it.apiKey })
        assertEquals("Groq #2", out[1].label)
    }

    @Test
    fun maskHidesTheMiddle() {
        val m = ExtraKeys.mask(k1)
        assertTrue(m.startsWith("gsk_") && m.endsWith("aaaa") && !m.contains("a".repeat(10)))
        assertFalse(ExtraKeys.mask("pendek").contains("pendek"))
    }
}
