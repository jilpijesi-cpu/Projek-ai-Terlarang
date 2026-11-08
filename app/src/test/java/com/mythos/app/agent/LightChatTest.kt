package com.mythos.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LightChatTest {
    private fun dir(): File = Files.createTempDirectory("mythos-light").toFile()

    @Test
    fun greetingInEmptyProjectIsLight() {
        assertTrue(LightChat.eligible(dir(), "halo", false))
        assertTrue(LightChat.eligible(dir(), "Halo Mythos!", false))
        assertTrue(LightChat.eligible(dir(), "terima kasih", false))
    }

    @Test
    fun realTasksAndContextAreNotLight() {
        val d = dir()
        assertFalse(LightChat.eligible(d, "buat kalkulator", false))
        assertFalse(LightChat.eligible(d, "halo", true))
        assertFalse(LightChat.eligible(d, "halo, tolong audit zip ini", false))
        File(d, "CLAUDE.md").writeText("aturan")
        assertFalse(LightChat.eligible(d, "halo", false))
        val d2 = dir()
        File(d2, "uploads").mkdirs()
        File(d2, "uploads/a.txt").writeText("x")
        assertFalse(LightChat.eligible(d2, "halo", false))
    }

    @Test
    fun lightPromptIsSmallAndHasNoTools() {
        val light = Protocol.lightSystemPrompt("BOS")
        val full = Protocol.systemPrompt(ToolRegistry.default(false), false)
        assertTrue(light.length < 2_500)
        assertFalse(light.contains("# Tools"))
        assertTrue(light.contains("BOS"))
        assertTrue(light.length * 8 < full.length)
    }

    @Test
    fun casesSectionCanBeLeftOut() {
        val reg = ToolRegistry.default(false)
        val with = Protocol.systemPrompt(reg, false)
        val without = Protocol.systemPrompt(reg, false, includeCases = false)
        assertTrue(with.contains("# Complaints, disputes and official letters"))
        assertFalse(without.contains("# Complaints, disputes and official letters"))
        assertTrue(without.contains("# Honesty (highest priority)"))
        assertTrue(without.length < with.length - 8_000)
    }

    @Test
    fun fixedPartTooLargeOnlyWhenSystemAloneNearsTheLimit() {
        val msg = "tokens per minute (TPM): Limit 8000, Requested 9620"
        assertTrue(ContextManager.fixedPartTooLarge(31_000, msg))
        assertFalse(ContextManager.fixedPartTooLarge(8_000, msg))
        assertFalse(ContextManager.fixedPartTooLarge(31_000, "tanpa angka"))
        assertTrue(ContextManager.describeFixedTooLarge(31_000, msg).contains("8000"))
    }
}
