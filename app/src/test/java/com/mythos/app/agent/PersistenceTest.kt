package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class SeqLlm(private val replies: List<String>) : LlmClient {
    var calls = 0
    override suspend fun complete(system: String, messages: List<Msg>): String = replies[minOf(calls++, replies.size - 1)]
}

private fun call(name: String, input: String) = "<tool_use>{\"name\":\"$name\",\"input\":$input}</tool_use>"

/** Agen tidak boleh berhenti selama build masih gagal; pengguna tidak perlu mengulang perintah. */
class PersistenceTest {
    @Test
    fun failedBuildThenGiveUpTextIsRejected() {
        assertTrue(shouldKeepGoingFor(buildFailed = true, persist = 0, text = "Build gagal, saya berhenti di sini."))
        assertTrue(shouldKeepGoingFor(buildFailed = true, persist = 7, text = "Saya akan periksa."))
        assertFalse(shouldKeepGoingFor(buildFailed = true, persist = 8, text = "Masih gagal."))   // batas pengaman
    }

    @Test
    fun questionToUserMayEndTheTurn() {
        assertFalse(shouldKeepGoingFor(buildFailed = true, persist = 0, text = "Token GitHub tidak valid. Bisa kirim token baru?"))
    }

    @Test
    fun selfReportedFailureIsRejectedEvenWithoutBuild() {
        assertTrue(shouldKeepGoingFor(buildFailed = false, persist = 0, text = "Perbaikan belum berhasil."))
        assertFalse(shouldKeepGoingFor(buildFailed = false, persist = 3, text = "Perbaikan belum berhasil."))
        assertFalse(shouldKeepGoingFor(buildFailed = false, persist = 0, text = "Selesai. APK berhasil dibuat."))
    }

    @Test
    fun agentKeepsFixingUntilBuildIsGreen() = runTest {
        val dir = Files.createTempDirectory("mythos-persist").toFile()
        File(dir, ".github/workflows").mkdirs()
        File(dir, ".github/workflows/build.yml").writeText("on: push\njobs: {}\n")
        var builds = 0
        val build = object : BuildService {
            override suspend fun build(root: File, timeoutSec: Int): BuildOutcome =
                if (++builds < 3) BuildOutcome(false, "error: unresolved reference") else BuildOutcome(true, "Semua run sukses.")
        }
        val llm = SeqLlm(
            listOf(
                call("GithubBuild", "{}"),
                "Build gagal, saya berhenti dan menunggu perintah Anda.",      // ditolak: tujuan belum tercapai
                call("GithubBuild", "{}"),
                "Masih gagal.",                                                  // ditolak lagi
                call("GithubBuild", "{}"),
                "Selesai, build hijau.",
            )
        )
        val ag = Agent(
            llm, ToolRegistry.default(true, withPlan = false), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, build, AgentConfig()
        )
        val events = mutableListOf<AgentEvent>()
        val res = ag.execute("bangun apk") { events.add(it) }
        assertTrue(res.success)
        assertEquals(3, builds)
        // balasan "menyerah" yang ditolak tidak boleh muncul di layar sebagai narasi (bug sekilas)
        assertFalse(events.any { it is AgentEvent.Thought && it.text.contains("saya berhenti") })
        assertEquals(1, events.count { it is AgentEvent.Notice && it.text.contains("tidak perlu mengulang") })
        assertEquals("Selesai, build hijau.", res.summary)
    }

    @Test
    fun stepLimitIsExtendedWhileBuildIsFailing() = runTest {
        val dir = Files.createTempDirectory("mythos-persist2").toFile()
        File(dir, ".github/workflows").mkdirs()
        File(dir, ".github/workflows/build.yml").writeText("on: push\njobs: {}\n")
        var builds = 0
        val build = object : BuildService {
            override suspend fun build(root: File, timeoutSec: Int): BuildOutcome =
                if (++builds < 6) BuildOutcome(false, "error") else BuildOutcome(true, "ok")
        }
        val replies = List(5) { call("GithubBuild", "{\"timeout_s\":${60 + it}}") } + call("GithubBuild", "{\"timeout_s\":99}") + "Selesai."
        val ag = Agent(
            SeqLlm(replies), ToolRegistry.default(true, withPlan = false), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, build, AgentConfig(maxSteps = 4)
        )
        val events = mutableListOf<AgentEvent>()
        val res = ag.execute("bangun apk") { events.add(it) }
        assertTrue(res.success)
        assertTrue(events.any { it is AgentEvent.Notice && it.text.contains("diperpanjang") })
    }
}
