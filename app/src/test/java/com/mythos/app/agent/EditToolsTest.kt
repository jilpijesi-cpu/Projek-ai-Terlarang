package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EditToolsTest {
    @Test
    fun pageRangesParseListsRangesAndOpenEnds() {
        assertEquals(listOf(1, 2, 3, 5), parsePageRanges("1-3,5", 8))
        assertEquals(listOf(6, 7, 8), parsePageRanges("6-", 8))
        assertEquals(listOf(1, 2, 3), parsePageRanges("-3", 8))
        assertEquals(listOf(3, 1), parsePageRanges("3, 1, 3", 8))
        assertEquals((1..4).toList(), parsePageRanges("", 4))
        assertEquals((1..4).toList(), parsePageRanges("semua", 4))
    }

    @Test
    fun pageRangesRejectBadInput() {
        assertNull(parsePageRanges("0", 8))
        assertNull(parsePageRanges("9", 8))
        assertNull(parsePageRanges("3-2", 8))
        assertNull(parsePageRanges("1-9", 8))
        assertNull(parsePageRanges("a", 8))
        assertNull(parsePageRanges("-", 8))
        assertNull(parsePageRanges("1", 0))
    }

    @Test
    fun pixelRectClampsAndRejectsEmpty() {
        val r = toPixelRect(0.1, 0.2, 0.5, 0.25, 200, 100)!!
        assertEquals(listOf(20, 20, 100, 25), listOf(r.x, r.y, r.w, r.h))
        val clipped = toPixelRect(0.8, 0.8, 0.5, 0.5, 100, 100)!!
        assertEquals(listOf(80, 80, 20, 20), listOf(clipped.x, clipped.y, clipped.w, clipped.h))
        assertNull(toPixelRect(1.0, 0.0, 0.2, 0.2, 100, 100))
        assertNull(toPixelRect(0.0, 0.0, 0.0, 0.5, 100, 100))
        assertNull(toPixelRect(Double.NaN, 0.0, 0.5, 0.5, 100, 100))
    }

    @Test
    fun pixelateOnlyTouchesTheRegionAndAveragesBlocks() {
        val w = 8; val h = 4
        val px = IntArray(w * h) { if (it % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val before = px.copyOf()
        pixelate(px, w, PxRect(0, 0, 4, 4), 4)
        for (y in 0 until h) for (x in 4 until w) assertEquals(before[y * w + x], px[y * w + x])
        val first = px[0]
        for (y in 0 until h) for (x in 0 until 4) assertEquals(first, px[y * w + x])
        val g = (first shr 16) and 255
        assertTrue("abu-abu rata-rata, bukan hitam/putih: $g", g in 100..156)
    }

    @Test
    fun editToolsRefuseToOverwriteOriginalAndBadArgs() = kotlinx.coroutines.test.runTest {
        val dir = Files.createTempDirectory("mythos-edit").toFile()
        java.io.File(dir, "uploads").mkdirs()
        java.io.File(dir, "uploads/a.jpg").writeText("x")
        java.io.File(dir, "uploads/a.pdf").writeText("x")
        val env = ToolEnv(ProjectSandbox(dir), SessionState(), { "" }, null)
        val same = org.json.JSONObject("""{"input":"uploads/a.jpg","output":"uploads/a.jpg","ops":[{"op":"redact","x":0,"y":0,"w":1,"h":1}]}""")
        val r = EditImage().run(same, env)
        assertFalse(r.ok); assertTrue(r.text.contains("berbeda"))
        val noOps = org.json.JSONObject("""{"input":"uploads/a.jpg","output":"uploads/b.jpg","ops":[]}""")
        assertTrue(runCatching { EditImage().run(noOps, env) }.exceptionOrNull() is ArgError)
        val badExt = EditImage().run(org.json.JSONObject("""{"input":"uploads/a.jpg","output":"uploads/b.gif","ops":[{"op":"resize","max_side":500}]}"""), env)
        assertFalse(badExt.ok)
        val badOp = org.json.JSONObject("""{"op":"rotate"}""")
        assertTrue(runCatching { PdfEdit().run(badOp, env) }.exceptionOrNull() is ArgError)
        val one = org.json.JSONObject("""{"op":"merge","inputs":["uploads/a.pdf"],"output":"uploads/m.pdf"}""")
        assertTrue(runCatching { PdfEdit().run(one, env) }.exceptionOrNull() is ArgError)
        val over = org.json.JSONObject("""{"op":"watermark","input":"uploads/a.pdf","output":"uploads/a.pdf","text":"x"}""")
        assertTrue(runCatching { PdfEdit().run(over, env) }.exceptionOrNull() is ArgError)
    }

    @Test
    fun playbooksAreLoadedFromFolderAndUploads() {
        val dir = Files.createTempDirectory("mythos-pb").toFile()
        assertNull(Playbooks.render(dir))
        java.io.File(dir, "playbook").mkdirs()
        java.io.File(dir, "playbook/asuransi.md").writeText("# Asuransi\nRegulator: OJK")
        java.io.File(dir, "playbook/catatan.docx").writeText("bukan teks")
        java.io.File(dir, "uploads").mkdirs()
        java.io.File(dir, "uploads/playbook-pajak.txt").writeText("Pajak: DJP")
        java.io.File(dir, "uploads/foto.txt").writeText("bukan playbook")
        val text = Playbooks.render(dir)!!
        assertTrue(text.contains("playbook/asuransi.md") && text.contains("Regulator: OJK"))
        assertTrue(text.contains("uploads/playbook-pajak.txt") && text.contains("Pajak: DJP"))
        assertFalse(text.contains("bukan teks") || text.contains("bukan playbook"))
    }

    @Test
    fun registryAndPromptMentionNewTools() {
        val reg = ToolRegistry.default(false)
        assertNotNull(reg.get("EditImage")); assertNotNull(reg.get("PdfEdit"))
        val p = Protocol.systemPrompt(reg, false)
        assertFalse(p.contains("Crop unrelated third-party"))
        assertFalse(p.contains("watermark naming the recipient"))
        listOf("IMAGE EDITING", "PDF EDITING", "PLAYBOOKS", "VERIFICATION BUNDLE", "ORIGINAL EVIDENCE", "CASE SUMMARY", "Playbook: <sektor").forEach { assertTrue("hilang: $it", p.contains(it)) }
    }
}
