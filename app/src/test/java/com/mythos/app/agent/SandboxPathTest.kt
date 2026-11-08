package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SandboxPathTest {
    private fun root(): File = Files.createTempDirectory("proyek-20261106-130006").toFile()

    @Test
    fun projectFolderNamePrefixIsNotPartOfThePath() {
        val dir = root()
        val sb = ProjectSandbox(dir)
        assertEquals(File(sb.root, "app/src/Main.kt"), sb.resolve("${sb.root.name}/app/src/Main.kt"))
        assertEquals(File(sb.root, "settings.gradle.kts"), sb.resolve("./${sb.root.name}/settings.gradle.kts"))
        assertEquals(sb.root, sb.resolve(sb.root.name))
        assertEquals("app/src/Main.kt", sb.relative(sb.resolve("${sb.root.name}/app/src/Main.kt")))
    }

    @Test
    fun absolutePathStillWorks() {
        val sb = ProjectSandbox(root())
        assertEquals(File(sb.root, "a.txt"), sb.resolve(sb.root.path + "/a.txt"))
    }

    @Test
    fun realSubfolderWithSameNameIsRespected() {
        val sb = ProjectSandbox(root())
        File(sb.root, sb.root.name).mkdirs()
        assertEquals(File(File(sb.root, sb.root.name), "x.txt"), sb.resolve("${sb.root.name}/x.txt"))
    }

    @Test
    fun escapingStillRejected() {
        val sb = ProjectSandbox(root())
        var failed = false
        try { sb.resolve("../x") } catch (e: SecurityException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun firstMessageTellsNotToPrefixFolderName() {
        val msg = Protocol.firstMessage("buat app", "(kosong)", "", "/data/projects/proyek-1")
        assertTrue(msg.contains("NOT part of a path"))
    }
}
