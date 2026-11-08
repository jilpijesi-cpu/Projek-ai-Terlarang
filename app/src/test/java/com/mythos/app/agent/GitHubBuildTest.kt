package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class FakeGitHub(
    private val conclusion: String = "success",
    private val runsAppear: Boolean = true,
) : GitHubTransport {
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<Pair<String, String>>()   // "METHOD path" -> isi permintaan
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        val path = url.removePrefix("https://api.github.com/repos/me/app")
        calls.add("$method $path")
        if (body != null) bodies.add("$method $path" to String(body, Charsets.UTF_8))
        fun ok(j: String, code: Int = 200) = HttpResult(code, j.toByteArray(), emptyMap())
        return when {
            method == "GET" && path.startsWith("/git/ref/heads/") -> ok("""{"message":"Not Found"}""", 404)
            method == "POST" && path == "/git/blobs" -> ok("""{"sha":"${"b".repeat(40)}"}""", 201)
            method == "POST" && path == "/git/trees" -> ok("""{"sha":"${"t".repeat(40)}"}""", 201)
            method == "POST" && path == "/git/commits" -> ok("""{"sha":"${"c".repeat(40)}"}""", 201)
            method == "POST" && path == "/git/refs" -> ok("""{"ref":"x"}""", 201)
            method == "GET" && path.startsWith("/actions/runs?") ->
                if (runsAppear) ok("""{"workflow_runs":[{"id":7,"name":"CI","status":"completed","conclusion":"$conclusion","html_url":"https://gh/run/7"}]}""")
                else ok("""{"workflow_runs":[]}""")
            method == "GET" && path.startsWith("/actions/runs/7/jobs") ->
                ok("""{"jobs":[{"id":9,"name":"build","conclusion":"failure","steps":[{"name":"Gradle","conclusion":"failure"}]}]}""")
            method == "GET" && path == "/actions/jobs/9/logs" ->
                HttpResult(302, ByteArray(0), mapOf("location" to "https://blob.example/log"))
            url == "https://blob.example/log" -> {
                assertFalse(headers.containsKey("Authorization"))
                ok("2026-01-01T00:00:00.0000000Z e: Main.kt: Unresolved reference: foo\n2026-01-01T00:00:01.0000000Z BUILD FAILED")
            }
            else -> ok("""{"message":"unexpected $method $path"}""", 500)
        }
    }
}

class GitHubBuildTest {
    private val cfg = GitHubConfig("tok", "me", "app")

    private fun project(withWorkflow: Boolean): File {
        val d = Files.createTempDirectory("proyek-x").toFile()
        File(d, "a.txt").writeText("halo")
        if (withWorkflow) {
            File(d, ".github/workflows").mkdirs()
            File(d, ".github/workflows/build.yml").writeText("on: push\n")
        }
        return d
    }

    @Test
    fun blobShaMatchesGit() {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", gitBlobSha("hello\n".toByteArray()))
    }

    @Test
    fun configParsing() {
        assertEquals("me/app", GitHubConfig.parse("t", "https://github.com/me/app.git")?.slug)
        assertNull(GitHubConfig.parse("", "me/app"))
        assertNull(GitHubConfig.parse("t", "bukan repo"))
    }

    @Test
    fun branchNameIsSanitized() {
        assertEquals("mythos/proyek-2026-1", branchFor("proyek 2026/1"))
    }

    @Test
    fun successfulRunVerifies() = runTest {
        val fake = FakeGitHub()
        val r = GitHubBuildService(cfg, fake, pollMs = 10).build(project(true), 60)
        assertTrue(r.text, r.ok)
        assertTrue(fake.calls.contains("POST /git/refs"))
        assertTrue(r.text.contains("Semua run sukses"))
    }

    @Test
    fun failedRunReturnsLogExcerpt() = runTest {
        val r = GitHubBuildService(cfg, FakeGitHub(conclusion = "failure"), pollMs = 10).build(project(true), 60)
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("Unresolved reference: foo"))
        assertTrue(r.text.contains("Gradle"))
    }

    @Test
    fun missingWorkflowStopsBeforeAnyRequest() = runTest {
        val fake = FakeGitHub()
        val r = GitHubBuildService(cfg, fake, pollMs = 10).build(project(false), 60)
        assertFalse(r.ok)
        assertTrue(r.text.contains(".github/workflows"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun noRunsExplainsTrigger() = runTest {
        val r = GitHubBuildService(cfg, FakeGitHub(runsAppear = false), pollMs = 1000).build(project(true), 60)
        assertFalse(r.ok)
        assertTrue(r.text.contains("push"))
    }

    @Test
    fun toolWithoutConfigFailsClearly() = runTest {
        val dir = Files.createTempDirectory("mythos").toFile()
        val env = ToolEnv(ProjectSandbox(dir), SessionState(), { "" }, null)
        val r = GithubBuild().run(JSONObject(), env)
        assertFalse(r.ok)
        assertTrue(r.text.contains("GitHub"))
    }

    @Test
    fun emptyGradleWrapperJarIsNotPushed() = runTest {
        val d = project(true)
        File(d, "gradle/wrapper").mkdirs()
        File(d, "gradle/wrapper/gradle-wrapper.jar").writeBytes(ByteArray(0))
        File(d, "gradle/wrapper/gradle-wrapper.properties").writeText("distributionUrl=x")
        val fake = FakeGitHub()
        val r = GitHubBuildService(cfg, fake, pollMs = 10).build(d, 60)
        assertTrue(r.text, r.ok)
        val tree = fake.bodies.first { it.first == "POST /git/trees" }.second
        assertTrue(tree, tree.contains("gradle-wrapper.properties"))
        assertFalse(tree, tree.contains("gradle-wrapper.jar"))
    }

    @Test
    fun attachmentOriginalsAreNotPushedButExtractedFoldersAre() = runTest {
        val d = project(true)
        File(d, "uploads/proj").mkdirs()
        File(d, "uploads/foto.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(d, "uploads/laporan.txt").writeText("pribadi")
        File(d, "uploads/proj/a.kt").writeText("fun a() {}")
        val fake = FakeGitHub()
        val r = GitHubBuildService(cfg, fake, pollMs = 10).build(d, 60)
        assertTrue(r.text, r.ok)
        val tree = fake.bodies.first { it.first == "POST /git/trees" }.second
        assertTrue(tree, tree.contains("uploads/proj/a.kt"))
        assertFalse(tree, tree.contains("uploads/foto.jpg"))
        assertFalse(tree, tree.contains("uploads/laporan.txt"))
    }

    @Test
    fun textFilesGoInlineAndBinariesAsBlobs() = runTest {
        val d = project(true)
        File(d, "ikon.bin").writeBytes(byteArrayOf(0, 1, 2, 3))   // mengandung NUL -> biner
        val fake = FakeGitHub()
        val r = GitHubBuildService(cfg, fake, pollMs = 10).build(d, 60)
        assertTrue(r.text, r.ok)
        assertEquals(1, fake.calls.count { it == "POST /git/blobs" })
        val tree = fake.bodies.first { it.first == "POST /git/trees" }.second
        assertTrue(tree, tree.contains("\"content\""))
        assertTrue(tree, tree.contains("halo"))
    }

    @Test
    fun branchNameNeverContainsIllegalRefSequences() {
        assertEquals("mythos/a.b", branchFor("a..b"))
        assertEquals("mythos/proyek", branchFor(".."))
        assertEquals("mythos/x", branchFor("x.lock"))
        assertEquals("mythos/x", branchFor("x.lock-"))
        assertEquals("mythos/x", branchFor("x.lock.lock"))
    }

    @Test
    fun workflowDigestListsEveryWorkflowFile() {
        val d = project(true)
        assertTrue(workflowDigest(d).contains(".github/workflows/build.yml"))
        assertTrue(workflowDigest(d).contains("on: push"))
        assertEquals("", workflowDigest(project(false)))
    }

    @Test
    fun losslessUtf8DetectionRejectsBinaryAndBrokenText() {
        assertTrue(isLosslessUtf8("halo dunia ✓\n".toByteArray()))
        assertFalse(isLosslessUtf8(byteArrayOf(104, 0, 105)))             // mengandung NUL
        assertFalse(isLosslessUtf8(byteArrayOf(0xC3.toByte(), 0x28)))     // urutan UTF-8 tidak sah
    }

    @Test
    fun secondaryRateLimitGetsAnActionableHint() = runTest {
        val limited = GitHubTransport { _, _, _, _ ->
            HttpResult(403, """{"message":"You have exceeded a secondary rate limit."}""".toByteArray(), mapOf("retry-after" to "60"))
        }
        val r = GitHubBuildService(cfg, limited, pollMs = 10).build(project(true), 60)
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("membatasi laju"))
    }

    @Test
    fun missingWorkflowPermissionIsExplained() = runTest {
        val denied = GitHubTransport { _, _, _, _ ->
            HttpResult(403, """{"message":"refusing to allow a Personal Access Token to create or update workflow .github/workflows/build.yml without workflow scope"}""".toByteArray(), emptyMap())
        }
        val r = GitHubBuildService(cfg, denied, pollMs = 10).build(project(true), 60)
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("Workflows"))
    }
}
