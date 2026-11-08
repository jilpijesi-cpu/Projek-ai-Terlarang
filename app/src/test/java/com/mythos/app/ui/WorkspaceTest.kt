package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun artifactRulesSkipUploadsHiddenAndSource() {
        assertTrue(Workspace.isArtifact("index.html"))
        assertTrue(Workspace.isArtifact("docs/laporan.md"))
        assertFalse(Workspace.isArtifact("uploads/foto.png"))
        assertFalse(Workspace.isArtifact(".github/workflows/build.yml"))
        assertFalse(Workspace.isArtifact("src/Main.kt"))
    }

    @Test fun scanFindsProjectsAndArtifacts() {
        val dir = tmp.newFolder("proyek-1")
        File(dir, "index.html").writeText("<h1>hai</h1>")
        File(dir, "src").mkdirs()
        File(dir, "src/App.kt").writeText("fun main() {}")
        File(dir, "uploads").mkdirs()
        File(dir, "uploads/a.png").writeText("x")
        val meta = ChatMeta("proyek-1", "Tes", dir.path, 1L)

        val p = Workspace.scanProjects(listOf(meta)).single()
        assertEquals(3, p.files)

        val a = Workspace.scanArtifacts(listOf(meta))
        assertEquals(listOf("index.html"), a.map { it.rel })
    }

    @Test fun fileKinds() {
        assertTrue(Workspace.isText(File("a.kt")))
        assertTrue(Workspace.isWeb(File("a.HTML")))
        assertTrue(Workspace.isImage(File("a.png")))
        assertFalse(Workspace.isText(File("a.zip")))
    }

    @Test fun sizeFormat() {
        assertEquals("512 B", Workspace.size(512))
        assertEquals("1.5 KB", Workspace.size(1536))
    }
}

class TlsMessageTest {
    @Test fun tlsFailureIsExplainedAndNotRetryable() {
        val m = com.mythos.app.agent.explainTlsFailure(javax.net.ssl.SSLHandshakeException("Chain validation failed"))
        assertTrue(m.contains("Chain validation failed"))
        assertTrue(m.contains("tanggal"))
        assertTrue(m.contains("Jangan ulangi"))
    }
}
