package com.mythos.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pengukur palsu: lebar teks = jumlah karakter (1 poin per huruf), jadi satu baris muat 499 huruf. */
private class Fake(private val sizes: Map<String, Pair<Int, Int>> = emptyMap()) : PdfMeasurer {
    override fun width(text: String, style: PdfStyle): Float = text.length.toFloat()
    override fun imageSize(path: String): Pair<Int, Int>? = sizes[path]
}

private fun body(text: String) = PdfBlock.Text(PdfStyle.BODY, text)
private fun manyLines(n: Int) = (1..n).joinToString("\n") { "x" }
private fun PlannedPage.texts() = ops.filterIsInstance<PdfOp.Text>()
private fun PlannedPage.images() = ops.filterIsInstance<PdfOp.Img>()

class PdfLayoutTest {

    // ---------- pembungkus teks ----------

    @Test
    fun wrap_memecahDiBatasKata() {
        assertEquals(listOf("aaa bbb", "ccc"), wrapLines("aaa bbb ccc", PdfStyle.BODY, 7f, Fake()))
    }

    @Test
    fun wrap_kataTerlaluPanjang_dipecahPerHuruf() {
        assertEquals(listOf("abcd", "efgh", "ij"), wrapLines("abcdefghij", PdfStyle.BODY, 4f, Fake()))
    }

    @Test
    fun wrap_barisKosongDipertahankanSebagaiJedaParagraf() {
        assertEquals(listOf("a", "", "b"), wrapLines("a\n\nb", PdfStyle.BODY, 50f, Fake()))
    }

    @Test
    fun fitSingleLine_dipotongDenganElipsis() {
        assertEquals("abcde…", fitSingleLine("abcdefghij", PdfStyle.BODY, 6f, Fake()))
        assertEquals("pendek", fitSingleLine("pendek", PdfStyle.BODY, 50f, Fake()))
    }

    // ---------- alur halaman ----------

    @Test
    fun teksPanjang_mengalirKeBeberapaHalaman() {
        val pages = PdfPlanner.plan(listOf(body(manyLines(120))), Fake())
        assertEquals(listOf(49, 49, 22), pages.map { it.texts().size })
    }

    @Test
    fun dokumenKosong_tidakMenghasilkanHalaman() {
        assertTrue(PdfPlanner.plan(emptyList(), Fake()).isEmpty())
    }

    @Test
    fun pageBreak_diAwalDanBerulang_tidakMenghasilkanHalamanKosong() {
        val blocks = listOf(PdfBlock.PageBreak, body("a"), PdfBlock.PageBreak, PdfBlock.PageBreak)
        assertEquals(1, PdfPlanner.plan(blocks, Fake()).size)
    }

    @Test
    fun pageBreak_diTengah_membagiDuaHalaman() {
        val pages = PdfPlanner.plan(listOf(body("a"), PdfBlock.PageBreak, body("b")), Fake())
        assertEquals(2, pages.size)
        assertEquals("b", pages[1].texts().single().text)
    }

    // ---------- gambar ----------

    @Test
    fun gambarLebar_diskalaKeLebarIsi() {
        val pages = PdfPlanner.plan(listOf(PdfBlock.Image("a")), Fake(mapOf("a" to (1000 to 500))))
        val img = pages.single().images().single()
        assertEquals(499f, img.w, 0.01f)
        assertEquals(249.5f, img.h, 0.01f)
    }

    @Test
    fun gambarTinggi_diskalaKeTinggiIsi_danDitengahkan() {
        val pages = PdfPlanner.plan(listOf(PdfBlock.Image("a")), Fake(mapOf("a" to (1000 to 2000))))
        val img = pages.single().images().single()
        assertEquals(730f, img.h, 0.5f)
        assertEquals(365f, img.w, 0.5f)
        assertEquals(115f, img.x, 0.5f)
    }

    @Test
    fun gambarKecil_tidakDiperbesar() {
        val pages = PdfPlanner.plan(listOf(PdfBlock.Image("a")), Fake(mapOf("a" to (100 to 50))))
        val img = pages.single().images().single()
        assertEquals(100f, img.w, 0.01f)
        assertEquals(50f, img.h, 0.01f)
    }

    @Test
    fun gambar_pindahKeHalamanBaruBilaSisaRuangTerlaluKecil() {
        val blocks = listOf(body(manyLines(47)), PdfBlock.Image("a"))
        val pages = PdfPlanner.plan(blocks, Fake(mapOf("a" to (600 to 600))))
        assertEquals(2, pages.size)
        assertTrue(pages[0].images().isEmpty())
        assertEquals(1, pages[1].images().size)
    }

    @Test
    fun judulBagian_tidakTerpisahDariGambarYangMengikutinya() {
        val blocks = listOf(
            body(manyLines(40)),
            PdfBlock.Text(PdfStyle.HEADING, "Lampiran 1"),
            body("keterangan"),
            PdfBlock.Image("a"),
        )
        val pages = PdfPlanner.plan(blocks, Fake(mapOf("a" to (600 to 600))))
        assertEquals(2, pages.size)
        assertFalse(pages[0].texts().any { it.style == PdfStyle.HEADING })
        assertTrue(pages[1].texts().any { it.style == PdfStyle.HEADING })
        assertEquals(1, pages[1].images().size)
    }

    @Test
    fun gambarTakTerbaca_menolakDenganPesanJelas() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PdfPlanner.plan(listOf(PdfBlock.Image("hilang.jpg")), Fake())
        }
        assertTrue(e.message!!.contains("hilang.jpg"))
    }

    // ---------- daftar dan kolom ----------

    @Test
    fun daftarBernomor_memberiPenandaDanMenjorokkanIsi() {
        val t = PdfPlanner.plan(listOf(PdfBlock.Numbered(listOf("satu", "dua"))), Fake()).single().texts()
        assertEquals(listOf("1.", "satu", "2.", "dua"), t.map { it.text })
        assertEquals(48f, t[0].x, 0.01f)
        assertEquals(72f, t[1].x, 0.01f)
    }

    @Test
    fun fields_menempatkanLabelTitikDuaDanNilai() {
        val rows = listOf("Nama" to "Agenia", "HP" to "08")
        val t = PdfPlanner.plan(listOf(PdfBlock.Fields(rows)), Fake()).single().texts()
        assertEquals(2, t.count { it.text == ":" })
        assertEquals(68f, t.first { it.text == "Agenia" }.x, 0.01f)
        assertEquals(48f, t.first { it.text == "Nama" }.x, 0.01f)
    }

    // ---------- penguraian spesifikasi ----------

    @Test
    fun parser_menerimaSemuaJenisBlok() {
        val spec = PdfSpecParser.parse(
            JSONObject(
                """{"output":"uploads/a.pdf","footer":"F","blocks":[
                {"type":"title","text":"T"},
                {"type":"numbered","items":["a","","b"]},
                {"type":"fields","rows":[["Nama","X"],{"label":"HP","value":"1"}]},
                {"type":"image","path":"uploads/p.jpg"},
                {"type":"pagebreak"},{"type":"rule"}]}"""
            )
        )
        assertEquals("F", spec.footer)
        assertEquals(6, spec.blocks.size)
        assertEquals(listOf("a", "b"), (spec.blocks[1] as PdfBlock.Numbered).items)
        assertEquals(2, (spec.blocks[2] as PdfBlock.Fields).rows.size)
    }

    @Test
    fun parser_membuangEmojiDanTandaTebalMarkdown() {
        val spec = PdfSpecParser.parse(JSONObject("""{"output":"a.pdf","blocks":[{"type":"text","text":"Halo \uD83D\uDE00 **tebal**"}]}"""))
        assertEquals("Halo  tebal", (spec.blocks.single() as PdfBlock.Text).text)
    }

    @Test
    fun parser_menolakOutputBukanPdf() {
        assertThrows(ArgError::class.java) {
            PdfSpecParser.parse(JSONObject("""{"output":"a.txt","blocks":[{"type":"text","text":"x"}]}"""))
        }
    }

    @Test
    fun parser_menolakBlokKosongDanJenisAsing() {
        assertThrows(ArgError::class.java) { PdfSpecParser.parse(JSONObject("""{"output":"a.pdf","blocks":[]}""")) }
        assertThrows(ArgError::class.java) { PdfSpecParser.parse(JSONObject("""{"output":"a.pdf"}""")) }
        assertThrows(ArgError::class.java) { PdfSpecParser.parse(JSONObject("""{"output":"a.pdf","blocks":[{"type":"text","text":"  "}]}""")) }
        val e = assertThrows(ArgError::class.java) {
            PdfSpecParser.parse(JSONObject("""{"output":"a.pdf","blocks":[{"type":"video"}]}"""))
        }
        assertTrue(e.message!!.contains("unknown type"))
    }

    @Test
    fun parser_membatasiJumlahGambar() {
        val blocks = (1..41).joinToString(",") { """{"type":"image","path":"uploads/$it.jpg"}""" }
        assertThrows(ArgError::class.java) {
            PdfSpecParser.parse(JSONObject("""{"output":"a.pdf","blocks":[$blocks]}"""))
        }
    }

    @Test
    fun items_penomoranBuatanModelDibuang() {
        val spec = PdfSpecParser.parse(JSONObject("""{"output":"a.pdf","blocks":[
            {"type":"numbered","items":["1. Foto KTP","2) Foto selfie","(3) Bukti","1. 1. Ganda","Tanpa nomor","2024 tetap"]},
            {"type":"bullets","items":["- Satu","\u2022 Dua"]}]}"""))
        assertEquals(listOf("Foto KTP", "Foto selfie", "Bukti", "Ganda", "Tanpa nomor", "2024 tetap"), (spec.blocks[0] as PdfBlock.Numbered).items)
        assertEquals(listOf("Satu", "Dua"), (spec.blocks[1] as PdfBlock.Bullets).items)
    }
}
