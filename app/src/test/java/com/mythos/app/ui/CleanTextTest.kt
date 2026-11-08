package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CleanTextTest {
    private val raw = listOf("**", "__", "~~", "](", "<tool_use", "</tool_use", "<thinking")

    @Test
    fun toolAndThinkingLeaksAreRemovedEvenWhenUnclosed() {
        assertEquals("Siap.", cleanForDisplay("Siap.<tool_use>{\"name\": \"Re"))
        assertEquals("Siap.", cleanForDisplay("Siap.<tool_u"))
        assertEquals("Halo", cleanForDisplay("<thinking>mikir</thinking>Halo"))
        assertEquals("Halo", cleanForDisplay("{\"status\": \"x\", \"name\": \"LS\", \"input\": {}}\nHalo"))
        assertEquals("Halo", cleanForDisplay("{\"name\": \"Read\", \"parameters\": {\"file_path\": \"a.kt\"}}\nHalo"))
    }

    @Test
    fun mentionInBackticksAndInsideFencesIsKept() {
        assertEquals("Pakai `<tool_use>` saja", cleanForDisplay("Pakai `<tool_use>` saja"))
        val code = "```kotlin\nval s = \"<tool_use>\" // **x**\n```"
        assertEquals(code, cleanForDisplay(code))
    }

    @Test
    fun inlineSyntaxIsFlattenedButBoldAndCodeStayForRenderer() {
        assertEquals("saldo refund dibekukan", cleanForDisplay("saldo *refund* dibekukan"))
        assertEquals("Ini **tebal** ya", cleanForDisplay("Ini __tebal__ ya"))
        assertEquals("batal", cleanForDisplay("~~batal~~"))
        assertEquals("Lihat situs (https://a.com)", cleanForDisplay("Lihat [situs](https://a.com)"))
        assertEquals("Pakai `__init__` dan `a*b*c`", cleanForDisplay("Pakai `__init__` dan `a*b*c`"))
        assertEquals("file_name_x dan __init__.py", cleanForDisplay("file_name_x dan __init__.py"))
        assertEquals("2 * 3 = 6", cleanForDisplay("2 * 3 = 6"))
    }

    @Test
    fun tablesRulesAndQuotesBecomePlainLines() {
        assertEquals("Nama  ·  Harga\nA  ·  1", cleanForDisplay("| Nama | Harga |\n|---|---|\n| A | 1 |"))
        assertEquals("A\nB", cleanForDisplay("A\n---\nB"))
        assertEquals("kutipan", cleanForDisplay("> kutipan"))
    }

    @Test
    fun unfinishedMarkersAtTheStreamingEdgeAreHidden() {
        assertEquals("saldo refund", cleanForDisplay("saldo *refund"))
        assertEquals("Lihat situs", cleanForDisplay("Lihat [situs](https://a.c"))
        assertEquals("Lihat situs", cleanForDisplay("Lihat [situs"))
        assertEquals("Halo", cleanForDisplay("Halo ~~"))
        assertEquals("Ini **tebal**", cleanForDisplay("Ini **tebal**"))
        assertEquals("A\n```salin\n**x** *", cleanForDisplay("A\n```salin\n**x** *"))
    }

    @Test
    fun closingFenceIsNeverTrimmed() {
        assertEquals("```salin\nIsi\n```", cleanForDisplay("```salin\nIsi\n```"))
    }

    @Test
    fun oneLineLabelsHaveNoMarkdown() {
        assertEquals("Memeriksa alur streaming", cleanLine("**Memeriksa** `alur` streaming"))
        assertEquals("Mencari *.kt", cleanLine("Mencari *.kt"))
        assertEquals("Membaca __init__.py", cleanLine("Membaca __init__.py"))
        assertEquals("Halo", cleanLine("Halo<tool_use>{"))
    }

    @Test
    fun plainTextForCopyBoxHasNoRawSymbols() {
        val src = "KEPADA YTH.\n- **Nomor Tiket:** P261101916\n- **Masalah:** saldo *refund* ~~lama~~\n| a | b |\n|---|---|\n## Judul\n> kutip\n[tautan](https://x.id)"
        val out = plainText(src)
        raw.forEach { assertFalse("sisa $it di: $out", out.contains(it)) }
        assertFalse(out.contains("*"))
        assertEquals(
            "KEPADA YTH.\nNomor Tiket: P261101916\nMasalah: saldo refund lama\na  ·  b\nJudul\nkutip\ntautan (https://x.id)",
            out
        )
    }

    @Test
    fun specialTokenMarkupAndFabricatedResultsAreCut() {
        assertEquals("Saya cek.", cleanForDisplay("Saya cek.\n<|start_header_id|>tool_use<|end_header_id|>\n<|start_code|>").trim())
        assertEquals("Cari dulu", cleanForDisplay("Cari dulu\n<tool_result id=\"1\">\nkarangan").trim())
        assertEquals("Cari dulu", cleanForDisplay("Cari dulu\n<|start_header_id|>tool_result<|end_header_id|>\nkarangan").trim())
        assertEquals("", cleanLine("<|start_header_id|>tool_use<|end_header_id|>"))
    }

    @Test
    fun singleSpecialTokenMentionIsKept() {
        assertEquals("Token <|im_start|> itu penanda", cleanForDisplay("Token <|im_start|> itu penanda"))
    }
}
