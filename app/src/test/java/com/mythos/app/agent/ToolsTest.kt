package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolsTest {
    private fun newEnv(): Pair<ToolEnv, File> {
        val dir = Files.createTempDirectory("mythos").toFile()
        return ToolEnv(ProjectSandbox(dir), SessionState(), { "" }, null) to dir
    }

    private fun edit(path: String, old: String, new: String, all: Boolean = false) =
        JSONObject().put("file_path", path).put("old_string", old).put("new_string", new).put("replace_all", all)

    @Test
    fun sandboxBlocksEscape() {
        val (env, _) = newEnv()
        assertThrows(SecurityException::class.java) { env.sandbox.resolve("../evil.txt") }
        assertThrows(SecurityException::class.java) { env.sandbox.resolve("a/../../evil.txt") }
    }

    @Test
    fun readUsesCatNFormat() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("satu\ndua")
        val r = Read().run(JSONObject().put("file_path", "a.txt"), env)
        assertTrue(r.text, r.ok)
        assertTrue(r.text.startsWith("     1\tsatu\n     2\tdua"))
    }

    @Test
    fun readSupportsOffsetAndLimit() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText((1..10).joinToString("\n") { "baris$it" })
        val r = Read().run(JSONObject().put("file_path", "a.txt").put("offset", 4).put("limit", 2), env)
        assertTrue(r.text.contains("     4\tbaris4"))
        assertTrue(r.text.contains("     5\tbaris5"))
        assertFalse(r.text.contains("baris6\n"))
        assertTrue(r.text.contains("offset=6"))
    }

    @Test
    fun editRequiresReadFirst() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("halo dunia")
        val r = Edit().run(edit("a.txt", "halo", "hai"), env)
        assertFalse(r.ok)
        assertTrue(r.text.contains("has not been read yet"))
        assertEquals("halo dunia", File(dir, "a.txt").readText())
    }

    @Test
    fun editAfterReadWorks() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("halo dunia")
        Read().run(JSONObject().put("file_path", "a.txt"), env)
        val r = Edit().run(edit("a.txt", "halo", "hai"), env)
        assertTrue(r.text, r.ok)
        assertEquals("hai dunia", File(dir, "a.txt").readText())
        assertEquals(listOf("a.txt"), r.changed)
    }

    @Test
    fun editRejectsAmbiguousMatchUnlessReplaceAll() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("a\nb\na\n")
        Read().run(JSONObject().put("file_path", "a.txt"), env)
        val r = Edit().run(edit("a.txt", "a", "x"), env)
        assertFalse(r.ok)
        assertTrue(r.text.contains("Found 2 matches"))
        val all = Edit().run(edit("a.txt", "a", "x", all = true), env)
        assertTrue(all.text, all.ok)
        assertEquals("x\nb\nx\n", File(dir, "a.txt").readText())
    }

    @Test
    fun editDetectsExternalChange() = runTest {
        val (env, dir) = newEnv()
        val f = File(dir, "a.txt")
        f.writeText("satu")
        Read().run(JSONObject().put("file_path", "a.txt"), env)
        f.writeText("dua")
        val r = Edit().run(edit("a.txt", "dua", "tiga"), env)
        assertFalse(r.ok)
        assertTrue(r.text.contains("modified since"))
    }

    @Test
    fun editReportsNotFoundAndNoOp() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("halo")
        Read().run(JSONObject().put("file_path", "a.txt"), env)
        assertTrue(Edit().run(edit("a.txt", "zzz", "y"), env).text.contains("String to replace not found"))
        assertTrue(Edit().run(edit("a.txt", "halo", "halo"), env).text.contains("No changes to make"))
    }

    @Test
    fun editWithEmptyOldStringCreatesNewFile() = runTest {
        val (env, dir) = newEnv()
        val r = Edit().run(edit("baru/x.txt", "", "isi"), env)
        assertTrue(r.text, r.ok)
        assertEquals("isi", File(dir, "baru/x.txt").readText())
    }

    @Test
    fun overwriteNeedsReadButCreateDoesNot() = runTest {
        val (env, dir) = newEnv()
        val create = Write().run(JSONObject().put("file_path", "src/x.txt").put("content", "1"), env)
        assertTrue(create.ok)
        File(dir, "old.txt").writeText("lama")
        val over = Write().run(JSONObject().put("file_path", "old.txt").put("content", "baru"), env)
        assertFalse(over.ok)
        assertTrue(over.text.contains("has not been read yet"))
    }

    @Test
    fun globMatchesByNameAndByPath() = runTest {
        val (env, dir) = newEnv()
        File(dir, "src/util").mkdirs()
        File(dir, "src/Main.kt").writeText("a")
        File(dir, "src/util/Helper.kt").writeText("b")
        File(dir, "README.md").writeText("c")
        val kt = Glob().run(JSONObject().put("pattern", "**/*.kt"), env).text.lines().toSet()
        assertEquals(setOf("src/Main.kt", "src/util/Helper.kt"), kt)
        val dotSlash = Glob().run(JSONObject().put("pattern", "./*.kt"), env).text.lines().toSet()
        assertEquals(setOf("src/Main.kt", "src/util/Helper.kt"), dotSlash)
        assertEquals("README.md", Glob().run(JSONObject().put("pattern", "*.md"), env).text)
        assertEquals("src/Main.kt", Glob().run(JSONObject().put("pattern", "src/*.kt"), env).text)
        assertEquals("No files found", Glob().run(JSONObject().put("pattern", "*.zzz"), env).text)
    }

    @Test
    fun deleteClearsDirtyStateViaReadPaths() = runTest {
        val (env, dir) = newEnv()
        File(dir, "temp.txt").writeText("x")
        val d = Delete().run(JSONObject().put("file_path", "temp.txt"), env)
        assertTrue(d.ok)
        assertEquals(listOf("temp.txt"), d.readPaths)
    }

    @Test
    fun grepModes() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.kt").writeText("fun a()\nval x\nfun b()\n")
        File(dir, "b.txt").writeText("fun c()\n")
        val files = Grep().run(JSONObject().put("pattern", "fun \\w+").put("glob", "*.kt"), env).text
        assertEquals("a.kt", files)
        val content = Grep().run(JSONObject().put("pattern", "fun \\w+").put("output_mode", "content").put("glob", "*.kt"), env).text
        assertEquals("a.kt:1:fun a()\na.kt:3:fun b()", content)
        val count = Grep().run(JSONObject().put("pattern", "fun").put("output_mode", "count"), env).text.lines().toSet()
        assertEquals(setOf("a.kt:2", "b.txt:1"), count)
        val ctx = Grep().run(JSONObject().put("pattern", "val x").put("output_mode", "content").put("-C", 1), env).text
        assertEquals("a.kt-1-fun a()\na.kt:2:val x\na.kt-3-fun b()", ctx)
        assertEquals("No matches found", Grep().run(JSONObject().put("pattern", "tidakada"), env).text)
        assertFalse(Grep().run(JSONObject().put("pattern", "fun ("), env).ok)
    }

    @Test
    fun grepIsCaseSensitiveByDefault() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("Halo")
        assertEquals("No matches found", Grep().run(JSONObject().put("pattern", "halo"), env).text)
        assertEquals("a.txt", Grep().run(JSONObject().put("pattern", "halo").put("-i", true), env).text)
    }

    @Test
    fun todoWriteStoresPlan() = runTest {
        val (env, _) = newEnv()
        val todos = JSONArray()
            .put(JSONObject().put("content", "Baca kode").put("status", "completed"))
            .put(JSONObject().put("content", "Perbaiki bug").put("status", "in_progress").put("activeForm", "Memperbaiki bug"))
            .put(JSONObject().put("content", "Tes").put("status", "pending"))
        val r = TodoWrite().run(JSONObject().put("todos", todos), env)
        assertTrue(r.ok)
        assertEquals(listOf(StepStatus.DONE, StepStatus.DOING, StepStatus.TODO), env.session.plan.map { it.status })
    }

    @Test
    fun registryLookupIsCaseInsensitive() {
        val reg = ToolRegistry.default(false)
        assertEquals("Read", reg.get("read")?.name)
        assertEquals("Grep", reg.get("Grep")?.name)
        assertTrue(reg.get("GithubBuild") == null)
        assertTrue(ToolRegistry.default(true).get("GithubBuild") != null)
    }

    @Test
    fun editThatDeletesTailDoesNotCrashOnSnippet() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("1\n2\n3\n4\n5\n6\n7\n8\nakhir")
        Read().run(JSONObject().put("file_path", "a.txt"), env)
        val r = Edit().run(edit("a.txt", "5\n6\n7\n8\nakhir", ""), env)
        assertTrue(r.text, r.ok)
        assertEquals("1\n2\n3\n4\n", File(dir, "a.txt").readText())
    }

    @Test
    fun globPatternWithSlashIsRelativeToPath() = runTest {
        val (env, dir) = newEnv()
        File(dir, "src/main").mkdirs()
        File(dir, "src/main/A.kt").writeText("class A")
        val r = Glob().run(JSONObject().put("pattern", "main/*.kt").put("path", "src"), env)
        assertTrue(r.text, r.ok)
        assertTrue(r.text, r.text.contains("src/main/A.kt"))
    }

    @Test
    fun grepGlobWithSlashIsRelativeToPath() = runTest {
        val (env, dir) = newEnv()
        File(dir, "src/main").mkdirs()
        File(dir, "src/main/A.kt").writeText("fun a() {}")
        val r = Grep().run(
            JSONObject().put("pattern", "fun").put("path", "src").put("glob", "main/*.kt").put("output_mode", "files_with_matches"), env
        )
        assertTrue(r.text, r.text.contains("src/main/A.kt"))
    }

    @Test
    fun editMatchesMultilineOldStringInCrlfFile() = runTest {
        val (env, dir) = newEnv()
        File(dir, "w.txt").writeBytes("satu\r\ndua\r\ntiga\r\n".toByteArray())
        Read().run(JSONObject().put("file_path", "w.txt"), env)
        val r = Edit().run(edit("w.txt", "satu\ndua", "SATU\nDUA"), env)
        assertTrue(r.text, r.ok)
        assertEquals("SATU\r\nDUA\r\ntiga\r\n", File(dir, "w.txt").readText())
    }

    @Test
    fun readDoesNotShowAPhantomLineAfterTheTrailingNewline() = runTest {
        val (env, dir) = newEnv()
        File(dir, "a.txt").writeText("satu\ndua\n")
        val r = Read().run(JSONObject().put("file_path", "a.txt"), env)
        assertTrue(r.text, r.ok)
        assertFalse(r.text, r.text.contains("     3\t"))
    }

    @Test
    fun contentLinesDropsOnlyTheEmptyElementAfterTheFinalNewline() {
        assertEquals(listOf("a", "b"), contentLines("a\nb\n"))
        assertEquals(listOf("a", "b"), contentLines("a\nb"))
        assertEquals(listOf(""), contentLines(""))
        assertEquals(listOf("a", ""), contentLines("a\n\n"))
    }
}
