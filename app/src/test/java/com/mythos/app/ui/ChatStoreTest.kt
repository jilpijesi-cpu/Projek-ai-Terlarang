package com.mythos.app.ui

import com.mythos.app.agent.ChangeKind
import com.mythos.app.agent.FileChange
import com.mythos.app.agent.PlanStep
import com.mythos.app.agent.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChatStoreTest {
    private fun store() = ChatStore(Files.createTempDirectory("mythos-chats").toFile())

    private fun sample(id: String, at: Long): SavedChat {
        val items = listOf(
            UserItem(0, "buat aplikasi catatan"),
            ThoughtItem(1, "mulai dari rencana"),
            PlanItem(2, listOf(PlanStep("rancang", StepStatus.DONE), PlanStep("kode", StepStatus.DOING))),
            ToolItem(3, 2, "write_file", "write_file a.kt", ToolStatus.OK, "ok", 12),
            ToolItem(4, 3, "github_build", "github_build", ToolStatus.RUNNING),
            NoticeItem(5, "info"),
            FinalItem(6, "selesai", true),
            ErrorItem(7, "galat"),
        )
        return SavedChat(ChatMeta(id, "buat aplikasi catatan", "/p/$id", at), items, listOf("tugas" to "hasil"))
    }

    @Test
    fun roundTripKeepsContentAndMarksInterruptedToolsAsError() {
        val s = store()
        s.save(sample("proyek-1", 100))
        val loaded = s.load("proyek-1")!!
        assertEquals(8, loaded.items.size)
        assertEquals(listOf("tugas" to "hasil"), loaded.history)
        assertEquals("/p/proyek-1", loaded.meta.projectPath)
        val plan = loaded.items[2] as PlanItem
        assertEquals(StepStatus.DOING, plan.steps[1].status)
        assertEquals(ToolStatus.OK, (loaded.items[3] as ToolItem).status)
        assertEquals(ToolStatus.ERROR, (loaded.items[4] as ToolItem).status)   // tadinya RUNNING
        assertTrue((loaded.items[6] as FinalItem).verified)
        assertEquals((0L..7L).toList(), loaded.items.map { it.id })
    }

    @Test
    fun listIsSortedNewestFirstAndDeleteRemoves() {
        val s = store()
        s.save(sample("a", 100))
        s.save(sample("b", 300))
        s.save(sample("c", 200))
        assertEquals(listOf("b", "c", "a"), s.list().map { it.id })
        s.delete("c")
        assertEquals(listOf("b", "a"), s.list().map { it.id })
        assertNull(s.load("c"))
    }

    @Test
    fun changesItemRoundTrips() {
        val s = store()
        val files = listOf(FileChange("a.kt", ChangeKind.MODIFIED, 3, 1), FileChange("ikon.png", ChangeKind.ADDED, 0, 0, binary = true))
        val items = listOf(UserItem(0, "ubah"), ChangesItem(1, "0000000000001", files, undone = true))
        s.save(SavedChat(ChatMeta("p1", "ubah", "/p/p1", 1L), items, emptyList()))
        val c = s.load("p1")!!.items[1] as ChangesItem
        assertEquals("0000000000001", c.checkpointId)
        assertTrue(c.undone)
        assertEquals(files, c.files)
    }

    @Test
    fun emailDraftSurvivesSaveAndLoad() {
        val s = store()
        val items = listOf(
            UserItem(0, "kirim email"),
            EmailDraftItem(1, listOf("a@x.id"), listOf("b@y.id"), "Tiket 1", "Halo\nIsi", listOf("uploads/Data_Verifikasi.pdf")),
        )
        s.save(SavedChat(ChatMeta("e1", "kirim email", "/p/e1", 1L), items, emptyList()))
        val d = s.load("e1")!!.items[1] as EmailDraftItem
        assertEquals(listOf("a@x.id"), d.to)
        assertEquals(listOf("b@y.id"), d.cc)
        assertEquals("Halo\nIsi", d.body)
        assertEquals(listOf("uploads/Data_Verifikasi.pdf"), d.attachments)
        assertEquals("Data Verifikasi", prettyFileName(d.attachments[0]))
    }
}
