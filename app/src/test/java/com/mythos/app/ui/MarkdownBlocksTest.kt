package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heading, tabel, dan daftar bernomor seperti pada rekaman acuan (Claude): dirender sebagai blok, bukan teks datar. */
class MarkdownBlocksTest {
    @Test
    fun headingBecomesHeadingBlockWithoutHashes() {
        val b = splitProse("Pengantar\n## Latensi\nIsi")
        assertEquals(
            listOf(ProseBlock.Para("Pengantar"), ProseBlock.Heading(2, "Latensi"), ProseBlock.Para("Isi")),
            b
        )
        assertEquals(ProseBlock.Heading(1, "Judul besar"), splitProse("# Judul besar").single())
    }

    @Test
    fun tableBecomesTableBlockAndDropsSeparatorRow() {
        val b = splitProse("Hasil:\n| Giliran | Token pertama |\n|---|---|\n| halo | 5,5 dtk |\n| PDF | **22 dtk** |\nSelesai")
        assertEquals(3, b.size)
        val t = b[1] as ProseBlock.Table
        assertEquals(
            listOf(listOf("Giliran", "Token pertama"), listOf("halo", "5,5 dtk"), listOf("PDF", "**22 dtk**")),
            t.rows
        )
        assertEquals(ProseBlock.Para("Selesai"), b[2])
    }

    @Test
    fun cleanForDisplayKeepsTablesOnlyWhenAsked() {
        val src = "| Nama | Harga |\n|---|---|\n| A | 1 |"
        assertEquals("Nama  ·  Harga\nA  ·  1", cleanForDisplay(src))
        val kept = cleanForDisplay(src, keepTables = true)
        assertTrue(kept.lines().all { it.startsWith("|") })
        assertEquals(2, (splitProse(kept).single() as ProseBlock.Table).rows.size)
    }

    @Test
    fun numberedAndBulletItemsKeepTheirMarkers() {
        val b = splitProse("1. Satu\n2. Dua\n- Tiga")
        assertEquals(
            listOf(ProseBlock.Item("1.", "Satu", 0), ProseBlock.Item("2.", "Dua", 0), ProseBlock.Item("•", "Tiga", 0)),
            b
        )
    }

    @Test
    fun lonePipeLineWhileStreamingStaysAParagraph() {
        assertEquals(listOf(ProseBlock.Para("| Gil")), splitProse("| Gil"))
    }
}
