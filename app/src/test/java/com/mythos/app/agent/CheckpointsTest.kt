package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CheckpointsTest {
    private class Setup(val root: File, val store: CheckpointStore, val journal: TaskJournal) {
        fun env() = ToolEnv(ProjectSandbox(root), SessionState(), { "" }, null, journal)
    }

    private fun setup(): Setup {
        val base = Files.createTempDirectory("mythos-ck").toFile()
        val root = File(base, "proj").apply { mkdirs() }
        val store = CheckpointStore(File(base, "ckpt"))
        return Setup(root, store, store.begin("proj", root, "uji"))
    }

    private suspend fun read(env: ToolEnv, path: String) = Read().run(JSONObject().put("file_path", path), env)
    private suspend fun write(env: ToolEnv, path: String, content: String) =
        Write().run(JSONObject().put("file_path", path).put("content", content), env)
    private suspend fun edit(env: ToolEnv, path: String, old: String, new: String) =
        Edit().run(JSONObject().put("file_path", path).put("old_string", old).put("new_string", new), env)
    private suspend fun delete(env: ToolEnv, path: String) = Delete().run(JSONObject().put("file_path", path), env)

    /** Satu tugas: ubah a.txt, buat src/deep/new.kt (folder baru), hapus b.txt. */
    private suspend fun typicalTask(s: Setup): List<FileChange> {
        File(s.root, "a.txt").writeText("satu\ndua\ntiga\n")
        File(s.root, "b.txt").writeText("hapus aku\n")
        val env = s.env()
        read(env, "a.txt")
        assertTrue(edit(env, "a.txt", "dua", "DUA").ok)
        assertTrue(write(env, "src/deep/new.kt", "fun x() {}\n").ok)
        assertTrue(delete(env, "b.txt").ok)
        return s.journal.finish()
    }

    @Test
    fun toolsRecordOriginalsAndSummarizeNetChanges() = runTest {
        val s = setup()
        val changes = typicalTask(s).associateBy { it.path }
        assertEquals(setOf("a.txt", "src/deep/new.kt", "b.txt"), changes.keys)
        val a = changes.getValue("a.txt")
        assertEquals(ChangeKind.MODIFIED, a.kind)
        assertEquals(1, a.added)
        assertEquals(1, a.removed)
        assertEquals(ChangeKind.ADDED, changes.getValue("src/deep/new.kt").kind)
        assertEquals(ChangeKind.DELETED, changes.getValue("b.txt").kind)
    }

    @Test
    fun diffShowsRemovedAndAddedLines() = runTest {
        val s = setup()
        typicalTask(s)
        val cp = s.store.open("proj", s.root, s.journal.id)
        val d = cp.diff("a.txt")
        assertTrue(d, d.contains("-dua") && d.contains("+DUA"))
        assertEquals("@@ -1 +1 @@\n+fun x() {}", cp.diff("src/deep/new.kt"))
        assertEquals("@@ -1 +1 @@\n-hapus aku", cp.diff("b.txt"))
    }

    @Test
    fun undoRestoresEverythingAndTidiesFoldersTheAgentCreated() = runTest {
        val s = setup()
        typicalTask(s)
        val report = s.store.open("proj", s.root, s.journal.id).undo()
        assertTrue(report.skipped.toString(), report.skipped.isEmpty())
        assertEquals(3, report.restored.size)
        assertEquals("satu\ndua\ntiga\n", File(s.root, "a.txt").readText())
        assertEquals("hapus aku\n", File(s.root, "b.txt").readText())
        assertFalse(File(s.root, "src").exists())
    }

    @Test
    fun undoSkipsFilesChangedAgainAfterTheTaskAndOnlyWorksOnce() = runTest {
        val s = setup()
        typicalTask(s)
        File(s.root, "a.txt").writeText("diubah tugas berikutnya\n")      // pekerjaan sesudahnya jangan hilang
        File(s.root, "b.txt").writeText("dibuat ulang\n")
        val cp = s.store.open("proj", s.root, s.journal.id)
        val report = cp.undo()
        assertEquals(setOf("a.txt", "b.txt"), report.skipped.map { it.first }.toSet())
        assertEquals("diubah tugas berikutnya\n", File(s.root, "a.txt").readText())
        assertEquals("dibuat ulang\n", File(s.root, "b.txt").readText())
        assertFalse(File(s.root, "src/deep/new.kt").exists())
        assertTrue(cp.undone)
        assertTrue(cp.undo().skipped[0].second.contains("sudah dibatalkan"))
    }

    @Test
    fun changesRevertedByTheAgentLeaveNoTrace() = runTest {
        val s = setup()
        File(s.root, "a.txt").writeText("asli\n")
        val env = s.env()
        read(env, "a.txt")
        assertTrue(write(env, "a.txt", "ubah\n").ok)
        assertTrue(write(env, "a.txt", "asli\n").ok)          // dikembalikan sendiri
        assertTrue(write(env, "temp.txt", "x").ok)
        assertTrue(delete(env, "temp.txt").ok)                // dibuat lalu dihapus
        assertTrue(s.journal.finish().isEmpty())
        assertFalse(s.journal.dir.exists())
    }

    @Test
    fun binaryFilesAreSummarizedWithoutLineCounts() = runTest {
        val s = setup()
        File(s.root, "ikon.bin").writeBytes(byteArrayOf(0, 1, 2))
        s.journal.beforeChange(File(s.root.canonicalFile, "ikon.bin"))
        File(s.root, "ikon.bin").writeBytes(byteArrayOf(0, 9, 9))
        val c = s.journal.finish().single()
        assertTrue(c.binary)
        assertTrue(s.store.open("proj", s.root, s.journal.id).diff("ikon.bin").contains("biner"))
    }

    @Test
    fun journalWorksWhenTheRootIsASymlink() = runTest {
        // Di Android filesDir adalah symlink, sedangkan sandbox memakai path kanonik.
        val base = Files.createTempDirectory("mythos-ck").toFile()
        val real = File(base, "real").apply { mkdirs() }
        val link = File(base, "link")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (e: Exception) {
            assumeTrue("symlink tidak didukung di sini", false)
        }
        val store = CheckpointStore(File(base, "ckpt"))
        val journal = store.begin("p", link, "uji")
        File(real, "s.txt").writeText("lama\n")
        val env = ToolEnv(ProjectSandbox(link), SessionState(), { "" }, null, journal)
        read(env, "s.txt")
        assertTrue(edit(env, "s.txt", "lama", "baru").ok)
        assertEquals(1, journal.finish().size)
    }

    @Test
    fun pruneKeepsOnlyTheNewestCheckpoints() {
        val base = Files.createTempDirectory("mythos-ck").toFile()
        val root = File(base, "proj").apply { mkdirs() }
        val store = CheckpointStore(File(base, "ckpt"))
        repeat(20) { store.begin("proj", root, "n$it") }
        assertEquals(CheckpointStore.MAX_KEEP, File(base, "ckpt/proj").listFiles()!!.size)
    }

    @Test
    fun lineDiffCountsAndFormats() {
        assertEquals(1 to 1, LineDiff.counts(listOf("a", "b", "c"), listOf("a", "B", "c")))
        assertEquals(2 to 0, LineDiff.counts(listOf("a"), listOf("a", "b", "c")))
        assertEquals(0 to 2, LineDiff.counts(listOf("a", "b", "c"), listOf("c")))
        assertEquals(1 to 2, LineDiff.counts(listOf("a", "b", "c", "d", "e"), listOf("a", "c", "x", "e")))
        assertEquals(0 to 0, LineDiff.counts(listOf("a", "b"), listOf("a", "b")))
        assertEquals("@@ -1 +1 @@\n satu\n-dua\n+DUA\n tiga", LineDiff.unified(listOf("satu", "dua", "tiga"), listOf("satu", "DUA", "tiga")))
    }

    @Test
    fun unifiedShowsOnlyThreeLinesOfContextAndCanBeTruncated() {
        val a = (0 until 50).map { "baris $it" }
        val b = a.toMutableList().also { it[25] = "BARIS 25" }
        val u = LineDiff.unified(a, b)
        assertTrue(u, u.startsWith("@@ -23 +23 @@"))
        assertEquals(1 + 3 + 2 + 3, u.lines().size)
        val cut = LineDiff.unified(emptyList(), (0 until 10).map { "x$it" }, maxLines = 4)
        assertTrue(cut, cut.contains("… (dipotong; 6 baris lagi)"))
    }

    @Test
    fun hugeFilesFallBackToReplaceAllInsteadOfRunningOutOfMemory() {
        val a = (0 until 2500).map { "a$it" }
        val b = (0 until 2500).map { "b$it" }
        assertEquals(2500 to 2500, LineDiff.counts(a, b))
    }

    @Test
    fun interruptedRunIsRecoveredFromTheManifest() = runTest {
        val s = setup()
        File(s.root, "a.txt").writeText("asli\n")
        val env = s.env()
        read(env, "a.txt")
        assertTrue(edit(env, "a.txt", "asli", "ubah").ok)
        assertTrue(write(env, "n.txt", "baru\n").ok)
        // proses mati di sini: finish() tidak pernah dipanggil
        val recovered = s.store.recoverInterrupted("proj", s.root)
        assertEquals(1, recovered.size)
        assertEquals(setOf("a.txt", "n.txt"), recovered[0].second.map { it.path }.toSet())
        val report = s.store.open("proj", s.root, recovered[0].first).undo()
        assertTrue(report.skipped.toString(), report.skipped.isEmpty())
        assertEquals("asli\n", File(s.root, "a.txt").readText())
        assertFalse(File(s.root, "n.txt").exists())
        assertTrue(s.store.recoverInterrupted("proj", s.root).isEmpty())   // tidak diproses dua kali
    }

    @Test
    fun finishedCheckpointsAreNotRecoveredAgain() = runTest {
        val s = setup()
        typicalTask(s)
        assertTrue(s.store.recoverInterrupted("proj", s.root).isEmpty())
    }
}
