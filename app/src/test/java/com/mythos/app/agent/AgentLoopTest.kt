package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class ScriptedLlm(private val replies: List<String>) : LlmClient {
    var calls = 0
    val seen = mutableListOf<List<Msg>>()
    override suspend fun complete(system: String, messages: List<Msg>): String {
        seen.add(messages.toList())
        return replies[calls++]
    }
}

private fun tu(name: String, input: String) = "<tool_use>{\"name\":\"$name\",\"input\":$input}</tool_use>"

class AgentLoopTest {
    private fun agent(
        llm: LlmClient,
        dir: File,
        cfg: AgentConfig = AgentConfig(),
        approver: Approver = Approver { _, _ -> true },
    ) = Agent(
        llm, ToolRegistry.default(false, withPlan = cfg.permissionMode == PermissionMode.PLAN),
        ProjectSandbox(dir), approver, { "" }, null, cfg
    )

    private fun newDir(): File = Files.createTempDirectory("mythos-loop").toFile()

    @Test
    fun finalAnswerIsAReplyWithoutToolCalls() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(
            listOf(
                "Membuat file.\n" + tu("Write", """{"file_path":"hello.txt","content":"halo"}"""),
                "Sudah dibuat.",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("buat hello.txt") { events.add(it) }
        assertTrue(res.success)
        assertEquals("Sudah dibuat.", res.summary)
        assertEquals(2, res.steps)
        assertEquals("halo", File(dir, "hello.txt").readText())
        // tidak ada gerbang: jawaban tidak ditolak, hanya ditandai belum terverifikasi
        assertFalse(res.verified)
        assertTrue(events.any { it is AgentEvent.Thought && it.text == "Membuat file." })
        assertEquals(0, events.count { it is AgentEvent.Notice })
    }

    @Test
    fun rereadingChangedFileMarksVerified() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(
            listOf(
                tu("Write", """{"file_path":"a.txt","content":"x"}"""),
                tu("Read", """{"file_path":"a.txt"}"""),
                "Selesai.",
            )
        )
        val res = agent(llm, dir).execute("x") { }
        assertTrue(res.verified)
    }

    @Test
    fun writeBeforeReadFollowedByReadAndIdenticalWriteSucceeds() = runTest {
        val dir = newDir()
        File(dir, "existing.txt").writeText("lama")
        val llm = ScriptedLlm(
            listOf(
                tu("Write", """{"file_path":"existing.txt","content":"baru"}"""),
                tu("Read", """{"file_path":"existing.txt"}"""),
                tu("Write", """{"file_path":"existing.txt","content":"baru"}"""),
                "Selesai.",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("timpa existing.txt") { events.add(it) }
        assertTrue(res.success)
        assertEquals("baru", File(dir, "existing.txt").readText())
        assertFalse(events.any { it is AgentEvent.Notice && it.text.contains("duplikat") })
    }

    @Test
    fun severalToolCallsInOneReplyRunAndReturnInOneMessage() = runTest {
        val dir = newDir()
        File(dir, "a.txt").writeText("satu")
        File(dir, "b.txt").writeText("dua")
        val llm = ScriptedLlm(
            listOf(
                tu("Read", """{"file_path":"a.txt"}""") + "\n" + tu("Read", """{"file_path":"b.txt"}"""),
                "Keduanya sudah dibaca.",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("baca") { events.add(it) }
        assertTrue(res.success)
        assertEquals(2, events.count { it is AgentEvent.ToolEnd })
        assertEquals(2, res.steps)
        val lastUser = llm.seen.last().last().text
        assertTrue(lastUser.contains("<tool_result id=\"toolu_1\" name=\"Read\" is_error=\"false\">"))
        assertTrue(lastUser.contains("<tool_result id=\"toolu_2\""))
        assertTrue(lastUser.contains("1\tsatu"))
    }

    @Test
    fun duplicateCallInUnchangedWorldIsBlocked() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(
            listOf(
                tu("LS", """{"path":"."}"""),
                tu("LS", """{"path":"."}"""),
                "selesai",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("lihat") { events.add(it) }
        assertTrue(res.success)
        assertEquals(1, events.count { it is AgentEvent.ToolEnd })
        assertTrue(events.any { it is AgentEvent.Notice && it.text.contains("duplikat") })
    }

    @Test
    fun brokenToolBlockIsRepaired() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(listOf("<tool_use>{\"name\": \"Read\", \"input\": {", "ok"))
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("x") { events.add(it) }
        assertTrue(res.success)
        assertEquals("ok", res.summary)
        assertTrue(events.any { it is AgentEvent.Notice && it.text.contains("Format") })
    }

    @Test
    fun abortsAfterTooManyConsecutiveErrors() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(listOf(tu("a1", "{}"), tu("a2", "{}"), tu("a3", "{}")))
        val res = agent(llm, dir, AgentConfig(maxConsecutiveErrors = 2, abortAfterErrors = 3)).execute("x") { }
        assertFalse(res.success)
        assertTrue(res.summary.contains("Terjebak"))
    }

    @Test
    fun stopsAtStepBudget() = runTest {
        val dir = newDir()
        File(dir, "f.txt").writeText("x")
        val llm = ScriptedLlm(listOf(tu("Read", """{"file_path":"f.txt"}"""), tu("LS", """{"path":"."}""")))
        val res = agent(llm, dir, AgentConfig(maxSteps = 2)).execute("x") { }
        assertFalse(res.success)
        assertTrue(res.summary.contains("Batas 2 langkah"))
    }

    @Test
    fun greetingIsAnsweredDirectlyWithoutTools() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(listOf("Halo! Ada yang bisa dibantu?"))
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("halo") { events.add(it) }
        assertTrue(res.success)
        assertEquals(0, events.count { it is AgentEvent.ToolStart })
        assertEquals("Halo! Ada yang bisa dibantu?", res.summary)
        assertEquals(1, llm.calls)
    }

    @Test
    fun defaultModeAsksBeforeWritingAndRespectsDenial() = runTest {
        val dir = newDir()
        var asked = 0
        val llm = ScriptedLlm(
            listOf(
                tu("Write", """{"file_path":"x.txt","content":"1"}"""),
                "Tidak jadi menulis.",
            )
        )
        val res = agent(llm, dir, AgentConfig(permissionMode = PermissionMode.DEFAULT), Approver { _, _ -> asked++; false })
            .execute("tulis") { }
        assertTrue(res.success)
        assertEquals(1, asked)
        assertFalse(File(dir, "x.txt").exists())
    }

    @Test
    fun acceptEditsDoesNotAskForWritesButAsksForDelete() = runTest {
        val dir = newDir()
        File(dir, "old.txt").writeText("x")
        val asked = mutableListOf<String>()
        val llm = ScriptedLlm(
            listOf(
                tu("Write", """{"file_path":"n.txt","content":"1"}""") + "\n" + tu("Delete", """{"file_path":"old.txt"}"""),
                "ok",
            )
        )
        agent(llm, dir, approver = Approver { t, _ -> asked.add(t); true }).execute("x") { }
        assertEquals(listOf("Delete"), asked)
        assertTrue(File(dir, "n.txt").exists())
        assertFalse(File(dir, "old.txt").exists())
    }

    @Test
    fun planModeBlocksWritesUntilPlanIsApproved() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(
            listOf(
                tu("Write", """{"file_path":"a.txt","content":"1"}"""),
                tu("ExitPlanMode", """{"plan":"1. buat a.txt"}"""),
                tu("Write", """{"file_path":"a.txt","content":"1"}"""),
                "Selesai.",
            )
        )
        val res = agent(llm, dir, AgentConfig(permissionMode = PermissionMode.PLAN)).execute("buat a.txt") { }
        assertTrue(res.success)
        assertEquals("1", File(dir, "a.txt").readText())
        assertTrue(llm.seen[1].last().text.contains("Plan mode is active"))
    }

    @Test
    fun announcingAnActionWithoutToolCallDoesNotEndTheTurn() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(
            listOf(
                "Saya akan mencari file-file di proyek.",
                tu("LS", """{"path":"."}"""),
                "Selesai: proyek kosong.",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("audit") { events.add(it) }
        assertTrue(res.success)
        assertEquals("Selesai: proyek kosong.", res.summary)
        assertEquals(3, res.steps)
        assertEquals(1, events.count { it is AgentEvent.ToolEnd })
        assertTrue(events.any { it is AgentEvent.Thought && it.text.startsWith("Saya akan mencari") })
    }

    @Test
    fun repeatedAnnouncementsEventuallyCountAsTheAnswer() = runTest {
        val dir = newDir()
        val llm = ScriptedLlm(List(3) { "Saya akan mulai sekarang." })
        val res = agent(llm, dir).execute("x") { }
        assertTrue(res.success)
        assertEquals(3, llm.calls)
        assertEquals("Saya akan mulai sekarang.", res.summary)
    }

    @Test
    fun intentHeuristicIgnoresQuestionsAndLongAnswers() {
        assertTrue(looksLikeUnfulfilledIntent("Saya akan membaca file itu."))
        assertTrue(looksLikeUnfulfilledIntent("Berikut langkahnya:"))
        assertFalse(looksLikeUnfulfilledIntent("Saya akan lanjut? Mau yang mana?"))
        assertTrue(looksLikeUnfulfilledIntent("Halo BOS, saya akan membuat struktur proyek Android dasar untuk aplikasi kalkulator."))
        assertTrue(looksLikeUnfulfilledIntent("Baik, BOS. Saya akan memeriksa isi direktori."))
        assertFalse(looksLikeUnfulfilledIntent("Sudah selesai, file dibuat."))
        assertFalse(looksLikeUnfulfilledIntent("Saya akan " + "x".repeat(700)))
    }

    @Test
    fun projectMemoryFileIsLoadedIntoFirstMessage() = runTest {
        val dir = newDir()
        File(dir, "CLAUDE.md").writeText("Selalu pakai tab.")
        val llm = ScriptedLlm(listOf("ok"))
        agent(llm, dir).execute("hai") { }
        val first = llm.seen[0][0].text
        assertTrue(first.contains("Selalu pakai tab."))
        assertTrue(first.endsWith("hai"))
    }

    @Test
    fun intentHeuristicSkipsClosingsAndRequestsForInput() {
        assertFalse(looksLikeUnfulfilledIntent("Selesai. Saya akan menunggu instruksi berikutnya."))
        assertFalse(looksLikeUnfulfilledIntent("Saya perlu tahu nama file yang dimaksud."))
        assertFalse(looksLikeUnfulfilledIntent("Done. I'll leave the rest to you."))
        assertFalse(looksLikeUnfulfilledIntent("Tolong kirim nama file-nya."))
        assertTrue(looksLikeUnfulfilledIntent("Saya perlu membaca file itu."))
    }

    @Test
    fun truncatedToolCallAsksToSplitInsteadOfResending() = runTest {
        val dir = newDir()
        val cut = "<tool_use>{\"name\":\"Write\",\"input\":{\"file_path\":\"Big.kt\",\"content\":\"package x\\nclass Big {"
        val llm = ScriptedLlm(listOf(cut, "ok"))
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir).execute("buat file besar") { events.add(it) }
        assertTrue(res.success)
        assertTrue(events.any { it is AgentEvent.Notice && it.text.contains("terpotong") })
        assertTrue(llm.seen[1].last().text.contains("cut off by the output limit"))
    }

    @Test
    fun repeatedTruncationAbortsWithAHelpfulReason() = runTest {
        val dir = newDir()
        val cut = "<tool_use>{\"name\":\"Write\",\"input\":{\"file_path\":\"Big.kt\",\"content\":\"package x\\nclass Big {"
        val llm = ScriptedLlm(List(4) { cut })
        val res = agent(llm, dir).execute("buat file besar") { }
        assertFalse(res.success)
        assertTrue(res.summary, res.summary.contains("terpotong"))
    }

    @Test
    fun duplicateGuardDoesNotBlockWhenTheOldResultWasAlreadyCompacted() = runTest {
        val dir = newDir()
        for (n in listOf("f", "g", "h")) File(dir, "$n.txt").writeText((1..100).joinToString("\n") { "baris $it " + "x".repeat(50) })
        val llm = ScriptedLlm(
            listOf(
                tu("Read", """{"file_path":"f.txt"}"""),
                tu("Read", """{"file_path":"g.txt"}"""),
                tu("Read", """{"file_path":"h.txt"}"""),
                tu("Read", """{"file_path":"f.txt"}"""),   // hasil f.txt sudah dipadatkan: harus boleh dibaca ulang
                "selesai",
            )
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent(llm, dir, AgentConfig(contextBudgetChars = 3_000)).execute("baca") { events.add(it) }
        assertTrue(res.success)
        assertEquals(4, events.count { it is AgentEvent.ToolEnd })
        assertFalse(events.any { it is AgentEvent.Notice && it.text.contains("duplikat") })
    }

    @Test
    fun githubBuildAsksBeforeRunningANewWorkflowEvenInAcceptEditsMode() = runTest {
        val dir = newDir()
        File(dir, ".github/workflows").mkdirs()
        File(dir, ".github/workflows/build.yml").writeText("on: push\njobs: {}\n")
        val asked = mutableListOf<String>()
        val build = object : BuildService {
            override suspend fun build(root: File, timeoutSec: Int): BuildOutcome = BuildOutcome(true, "Semua run sukses.")
        }
        val llm = ScriptedLlm(listOf(tu("GithubBuild", "{}"), tu("GithubBuild", """{"timeout_s":61}"""), "selesai"))
        val ag = Agent(
            llm, ToolRegistry.default(true, withPlan = false), ProjectSandbox(dir),
            Approver { tool, summary -> asked.add("$tool|$summary"); true }, { "" }, build, AgentConfig()
        )
        val res = ag.execute("bangun") { }
        assertTrue(res.success)
        assertEquals(1, asked.size)                                  // build kedua memakai workflow yang sama: tidak bertanya lagi
        assertTrue(asked[0], asked[0].startsWith("GithubBuild|"))
        assertTrue(asked[0], asked[0].contains("on: push"))          // isi workflow ditampilkan agar bisa dinilai pengguna
    }

    @Test
    fun journalRecordsWhatTheAgentChanged() = runTest {
        val dir = newDir()
        File(dir, "a.txt").writeText("x\n")
        val journal = CheckpointStore(File(newDir(), "ck")).begin("p", dir, "uji")
        val llm = ScriptedLlm(
            listOf(
                tu("Read", """{"file_path":"a.txt"}"""),
                tu("Edit", """{"file_path":"a.txt","old_string":"x","new_string":"y"}"""),
                tu("Write", """{"file_path":"n.txt","content":"baru\n"}"""),
                "selesai",
            )
        )
        val ag = Agent(llm, ToolRegistry.default(false, withPlan = false), ProjectSandbox(dir), Approver { _, _ -> true }, { "" }, null, AgentConfig(), journal)
        val res = ag.execute("ubah") { }
        assertTrue(res.success)
        assertEquals(setOf("a.txt", "n.txt"), journal.finish().map { it.path }.toSet())
    }
}
