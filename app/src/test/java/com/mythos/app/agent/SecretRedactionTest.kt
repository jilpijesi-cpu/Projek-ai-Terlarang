package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push ke GitHub harus menghormati .gitignore dan tidak pernah membawa kunci API (HTTP 422 "Secret detected"). */
class SecretRedactionTest {
    @Test
    fun keysAreRedacted() {
        val key1 = "sk-or-v1-" + "a1b2".repeat(10)
        val key2 = "gsk_" + "Z9y8".repeat(8)
        val (out, n) = redactSecrets("val a = \"$key1\"\nval b = \"$key2\"\nval c = \"biasa\"")
        assertEquals(2, n)
        assertFalse(out.contains(key1))
        assertFalse(out.contains(key2))
        assertTrue(out.contains("REDACTED"))
        assertTrue(out.contains("biasa"))
    }

    @Test
    fun plainTextUntouched() {
        assertEquals(0, redactSecrets("tidak ada rahasia di sini, sk-or tidak lengkap").second)
    }

    @Test
    fun gitignoreRules() {
        val g = SimpleGitignore(listOf("# komentar", "*.iml", ".gradle/", "build/", "app/src/main/java/com/mythos/app/agent/EmbeddedKeys.kt", "/local.properties"))
        assertTrue(g.ignores("app/src/main/java/com/mythos/app/agent/EmbeddedKeys.kt"))
        assertTrue(g.ignores("x/app.iml"))
        assertTrue(g.ignores(".gradle/caches/a"))
        assertTrue(g.ignores("app/build/out.apk"))
        assertTrue(g.ignores("local.properties"))
        assertFalse(g.ignores("app/src/main/java/com/mythos/app/agent/Agent.kt"))
    }
}
