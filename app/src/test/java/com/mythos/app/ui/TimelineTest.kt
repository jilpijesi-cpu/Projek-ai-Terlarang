package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Linimasa streaming: reducer peristiwa -> item, pengelompokan blok, label baris hidup, dan tanda kemajuan. */
class TimelineTest {
    private var next = 100L
    private fun reducer(items: MutableList<ChatItem>) = TimelineReducer(items) { next++ }
    private fun tool(id: Long, name: String, summary: String, status: ToolStatus = ToolStatus.OK) = ToolItem(id, 1, name, summary, status)

    // ------------------------------------------------------------ reducer

    @Test
    fun streamingCreatesOneLiveItemAndUpdatesItInPlace() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        val r = reducer(items)
        r.streaming("Saya", null, null)
        r.streaming("Saya lihat dulu", null, null)
        assertEquals(2, items.size)
        val t = items[1] as ThoughtItem
        assertEquals("Saya lihat dulu", t.text)
        assertTrue(t.live)
        assertEquals(t.id, r.liveId)
    }

    @Test
    fun blankStreamingRemovesTheLiveItemWhenAnAttemptIsRestarted() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        val r = reducer(items)
        r.streaming("setengah", null, null)
        r.streaming("", null, null)
        assertEquals(1, items.size)
        assertNull(r.liveId)
        r.streaming("", null, null)   // tanpa item hidup: tidak ada yang berubah
        assertEquals(1, items.size)
    }

    @Test
    fun thoughtFinalizesTheLiveItemInPlace() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        val r = reducer(items)
        r.streaming("Saya lihat dulu", null, null)
        val id = items[1].id
        r.thought("Saya lihat dulu.")
        assertEquals(2, items.size)
        val t = items[1] as ThoughtItem
        assertEquals(id, t.id)
        assertEquals("Saya lihat dulu.", t.text)
        assertFalse(t.live)
        assertNull(r.liveId)
    }

    @Test
    fun thoughtWithoutLiveItemAppendsANewOne() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        reducer(items).thought("Langsung")
        assertEquals(2, items.size)
        assertFalse((items[1] as ThoughtItem).live)
    }

    @Test
    fun finalAnswerReplacesTheLiveItemKeepingItsId() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        val r = reducer(items)
        r.streaming("Halo, ada yang", null, null)
        val id = items[1].id
        r.finalAnswer("Halo, ada yang bisa dibantu?", verified = true, showStatus = false)
        assertEquals(2, items.size)
        val f = items[1] as FinalItem
        assertEquals(id, f.id)
        assertEquals("Halo, ada yang bisa dibantu?", f.text)
        assertNull(r.liveId)
    }

    @Test
    fun finalAnswerWithoutLiveItemAppends() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        reducer(items).finalAnswer("Selesai.", verified = false, showStatus = true)
        assertTrue(items[1] is FinalItem)
    }

    @Test
    fun settleKeepsNonBlankTextAsPlainNarrationAndClearsActivity() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        val r = reducer(items)
        r.streaming("Menulis file", "Write", "A.kt")
        assertEquals(LiveActivity("Write", "A.kt"), r.activity)
        r.settle()
        assertNull(r.activity)
        assertNull(r.liveId)
        assertFalse((items[1] as ThoughtItem).live)
    }

    @Test
    fun activityFollowsStreamingToolAndIsClearedByThought() {
        val items = ArrayList<ChatItem>(listOf(UserItem(1, "hai")))
        val r = reducer(items)
        r.streaming("", "Read", null)
        assertEquals(LiveActivity("Read", null), r.activity)
        assertEquals(1, items.size)   // tanpa narasi: belum ada item
        r.streaming("", "Read", "a.kt")
        assertEquals(LiveActivity("Read", "a.kt"), r.activity)
        r.thought("")                 // balasan selesai: aktivitas dibersihkan
        assertNull(r.activity)
    }

    // ------------------------------------------------------------ blok

    private val sample: List<ChatItem> = listOf(
        UserItem(1, "q"),
        ThoughtItem(2, "Saya lihat dulu."),
        tool(3, "Read", "{\"file_path\":\"a.kt\"}"),
        tool(4, "Grep", "{\"pattern\":\"x\"}"),
        ThoughtItem(5, "Sekarang menulis."),
        tool(6, "Write", "Write b.kt (10 karakter)"),
        FinalItem(7, "Selesai.", true),
    )

    @Test
    fun toBlocksGroupsLikeTheRecording() {
        val blocks = toBlocks(sample)
        assertEquals(
            listOf("UserBlock", "TextBlock", "StepsBlock", "TextBlock", "StepsBlock", "TextBlock"),
            blocks.map { it::class.simpleName }
        )
        assertEquals(2, (blocks[2] as StepsBlock).items.size)
        assertEquals(1, (blocks[4] as StepsBlock).items.size)
    }

    @Test
    fun blockKeysAreStableAndDistinct() {
        val keys = toBlocks(sample).map { blockKey(it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun liveTextBecomingFinalKeepsTheSameBlockKey() {
        val live = listOf(UserItem(1, "q"), ThoughtItem(2, "Halo", live = true))
        val done = listOf(UserItem(1, "q"), FinalItem(2, "Halo!", true, showStatus = false))
        assertEquals(blockKey(toBlocks(live)[1]), blockKey(toBlocks(done)[1]))
    }

    @Test
    fun needsExtraLiveCases() {
        fun blocks(vararg i: ChatItem) = toBlocks(i.toList())
        assertFalse(needsExtraLive(blocks(UserItem(1, "q")), running = false, activity = null))
        assertTrue(needsExtraLive(blocks(UserItem(1, "q")), running = true, activity = null))
        // kelompok langkah terakhir sudah menjadi baris hidup
        assertFalse(needsExtraLive(blocks(UserItem(1, "q"), tool(2, "Read", "a")), running = true, activity = null))
        // teks sedang mengalir: tanpa baris di bawahnya
        assertFalse(needsExtraLive(blocks(UserItem(1, "q"), ThoughtItem(2, "Sa", live = true)), running = true, activity = null))
        // ... kecuali model sudah menulis JSON alat setelah narasinya
        assertTrue(needsExtraLive(blocks(UserItem(1, "q"), ThoughtItem(2, "Sa", live = true)), running = true, activity = LiveActivity("Write", null)))
        // narasi selesai, alat belum mulai
        assertTrue(needsExtraLive(blocks(UserItem(1, "q"), ThoughtItem(2, "Sa.")), running = true, activity = null))
        // efek mengetik narasi belum selesai -> baris aktivitas belum boleh muncul
        assertFalse(needsExtraLive(blocks(UserItem(1, "q"), ThoughtItem(2, "Sa.")), running = true, activity = null, textSettled = false))
        assertFalse(needsExtraLive(blocks(UserItem(1, "q"), ThoughtItem(2, "Sa", live = true)), running = true, activity = LiveActivity("Write", null), textSettled = false))
        // jawaban akhir: giliran berakhir
        assertFalse(needsExtraLive(blocks(UserItem(1, "q"), FinalItem(2, "Ok", true)), running = true, activity = null))
        assertFalse(needsExtraLive(blocks(UserItem(1, "q"), ErrorItem(2, "gagal")), running = true, activity = null))
    }

    // ------------------------------------------------------------ label & judul

    @Test
    fun liveLabelPrefersPreparingToolThenRunningToolThenThinking() {
        val running = toBlocks(listOf(UserItem(1, "q"), tool(2, "Read", "{\"file_path\":\"src/A.kt\"}", ToolStatus.RUNNING)))
        assertEquals("Membaca A.kt", liveLabel(running, null))
        assertEquals("Menulis B.kt", liveLabel(running, LiveActivity("Write", "src/B.kt")))
        assertEquals("Menulis file", liveLabel(running, LiveActivity("Write", null)))
        val done = toBlocks(listOf(UserItem(1, "q"), tool(2, "Read", "{\"file_path\":\"src/A.kt\"}", ToolStatus.OK)))
        assertEquals(THINKING_LABEL, liveLabel(done, null))
        assertEquals(THINKING_LABEL, liveLabel(emptyList(), null))
    }

    @Test
    fun toolAndPrepTitles() {
        assertEquals("Membaca A.kt", toolTitle("Read", "{\"file_path\":\"src/A.kt\"}"))
        assertEquals("Menulis b.kt", toolTitle("Write", "Write b.kt (10 karakter)"))
        assertEquals("Mencari “foo”", toolTitle("Grep", "{\"pattern\":\"foo\"}"))
        assertEquals("Mencari “foo”", prepTitle("Grep", "foo"))
        assertEquals("Mengedit C.kt", prepTitle("Edit", "a/b/C.kt"))
        assertEquals("Build di GitHub Actions", prepTitle("GithubBuild", null))
        assertEquals("AlatBaru", prepTitle("AlatBaru", null))
    }

    @Test
    fun groupTitleUsesTheToolForOneStepAndASummaryForMany() {
        val one = listOf(tool(1, "Read", "{\"file_path\":\"src/A.kt\"}"))
        assertEquals("Membaca A.kt", groupTitle(one))
        val many = listOf(tool(1, "Read", "a"), tool(2, "Read", "b"), tool(3, "Edit", "Edit c.kt"))
        assertEquals("Membaca 2 file, mengubah 1 file", groupTitle(many))
        assertEquals(THINKING_LABEL, groupTitle(listOf(NoticeItem(1, "info"))))
    }

    @Test
    fun buildGroupsGetTheTerminalIcon() {
        assertEquals(GroupIcon.TERMINAL, groupIcon(listOf(tool(1, "GithubBuild", "GithubBuild (push + GitHub Actions)"))))
        assertEquals(GroupIcon.CLOCK, groupIcon(listOf(tool(1, "Read", "a"))))
        assertEquals(GroupIcon.CLOCK, groupIcon(emptyList()))
    }

    // ------------------------------------------------------------ "Masih diproses…"

    @Test
    fun progressKeyChangesOnlyWhenSomethingVisibleChanges() {
        val a = listOf<ChatItem>(UserItem(1, "q"), ThoughtItem(2, "Sa", live = true))
        val same = listOf<ChatItem>(UserItem(1, "q"), ThoughtItem(2, "Sa", live = true))
        assertEquals(progressKey(a, null), progressKey(same, null))
        val grown = listOf<ChatItem>(UserItem(1, "q"), ThoughtItem(2, "Saya", live = true))
        assertNotEquals(progressKey(a, null), progressKey(grown, null))
        assertNotEquals(progressKey(a, null), progressKey(a, LiveActivity("Write", null)))
        val running = listOf<ChatItem>(UserItem(1, "q"), tool(2, "Read", "a", ToolStatus.RUNNING))
        val ended = listOf<ChatItem>(UserItem(1, "q"), tool(2, "Read", "a", ToolStatus.OK))
        assertNotEquals(progressKey(running, null), progressKey(ended, null))
    }

    @Test
    fun stallThresholdMatchesTheRecording() {
        assertEquals(10_000L, STALL_MS)
        assertEquals("Masih diproses…", STALL_TEXT)
    }
}
