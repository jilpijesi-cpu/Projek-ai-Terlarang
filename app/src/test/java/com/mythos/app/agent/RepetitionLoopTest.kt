package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RepetitionLoopTest {
    private val sentences = listOf(
        "Saya cari harga Claude API dan langganan dari sumber resmi.",
        "Saya mulai dengan pencarian web untuk harga resmi Claude API dan langganannya.",
        "Saya cari harga resmi API dan paket Claude termurah, lalu saya bandingkan sumbernya.",
        "Saya mulai pencarian web dengan tiga istilah berbeda.",
        "Saya telusuri tiga pencarian terpisah untuk memastikan harga API dan langganan.",
        "Saya cari harga API, tarif model, dan langganan Claude.",
        "Saya mulai dengan tiga pencarian web berbeda untuk membandingkan biaya Claude.",
        "Saya telusuri sumber resmi untuk harga API dan langganan Claude.",
    )
    private val stuck = (0 until 12).joinToString("\n\n") { sentences[it % sentences.size] }

    private val normal = listOf(
        "Harga Claude Sonnet berbeda tergantung penggunaan dan jenis paket yang Anda pilih untuk aplikasi.",
        "Untuk pelajar tersedia paket hemat dengan batas pemakaian lebih rendah dibanding paket reguler biasa.",
        "Pertimbangkan juga biaya token keluaran yang umumnya lebih mahal daripada token masukan model tersebut.",
        "Terakhir, cek halaman resmi sebelum berlangganan karena tarif dapat berubah sewaktu-waktu tanpa pemberitahuan.",
        "Jika memakai API, atur anggaran bulanan supaya tagihan tidak membengkak di luar perkiraan awal Anda.",
        "Cache prompt dapat memangkas biaya untuk permintaan berulang dengan konteks yang sama panjang.",
        "Batch API menawarkan diskon untuk tugas yang tidak perlu dijawab seketika oleh model bahasa.",
        "Bandingkan juga dengan penyedia lain bila kebutuhan Anda hanya tugas sederhana dan berulang.",
    ).joinToString("\n\n")

    @Test
    fun detectsStuckRepeatedIntentSentences() {
        assertTrue(isRepetitionLoop(stuck))
    }

    @Test
    fun leavesNormalAnswersAndShortTextAlone() {
        assertFalse(isRepetitionLoop(normal))
        assertFalse(isRepetitionLoop(sentences.take(3).joinToString("\n\n")))
        assertFalse(isRepetitionLoop(""))
    }

    @Test
    fun neverFlagsAReplyThatAlreadyContainsAToolCall() {
        val withTool = stuck + "\n\n" + Protocol.OPEN + "{\"name\":\"WebSearch\",\"input\":{\"query\":\"harga claude\"}}" + Protocol.CLOSE
        assertFalse(isRepetitionLoop(withTool))
    }

    private class ScriptedLlm(private val replies: List<String>) : LlmClient {
        var calls = 0
        val seen = mutableListOf<List<Msg>>()
        override suspend fun complete(system: String, messages: List<Msg>): String {
            seen.add(messages.toList())
            return replies[calls++]
        }
    }

    @Test
    fun agentDiscardsAStuckReplyAndAsksAgainInsteadOfEndingWithJunk() = runTest {
        val dir = Files.createTempDirectory("mythos-rep").toFile()
        val llm = ScriptedLlm(listOf(stuck, "Ini jawaban akhirnya."))
        val agent = Agent(
            llm, ToolRegistry.default(false, withPlan = false), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, null, AgentConfig(),
        )
        val events = mutableListOf<AgentEvent>()
        val res = agent.execute("berapa harga claude?") { events.add(it) }
        assertTrue(res.success)
        assertEquals("Ini jawaban akhirnya.", res.summary)
        assertEquals(2, llm.calls)
        assertTrue(events.any { it is AgentEvent.Notice && it.text.contains("mengulang kalimat yang sama") })
        // balasan macet tidak boleh masuk ke riwayat yang dikirim ulang ke model
        assertFalse(llm.seen[1].any { it.text.contains(sentences[0]) })
    }

    @Test
    fun agentGivesUpWithAClearMessageWhenTheModelKeepsLooping() = runTest {
        val dir = Files.createTempDirectory("mythos-rep").toFile()
        val llm = ScriptedLlm(List(AgentConfig().maxFormatRepairs + 1) { stuck })
        val agent = Agent(
            llm, ToolRegistry.default(false, withPlan = false), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, null, AgentConfig(),
        )
        val res = agent.execute("berapa harga claude?") { }
        assertFalse(res.success)
        assertTrue(res.summary.contains("terjebak"))
    }
}
