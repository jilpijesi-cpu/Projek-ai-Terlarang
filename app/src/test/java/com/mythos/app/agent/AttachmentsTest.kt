package com.mythos.app.agent

import com.mythos.app.ui.AttachKind
import com.mythos.app.ui.Attachment
import com.mythos.app.ui.ChatStore
import com.mythos.app.ui.UserItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AttachmentsTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (n, c) ->
                z.putNextEntry(ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun safeNameStripsPathsAndOddChars() {
        assertEquals("laporan_.pdf", AttachmentText.safeName("../../laporan?.pdf"))
        assertEquals("lampiran", AttachmentText.safeName("///"))
        assertFalse(AttachmentText.safeName(".hidden").startsWith("."))
    }

    @Test
    fun detectsTextVersusBinary() {
        assertTrue(AttachmentText.isProbablyText("fun main() {}\n".toByteArray()))
        assertFalse(AttachmentText.isProbablyText(byteArrayOf(1, 2, 0, 3, 4)))
    }

    @Test
    fun extractsDocxText() {
        val bytes = zip("word/document.xml" to "<w:document><w:p><w:r><w:t>Halo</w:t></w:r></w:p><w:p><w:r><w:t>Dunia &amp; semua</w:t></w:r></w:p></w:document>")
        val t = AttachmentText.extractOfficeText(bytes, "docx")
        assertNotNull(t)
        assertEquals("Halo\nDunia & semua", t)
    }

    @Test
    fun extractsPptxSlidesInOrder() {
        val bytes = zip(
            "ppt/slides/slide2.xml" to "<p><a:t>Kedua</a:t></p>",
            "ppt/slides/slide1.xml" to "<p><a:t>Pertama</a:t></p>",
        )
        val t = AttachmentText.extractOfficeText(bytes, "pptx")!!
        assertTrue(t.indexOf("Pertama") < t.indexOf("Kedua"))
    }

    @Test
    fun extractsXlsxCellsWithSharedStrings() {
        val bytes = zip(
            "xl/sharedStrings.xml" to "<sst><si><t>Nama</t></si><si><t>Budi</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>42</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>7</v></c></row></sheetData></worksheet>",
        )
        val t = AttachmentText.extractOfficeText(bytes, "xlsx")!!
        assertTrue(t, t.contains("Nama\t42"))
        assertTrue(t, t.contains("Budi\t7"))
    }

    @Test
    fun unzipRejectsZipSlip() {
        val dir = Files.createTempDirectory("unzip").toFile()
        val z = File(dir, "evil.zip").apply { writeBytes(zip("../escape.txt" to "x")) }
        try {
            AttachmentText.unzipSafely(z, File(dir, "out"))
            fail("seharusnya menolak zip-slip")
        } catch (e: IOException) {
            assertFalse(File(dir, "escape.txt").exists())
        }
    }

    @Test
    fun unzipExtractsNormalFiles() {
        val dir = Files.createTempDirectory("unzip").toFile()
        val z = File(dir, "ok.zip").apply { writeBytes(zip("a/b.txt" to "isi")) }
        val r = AttachmentText.unzipSafely(z, File(dir, "out"))
        assertEquals(1, r.files)
        assertEquals("isi", File(dir, "out/a/b.txt").readText())
    }

    @Test
    fun visionDetectionByModelName() {
        assertTrue(supportsVision(ProviderKind.ANTHROPIC, "claude-sonnet-4"))
        assertFalse(supportsVision(ProviderKind.ANTHROPIC, "claude-instant-1.2"))
        assertTrue(supportsVision(ProviderKind.GEMINI, "gemini-2.5-flash"))
        assertTrue(supportsVision(ProviderKind.OPENAI_COMPATIBLE, "gpt-4o-mini"))
        assertTrue(supportsVision(ProviderKind.OPENAI_COMPATIBLE, "meta-llama/llama-4-scout"))
        assertTrue(supportsVision(ProviderKind.OPENAI_COMPATIBLE, "llava:13b"))
        assertFalse(supportsVision(ProviderKind.OPENAI_COMPATIBLE, "deepseek-chat"))
        assertFalse(supportsVision(ProviderKind.OPENAI_COMPATIBLE, "llama-3.3-70b-versatile"))
    }

    private val img = ImagePart("image/jpeg", "QUJD")

    @Test
    fun openAiBodyCarriesImagesAsDataUrls() {
        val cfg = Presets.openAi("k", "gpt-4o")
        val body = openAiBody(cfg, "sys", listOf(Msg(Role.USER, "lihat", listOf(img))))
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun anthropicBodyPutsImageBlocksFirst() {
        val body = anthropicBody(Presets.anthropic("k", "claude-sonnet-4"), "sys", listOf(Msg(Role.USER, "lihat", listOf(img))))
        val blocks = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image", blocks.getJSONObject(0).getString("type"))
        assertEquals("QUJD", blocks.getJSONObject(0).getJSONObject("source").getString("data"))
        assertEquals("text", blocks.getJSONObject(1).getString("type"))
    }

    @Test
    fun geminiBodyUsesInlineData() {
        val body = geminiBody(Presets.gemini("k", "gemini-2.5-pro"), "sys", listOf(Msg(Role.USER, "lihat", listOf(img))))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inline_data").getString("mime_type"))
        assertEquals("lihat", parts.getJSONObject(1).getString("text"))
    }

    @Test
    fun textOnlyMessagesStayPlainStrings() {
        val body = openAiBody(Presets.openAi("k", "gpt-4o"), "sys", listOf(Msg(Role.USER, "halo")))
        assertEquals("halo", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun mergeKeepsImagesOfBothMessages() {
        val merged = listOf(Msg(Role.USER, "a", listOf(img)), Msg(Role.USER, "b", listOf(img))).mergeSameRole()
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].images.size)
    }

    @Test
    fun visionGateStripsImagesForTextOnlyModels() = kotlinx.coroutines.test.runTest {
        var seen: List<Msg> = emptyList()
        val inner = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String { seen = messages; return "ok" }
        }
        VisionGate(Presets.deepSeek("k", "deepseek-chat"), inner).complete("s", listOf(Msg(Role.USER, "x", listOf(img))))
        assertTrue(seen[0].images.isEmpty())
        assertTrue(seen[0].text.contains("CATATAN SISTEM"))
        VisionGate(Presets.openAi("k", "gpt-4o"), inner).complete("s", listOf(Msg(Role.USER, "x", listOf(img))))
        assertEquals(1, seen[0].images.size)
    }

    @Test
    fun chatStoreRoundTripsAttachments() {
        val item = UserItem(5, "halo", listOf(Attachment("a.zip", AttachKind.DOC, "/p/uploads/a.zip", "application/zip", 12)))
        val store = ChatStore(Files.createTempDirectory("cs").toFile())
        val back = store.decode(JSONObject(store.encode(item).toString()), 5) as UserItem
        assertEquals("a.zip", back.attachments[0].name)
        assertEquals(AttachKind.DOC, back.attachments[0].kind)
    }

    @Test
    fun xlsxReadsInlineStringsAndOrdersSheetsNaturally() {
        fun sheet(text: String) = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>$text</t></is></c></row></sheetData></worksheet>"
        val bytes = zip("xl/worksheets/sheet10.xml" to sheet("sepuluh"), "xl/worksheets/sheet2.xml" to sheet("dua"))
        val text = AttachmentText.extractOfficeText(bytes, "xlsx")!!
        assertTrue(text, text.contains("dua") && text.contains("sepuluh"))
        assertTrue(text, text.indexOf("sheet2") < text.indexOf("sheet10"))
    }

    @Test
    fun xlsxSelfClosingRowDoesNotSwallowTheNextRow() {
        val sheet = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>satu</t></is></c></row>" +
            "<row r=\"2\" spans=\"1:1\"/>" +
            "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>tiga</t></is></c></row></sheetData></worksheet>"
        val text = AttachmentText.extractOfficeText(zip("xl/worksheets/sheet1.xml" to sheet), "xlsx")!!
        assertTrue(text, text.contains("satu\n\ntiga"))
    }

    @Test
    fun docxTableCellsAreSeparatedByTabs() {
        fun cell(t: String) = "<w:tc><w:p><w:r><w:t>$t</w:t></w:r></w:p></w:tc>"
        val xml = "<w:document><w:tbl><w:tr>${cell("Nama")}${cell("Umur")}</w:tr><w:tr>${cell("Ani")}${cell("30")}</w:tr></w:tbl></w:document>"
        val text = AttachmentText.extractOfficeText(zip("word/document.xml" to xml), "docx")!!
        assertEquals("Nama\tUmur\nAni\t30", text)
    }
}
