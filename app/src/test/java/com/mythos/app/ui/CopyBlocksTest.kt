package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyBlocksTest {
    @Test
    fun plainTextIsOneProsePart() {
        val parts = splitAnswer("Halo\nApa kabar")
        assertEquals(listOf(AnswerPart.Prose("Halo\nApa kabar")), parts)
    }

    @Test
    fun salinFenceBecomesClosedBlockBetweenProse() {
        val src = "Pilih status TIDAK SEPAKAT, lalu isi:\n```salin\nBaris 1\n\nBaris 3\n```\nSetelah itu kirim."
        val parts = splitAnswer(src)
        assertEquals(3, parts.size)
        assertEquals(AnswerPart.Prose("Pilih status TIDAK SEPAKAT, lalu isi:"), parts[0])
        assertEquals(AnswerPart.Fence("salin", "Baris 1\n\nBaris 3", true), parts[1])
        assertEquals(AnswerPart.Prose("Setelah itu kirim."), parts[2])
    }

    @Test
    fun unclosedFenceIsMarkedOpenWhileStreaming() {
        val parts = splitAnswer("Teks:\n```salin\nMasih mengalir")
        val f = parts.last() as AnswerPart.Fence
        assertFalse(f.closed)
        assertEquals("Masih mengalir", f.body)
    }

    @Test
    fun severalBlocksAndCodeKeepTheirLanguage() {
        val parts = splitAnswer("```salin\nA\n```\n```kotlin\nval x = 1\n```")
        assertEquals(2, parts.size)
        assertTrue(parts.all { it is AnswerPart.Fence })
        assertEquals("kotlin", (parts[1] as AnswerPart.Fence).lang)
        assertEquals("Teks siap salin", fenceTitle("salin"))
        assertEquals("kotlin", fenceTitle("kotlin"))
    }

    @Test
    fun stripMarkdownRemovesStarsHeadingsAndBullets() {
        val src = "Berikut:\n- **Nomor Tiket:** P261101916\n- **Masalah:** saldo *refund* dibekukan\n## Judul\nTotal 2 * 3 = 6 dan `kode`"
        val out = stripMarkdown(src)
        assertEquals("Berikut:\nNomor Tiket: P261101916\nMasalah: saldo refund dibekukan\nJudul\nTotal 2 * 3 = 6 dan kode", out)
        assertFalse(out.contains("**"))
    }

    @Test
    fun stripMarkdownLeavesPlainAndNumberedTextAlone() {
        val src = "Hormat saya,\n1. Status refund\n2. Langkah lanjut"
        assertEquals(src, stripMarkdown(src))
    }

    @Test
    fun listItemsBecomeItemsWithHangingIndentLevels() {
        val blocks = splitProse("Ringkasan:\n\n• **Identitas:** satu\n  1. Pertama\n  2) Kedua\n- Tiga\nPenutup")
        assertEquals(
            listOf(
                ProseBlock.Para("Ringkasan:"),
                ProseBlock.Item("•", "**Identitas:** satu", 0),
                ProseBlock.Item("1.", "Pertama", 1),
                ProseBlock.Item("2)", "Kedua", 1),
                ProseBlock.Item("•", "Tiga", 0),
                ProseBlock.Para("Penutup"),
            ),
            blocks
        )
    }

    @Test
    fun boldAtLineStartIsNotAListItem() {
        assertEquals(listOf(ProseBlock.Para("**Tebal** di awal")), splitProse("**Tebal** di awal"))
    }

    @Test
    fun leadingSpacesOnParagraphLinesAreDropped() {
        assertEquals(listOf(ProseBlock.Para("Awal\nlanjutan")), splitProse("Awal\n   lanjutan"))
    }
}
