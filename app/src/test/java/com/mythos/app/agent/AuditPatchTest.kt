package com.mythos.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Tes untuk perbaikan hasil audit: timeout regex, pola rahasia, digest persetujuan workflow, galat cleartext. */
class AuditPatchTest {
    @Test
    fun deadlineCharsAbortsSlowRegex() {
        val input = "a".repeat(40) + "c"
        try {
            Regex("(.*a){14}b").containsMatchIn(DeadlineChars(input, System.nanoTime() + 300_000_000L))
            fail("seharusnya RegexTimeout")
        } catch (e: RegexTimeout) {
            // ok
        }
    }

    @Test
    fun deadlineCharsMatchesNormally() {
        assertTrue(Regex("fun\\s+main").containsMatchIn(DeadlineChars("fun main()", System.nanoTime() + 1_000_000_000L)))
    }

    @Test
    fun privateKeyBlocksAreRedacted() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"
        val (out, n) = redactSecrets("x $pem y")
        assertTrue(n >= 1)
        assertFalse(out.contains("abc"))
    }

    @Test
    fun digestCoversBuildScriptsAndLocalActions() {
        val d = Files.createTempDirectory("audit").toFile()
        File(d, ".github/workflows").mkdirs()
        File(d, ".github/workflows/build.yml").writeText("on: push")
        File(d, "build.gradle.kts").writeText("println(\"hello\")")
        File(d, ".github/actions/x").mkdirs()
        File(d, ".github/actions/x/action.yml").writeText("runs: { using: composite }")
        val digest = workflowDigest(d)
        assertTrue(digest.contains("build.gradle.kts"))
        assertTrue(digest.contains(".github/actions/x/action.yml"))
        File(d, "build.gradle.kts").writeText("println(\"changed\")")
        assertTrue(workflowDigest(d) != digest)
    }

    @Test
    fun cleartextErrorIsNotRetryable() {
        assertFalse(networkError(IOException("Cleartext HTTP traffic to 192.168.1.20 not permitted")).retryable)
        assertTrue(networkError(IOException("Socket closed")).retryable)
    }
}
