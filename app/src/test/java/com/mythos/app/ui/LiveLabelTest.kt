package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLabelTest {
    private fun tool(id: Long, summary: String, status: ToolStatus) = ToolItem(id, 1, "Write", summary, status)

    @Test
    fun runningToolShowsItsOwnTitleNotTheNarration() {
        val blocks = toBlocks(
            listOf(
                UserItem(1, "buat kalkulator"),
                ThoughtItem(2, "Saya menulis layar kalkulator\ndengan **tombol angka** dan `operasi` dasar."),
                tool(3, "Write {\"file_path\":\"A.kt\"}", ToolStatus.RUNNING),
            )
        )
        assertEquals("Menulis A.kt", liveLabel(blocks, null))
        // sementara model masih menulis alatnya, narasi (satu baris) yang tampil
        assertEquals(
            "Saya menulis layar kalkulator dengan tombol angka dan operasi dasar.",
            liveLabel(blocks.dropLast(1), LiveActivity("Write", "A.kt"))
        )
    }

    @Test
    fun streamingToolUsesNarrationEvenWhileItIsStillLive() {
        val blocks = toBlocks(listOf(UserItem(1, "q"), ThoughtItem(2, "Siap, saya mulai.", live = true)))
        assertEquals("Siap, saya mulai.", liveLabel(blocks, LiveActivity("Write", "src/B.kt")))
    }

    @Test
    fun withoutNarrationFallsBackToToolTitle() {
        val blocks = toBlocks(listOf(UserItem(1, "q"), tool(2, "Write {\"file_path\":\"src/A.kt\"}", ToolStatus.RUNNING)))
        assertEquals("Menulis A.kt", liveLabel(blocks, null))
    }

    @Test
    fun streamingNewToolAfterFinishedStepDoesNotReuseOldNarration() {
        val blocks = toBlocks(
            listOf(
                UserItem(1, "q"),
                ThoughtItem(2, "Narasi langkah pertama."),
                ToolItem(3, 1, "Read", "Read {\"file_path\":\"A.kt\"}", ToolStatus.OK),
            )
        )
        assertEquals("Menulis B.kt", liveLabel(blocks, LiveActivity("Write", "src/B.kt")))
    }

    @Test
    fun labelFollowsEachToolInTheGroup() {
        val blocks = toBlocks(
            listOf(
                UserItem(1, "q"),
                ThoughtItem(2, "Langkah pertama."),
                ToolItem(3, 1, "Read", "Read {\"file_path\":\"A.kt\"}", ToolStatus.OK),
                ThoughtItem(4, "Langkah kedua."),
                ToolItem(5, 2, "Read", "Read {\"file_path\":\"B.kt\"}", ToolStatus.RUNNING),
            )
        )
        assertEquals("Membaca B.kt", liveLabel(blocks, null))
    }

    @Test
    fun betweenToolsWaitingForModelShowsThinking() {
        val blocks = toBlocks(
            listOf(
                UserItem(1, "q"),
                ToolItem(2, 1, "Read", "Read {\"file_path\":\"A.kt\"}", ToolStatus.OK),
            )
        )
        assertEquals(THINKING_LABEL, liveLabel(blocks, null))
    }

    @Test
    fun longNarrationIsCappedAndLeftToTheViewToEllipsize() {
        assertEquals(300, oneLineNarration("a ".repeat(400)).length)
    }

    @Test
    fun finishedRowKeepsTheLastLiveLabelText() {
        val items = listOf<ChatItem>(ToolItem(1, 1, "Read", "Read {\"file_path\":\"A.kt\"}", ToolStatus.OK))
        assertEquals("Memeriksa jalur streaming", finishedLabel("Memeriksa jalur streaming", items))
        // tanpa label hidup (riwayat) atau hanya "Proses berpikir" -> judul kelompok
        assertEquals("Membaca A.kt", finishedLabel(null, items))
        assertEquals("Membaca A.kt", finishedLabel(THINKING_LABEL, items))
    }
}

class StatusLabelTest {
    @Test
    fun streamingToolShowsModelStatusFirst() {
        val blocks = toBlocks(listOf(UserItem(1, "q")))
        assertEquals("Memeriksa alur streaming", liveLabel(blocks, LiveActivity("Read", "A.kt", "Memeriksa alur streaming")))
        assertEquals("Membaca A.kt", liveLabel(blocks, LiveActivity("Read", "A.kt", null)))
    }

    @Test
    fun runningToolShowsItsNoteOrTitle() {
        val noted = toBlocks(listOf(UserItem(1, "q"), ToolItem(2, 1, "Read", "Read {\"file_path\":\"A.kt\"}", ToolStatus.RUNNING, note = "Memeriksa alur")))
        assertEquals("Memeriksa alur", liveLabel(noted, null))
        val plain = toBlocks(listOf(UserItem(1, "q"), ToolItem(2, 1, "Read", "Read {\"file_path\":\"A.kt\"}", ToolStatus.RUNNING)))
        assertEquals("Membaca A.kt", liveLabel(plain, null))
    }
}
