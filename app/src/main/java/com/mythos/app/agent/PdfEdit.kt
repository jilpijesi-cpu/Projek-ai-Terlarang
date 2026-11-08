package com.mythos.app.agent

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

/**
 * Menggabung, memisah, atau memberi watermark pada PDF yang sudah ada. Android tidak punya API untuk menyalin halaman PDF,
 * jadi setiap halaman dirender ke gambar lalu ditulis ulang: hasilnya tetap PDF sungguhan, tetapi teksnya tidak lagi bisa
 * dipilih/dicari dan ukurannya bisa lebih besar. Berkas asli tidak pernah ditimpa.
 */
class PdfEdit : Tool {
    override val name = "PdfEdit"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE
    override val timeoutMs: Long get() = 180_000L
    override val doc = """PdfEdit {"op":"merge","inputs":["uploads/a.pdf","uploads/b.pdf"],"output":"uploads/gabung.pdf"} | {"op":"split","input":"uploads/besar.pdf","parts":[{"pages":"1-3","output":"uploads/bagian1.pdf"},{"pages":"4,6-8","output":"uploads/bagian2.pdf"}]} | {"op":"watermark","input":"uploads/surat.pdf","output":"uploads/surat-wm.pdf","text":"Hanya untuk pengaduan Flip","pages":"all"} — work on EXISTING PDFs (to create a new one use MakePdf or ImagesToPdf). Originals are never overwritten; every output must be a different .pdf file. merge joins the PDFs in the given order (max 80 pages in total). split writes each part with the listed pages (1-based, ranges like "1-3,5" or "6-"). watermark stamps repeated diagonal text on the chosen pages (default all). Limitation to tell the user when it matters: pages are re-rendered as pictures, so the text in the result can no longer be selected or searched, and the file may be bigger. Encrypted or damaged PDFs fail with a clear message."""

    override fun describe(args: JSONObject): String = when (args.str("op")?.lowercase()) {
        "merge" -> "PdfEdit gabung ${args.optJSONArray("inputs")?.length() ?: 0} PDF -> ${args.str("output") ?: "?"}"
        "split" -> "PdfEdit pisah ${args.str("input") ?: "?"} (${args.optJSONArray("parts")?.length() ?: 0} bagian)"
        "watermark" -> "PdfEdit watermark ${args.str("input") ?: "?"} -> ${args.str("output") ?: "?"}"
        else -> "PdfEdit ${oneLine(args.toString(), 120)}"
    }

    private class Source(val file: File, val pfd: ParcelFileDescriptor, val renderer: PdfRenderer)

    private fun open(f: File): Source? = try {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try { Source(f, fd, PdfRenderer(fd)) } catch (e: Exception) { fd.close(); null }
    } catch (e: Exception) { null }

    private fun Source.close() { runCatching { renderer.close() }; runCatching { pfd.close() } }

    /** Satu halaman sumber -> satu halaman PDF baru (ukuran titik asli), dengan watermark bila [mark] tidak null. */
    private fun copyPage(src: PdfRenderer, index: Int, dst: PdfDocument, number: Int, mark: String?) {
        src.openPage(index).use { p ->
            val pw = p.width.coerceAtLeast(1); val ph = p.height.coerceAtLeast(1)
            val scale = (RENDER_W.toFloat() / pw).coerceAtMost(3f)
            val bw = (pw * scale).toInt().coerceAtLeast(1); val bh = (ph * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val page = dst.startPage(PdfDocument.PageInfo.Builder(pw, ph, number).create())
                page.canvas.drawBitmap(bmp, Rect(0, 0, bw, bh), Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                if (mark != null) drawWatermark(page.canvas, pw, ph, mark)
                dst.finishPage(page)
            } finally { bmp.recycle() }
        }
    }

    private fun checkOut(rel: String, env: ToolEnv, inputs: Set<File>): File {
        if (!rel.lowercase().endsWith(".pdf")) throw ArgError("output '$rel' harus berakhiran .pdf")
        val f = env.sandbox.resolve(rel)
        if (f in inputs) throw ArgError("output '$rel' sama dengan berkas masukan: berkas asli tidak boleh ditimpa")
        if (f.isDirectory) throw ArgError("$rel adalah folder")
        return f
    }

    private fun write(doc: PdfDocument, out: File, env: ToolEnv): String {
        env.journal?.beforeChange(out)
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        val rel = env.sandbox.relative(out)
        env.session.known.remove(rel)
        return rel
    }

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        return when (val op = args.reqStr("op").lowercase()) {
            "merge" -> merge(args, env)
            "split" -> split(args, env)
            "watermark" -> watermark(args, env)
            else -> throw ArgError("'op' harus merge, split atau watermark, bukan '$op'")
        }
    }

    private fun pdfInput(rel: String, env: ToolEnv): File {
        val f = env.sandbox.resolve(rel)
        if (!f.isFile) throw ArgError("PDF tidak ditemukan: $rel")
        return f
    }

    private suspend fun merge(args: JSONObject, env: ToolEnv): ToolResult {
        val names = args.stringList("inputs")
        if (names.size < 2) throw ArgError("'inputs' harus berisi minimal 2 PDF")
        val files = names.map { pdfInput(it, env) }
        val out = checkOut(args.reqStr("output"), env, files.toSet())
        val doc = PdfDocument()
        var number = 0
        try {
            for ((i, f) in files.withIndex()) {
                val s = open(f) ?: return ToolResult(false, "Tidak bisa membuka ${names[i]} sebagai PDF (rusak, terenkripsi, atau bukan PDF).")
                try {
                    if (number + s.renderer.pageCount > MAX_PAGES) return ToolResult(false, "Total halaman melebihi $MAX_PAGES. Bagi menjadi beberapa PDF.")
                    for (p in 0 until s.renderer.pageCount) { currentCoroutineContext().ensureActive(); copyPage(s.renderer, p, doc, ++number, null) }
                } finally { s.close() }
            }
            val rel = write(doc, out, env)
            return ToolResult(true, "PDF digabung: $rel ($number halaman dari ${files.size} berkas, ${(out.length() + 1023) / 1024} KB). Teks tidak lagi bisa dipilih karena halaman dirender ulang. Berkas asli tidak diubah.", changed = listOf(rel))
        } finally { doc.close() }
    }

    private suspend fun split(args: JSONObject, env: ToolEnv): ToolResult {
        val inF = pdfInput(args.reqStr("input"), env)
        val parts = args.optJSONArray("parts") ?: throw ArgError("'parts' wajib berupa daftar {pages, output}")
        if (parts.length() == 0 || parts.length() > 10) throw ArgError("'parts' harus berisi 1 sampai 10 bagian")
        val s = open(inF) ?: return ToolResult(false, "Tidak bisa membuka ${args.str("input")} sebagai PDF (rusak, terenkripsi, atau bukan PDF).")
        val changed = ArrayList<String>(); val lines = ArrayList<String>()
        try {
            val total = s.renderer.pageCount
            val plans = (0 until parts.length()).map { i ->
                val p = parts.optJSONObject(i) ?: throw ArgError("bagian ${i + 1} bukan objek")
                val pages = parsePageRanges(p.reqStr("pages"), total) ?: throw ArgError("bagian ${i + 1}: 'pages' tidak sah untuk PDF $total halaman")
                pages to checkOut(p.reqStr("output"), env, setOf(inF))
            }
            if (plans.map { it.second }.toSet().size != plans.size) throw ArgError("setiap bagian harus punya output berbeda")
            for ((pages, out) in plans) {
                val doc = PdfDocument()
                try {
                    for ((k, n) in pages.withIndex()) { currentCoroutineContext().ensureActive(); copyPage(s.renderer, n - 1, doc, k + 1, null) }
                    val rel = write(doc, out, env)
                    changed.add(rel); lines.add("$rel (${pages.size} halaman, ${(out.length() + 1023) / 1024} KB)")
                } finally { doc.close() }
            }
        } finally { s.close() }
        return ToolResult(true, "PDF dipisah dari ${args.str("input")}: ${lines.joinToString("; ")}. Teks tidak lagi bisa dipilih karena halaman dirender ulang. Berkas asli tidak diubah.", changed = changed)
    }

    private suspend fun watermark(args: JSONObject, env: ToolEnv): ToolResult {
        val inF = pdfInput(args.reqStr("input"), env)
        val out = checkOut(args.reqStr("output"), env, setOf(inF))
        val text = args.reqStr("text").trim()
        if (text.isEmpty() || text.length > 80) throw ArgError("'text' harus 1-80 karakter")
        val s = open(inF) ?: return ToolResult(false, "Tidak bisa membuka ${args.str("input")} sebagai PDF (rusak, terenkripsi, atau bukan PDF).")
        val doc = PdfDocument()
        try {
            val total = s.renderer.pageCount
            if (total > MAX_PAGES) return ToolResult(false, "PDF punya $total halaman; batasnya $MAX_PAGES. Pisahkan dulu dengan op split.")
            val marked = parsePageRanges(args.str("pages").orEmpty(), total)?.toSet() ?: throw ArgError("'pages' tidak sah untuk PDF $total halaman")
            for (p in 0 until total) { currentCoroutineContext().ensureActive(); copyPage(s.renderer, p, doc, p + 1, if ((p + 1) in marked) text else null) }
            val rel = write(doc, out, env)
            return ToolResult(true, "Watermark dipasang pada ${marked.size} dari $total halaman: $rel (${(out.length() + 1023) / 1024} KB). Teks tidak lagi bisa dipilih karena halaman dirender ulang. Berkas asli tidak diubah.", changed = listOf(rel))
        } finally { s.close(); doc.close() }
    }

    private companion object {
        const val RENDER_W = 1240
        const val MAX_PAGES = 80
    }
}
