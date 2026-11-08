package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CaseToolsTest {
    private class FakeDevice : DeviceActions {
        var email: List<Any?>? = null
        var alarm: Triple<Int, Int, String>? = null
        var event: List<Any?>? = null
        val notices = ArrayList<Triple<String, String, Long>>()
        override fun scheduleNotice(id: String, title: String, text: String, atMillis: Long): String? { notices.add(Triple(id, title, atMillis)); return null }
        override fun composeEmail(to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<File>): String? {
            email = listOf(to, cc, subject, body, attachments); return null
        }
        override fun setAlarm(hour: Int, minute: Int, label: String): String? { alarm = Triple(hour, minute, label); return null }
        override fun addCalendarEvent(title: String, startMillis: Long, endMillis: Long, notes: String): String? {
            event = listOf(title, startMillis, endMillis, notes); return null
        }
    }

    private fun env(dev: DeviceActions?): Pair<ToolEnv, File> {
        val dir = Files.createTempDirectory("mythos-case").toFile()
        return ToolEnv(ProjectSandbox(dir), SessionState(), { "" }, null, null, dev) to dir
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable? = try { block(); null } catch (e: Throwable) { e }

    @Test
    fun stringListAcceptsArrayAndCommaText() {
        val a = JSONObject().put("to", JSONArray().put("a@x.id").put(" b@y.id "))
        assertEquals(listOf("a@x.id", "b@y.id"), a.stringList("to"))
        val b = JSONObject().put("to", "a@x.id; b@y.id, c@z.id")
        assertEquals(listOf("a@x.id", "b@y.id", "c@z.id"), b.stringList("to"))
        assertTrue(JSONObject().stringList("to").isEmpty())
    }

    @Test
    fun composeEmailOnlyShowsCardNeverOpensAppNorClaimsSent() = runTest {
        val dev = FakeDevice()
        val (e, dir) = env(dev)
        File(dir, "uploads").mkdirs()
        File(dir, "uploads/a.pdf").writeText("pdf")
        val args = JSONObject().put("to", JSONArray().put("bicara@bi.go.id")).put("cc", "lapor@flip.id")
            .put("subject", "Pengaduan").put("body", "Isi").put("attachments", JSONArray().put("uploads/a.pdf"))
        val r = ComposeEmail().run(args, e)
        assertTrue(r.text, r.ok)
        assertTrue(r.text.contains("BELUM TERKIRIM"))
        assertNull("aplikasi email tidak boleh dibuka oleh alat", dev.email)
        val ev = emailDraftEvent(args)
        assertEquals(listOf("bicara@bi.go.id"), ev.to)
        assertEquals(listOf("lapor@flip.id"), ev.cc)
        assertEquals("Pengaduan", ev.subject)
        assertEquals(listOf("uploads/a.pdf"), ev.attachments)
    }

    @Test
    fun composeEmailRejectsBadInput() = runTest {
        val (e, dir) = env(FakeDevice())
        val base = { JSONObject().put("to", "bicara@bi.go.id").put("subject", "S").put("body", "B") }
        assertTrue(failure { ComposeEmail().run(JSONObject().put("subject", "S").put("body", "B"), e) } is ArgError)
        assertTrue(failure { ComposeEmail().run(base().put("to", "bukan-email"), e) } is ArgError)
        assertTrue(failure { ComposeEmail().run(base().put("subject", "  "), e) } is ArgError)
        val missing = ComposeEmail().run(base().put("attachments", "uploads/tidak-ada.pdf"), e)
        assertFalse(missing.ok)
        assertTrue(missing.text.contains("tidak ditemukan"))
        assertTrue(File(dir, "uploads/tidak-ada.pdf").exists().not())
    }

    @Test
    fun composeEmailBlocksPathEscape() = runTest {
        val (e, _) = env(FakeDevice())
        val args = JSONObject().put("to", "a@x.id").put("subject", "S").put("body", "B").put("attachments", "../rahasia.txt")
        assertTrue(failure { ComposeEmail().run(args, e) } is SecurityException)
    }

    @Test
    fun composeEmailWithoutDeviceExplainsFallback() = runTest {
        val (e, _) = env(null)
        val r = ComposeEmail().run(JSONObject().put("to", "a@x.id").put("subject", "S").put("body", "B"), e)
        assertFalse(r.ok)
        assertTrue(r.text.contains("salin"))
    }

    @Test
    fun composeEmailCardIsTheReviewAndDescribeShowsRecipientsAndAttachments() {
        val t = ComposeEmail()
        assertEquals(Risk.SAFE, t.risk)
        val d = t.describe(JSONObject().put("to", "a@x.id").put("cc", "b@y.id").put("subject", "Tiket 730470").put("body", "Halo").put("attachments", "uploads/a.pdf"))
        listOf("a@x.id", "b@y.id", "Tiket 730470", "uploads/a.pdf", "TIDAK terkirim").forEach { assertTrue("hilang: $it\n$d", d.contains(it)) }
    }

    @Test
    fun clockAndDateParsing() {
        assertEquals(8 to 30, parseClock("08:30"))
        assertEquals(9 to 5, parseClock("9.05"))
        assertNull(parseClock("24:00"))
        assertNull(parseClock("8:60"))
        assertNotNull(parseLocalDateTime("2026-11-10 09:00"))
        assertNull(parseLocalDateTime("2026-02-31 09:00"))
        assertNull(parseLocalDateTime("10 November 2026"))
    }

    @Test
    fun reminderAlarmAndCalendar() = runTest {
        val dev = FakeDevice()
        val (e, _) = env(dev)
        val a = SetReminder().run(JSONObject().put("kind", "alarm").put("time", "08:30").put("label", "Cek Flip"), e)
        assertTrue(a.text, a.ok)
        assertEquals(Triple(8, 30, "Cek Flip"), dev.alarm)
        val c = SetReminder().run(JSONObject().put("kind", "calendar").put("title", "Tenggat Flip").put("start", "2026-11-10 09:00").put("duration_minutes", 45), e)
        assertTrue(c.text, c.ok)
        val ev = dev.event!!
        assertEquals("Tenggat Flip", ev[0])
        assertEquals(45 * 60_000L, (ev[2] as Long) - (ev[1] as Long))
        assertTrue(failure { SetReminder().run(JSONObject().put("kind", "calendar").put("title", "x").put("start", "besok"), e) } is ArgError)
        assertTrue(failure { SetReminder().run(JSONObject().put("kind", "sms"), e) } is ArgError)
    }

    @Test
    fun registryHasDeviceToolsOnlyWhenEnabledAndPromptMentionsThem() {
        assertNull(ToolRegistry.default(false).get("ComposeEmail"))
        val reg = ToolRegistry.default(false, withDevice = true)
        assertNotNull(reg.get("ComposeEmail"))
        assertNotNull(reg.get("SetReminder"))
        assertNotNull(reg.get("SetDeadlineNotice"))
        val p = Protocol.systemPrompt(reg, false)
        listOf("KASUS.md", "SEPARATE DRAFTS", "ORDER OF ACTIONS", "ComposeEmail", "SetReminder", "SetDeadlineNotice").forEach { assertTrue("hilang: $it", p.contains(it)) }
        assertFalse(Protocol.systemPrompt(ToolRegistry.default(false), false).contains("call ComposeEmail"))
    }

    @Test
    fun composeEmailRefusesFullNikInBody() = runTest {
        val dev = FakeDevice()
        val (e, _) = env(dev)
        fun mail(body: String) = JSONObject().put("to", "a@x.id").put("subject", "S").put("body", body)
        for (b in listOf("NIK 3174012345678901 ya", "NIK 3174 0123 4567 8901", "NIK 3174-0123-4567-8901")) {
            val r = ComposeEmail().run(mail(b), e)
            assertFalse(r.text, r.ok)
            assertTrue(r.text.contains("TIDAK dibuka"))
        }
        assertNull(dev.email)
        // Nomor tersamar, tiket, atau angka lebih panjang dari 16 digit tidak diblokir.
        assertTrue(ComposeEmail().run(mail("NIK 3174********8901, tiket 730470"), e).ok)
        assertTrue(ComposeEmail().run(mail("Ref 12345678901234567"), e).ok)
    }

    @Test
    fun deadlineNoticeSchedulesMainAndEarlyNotice() = runTest {
        val dev = FakeDevice()
        val (e, _) = env(dev)
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + 3 * 86_400_000L))
        val r = SetDeadlineNotice().run(JSONObject().put("title", "Batas Flip").put("at", at).put("remind_before_minutes", 1440), e)
        assertTrue(r.text, r.ok)
        assertEquals(2, dev.notices.size)
        assertTrue(dev.notices[0].third < dev.notices[1].third)
        assertTrue(dev.notices[0].second.startsWith("Segera"))
    }

    @Test
    fun deadlineNoticeRejectsPastAndBadInput() = runTest {
        val dev = FakeDevice()
        val (e, _) = env(dev)
        val past = SetDeadlineNotice().run(JSONObject().put("title", "x").put("at", "2020-01-01 09:00"), e)
        assertFalse(past.ok)
        assertTrue(dev.notices.isEmpty())
        assertTrue(failure { SetDeadlineNotice().run(JSONObject().put("title", "x").put("at", "besok"), e) } is ArgError)
        assertFalse(SetDeadlineNotice().run(JSONObject().put("title", "x").put("at", "2099-01-01 09:00"), env(null).first).ok)
    }
}
