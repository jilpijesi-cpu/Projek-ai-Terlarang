package com.mythos.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Menyusun beberapa gambar (mis. lampiran di uploads/) menjadi satu PDF, satu gambar per halaman, memakai PdfDocument bawaan Android.
 * Tanpa pustaka tambahan dan tanpa jaringan. Hasilnya ada di folder proyek, jadi bisa dibuka/dibagikan dari layar Berkas.
 */
class ImagesToPdf : Tool {
    override val name = "ImagesToPdf"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE
    override val timeoutMs: Long get() = 120_000L
    override val doc = """ImagesToPdf {"images": ["uploads/a.jpg", "uploads/b.png"], "output": "uploads/bukti.pdf"} — combine images (in the given order, one per A4 page, scaled to fit, orientation follows the image) into ONE PDF. Use it when the user asks to turn pictures/photos into a PDF (for example to upload to a help desk). Never fake a PDF with Write."""

    override fun describe(args: JSONObject): String {
        val n = args.optJSONArray("images")?.length() ?: 0
        return "ImagesToPdf $n gambar -> ${args.str("output") ?: "?"}"
    }

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val arr: JSONArray = args.optJSONArray("images") ?: throw ArgError("argumen 'images' wajib berupa daftar path gambar")
        val names = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return ToolResult(false, "No images given.")
        if (names.size > MAX_IMAGES) return ToolResult(false, "Too many images (${names.size}); at most $MAX_IMAGES per PDF. Split them into several PDFs.")
        val outRel = args.reqStr("output")
        if (!outRel.lowercase().endsWith(".pdf")) return ToolResult(false, "'output' must end with .pdf")
        val out = env.sandbox.resolve(outRel)
        if (out.isDirectory) return ToolResult(false, "${env.sandbox.relative(out)} is a directory.")

        val files = names.map { env.sandbox.resolve(it) }
        files.forEachIndexed { i, f -> if (!f.isFile) return ToolResult(false, "Image not found: ${names[i]}") }

        val doc = PdfDocument()
        try {
            for ((i, f) in files.withIndex()) {
                currentCoroutineContext().ensureActive()
                val bmp = decode(f) ?: return ToolResult(false, "Cannot read ${names[i]} as an image (is it really a picture?).")
                try {
                    addPage(doc, bmp, i + 1)
                } finally {
                    bmp.recycle()
                }
            }
            env.journal?.beforeChange(out)
            out.parentFile?.mkdirs()
            val existed = out.exists()
            out.outputStream().use { doc.writeTo(it) }
            val rel = env.sandbox.relative(out)
            env.session.known.remove(rel)
            val kb = (out.length() + 1023) / 1024
            return ToolResult(
                true,
                "${if (existed) "PDF replaced" else "PDF created"}: $rel (${files.size} pages, $kb KB). The chat shows a card with Open and Download/Share buttons for it, and it is also in the Files screen.",
                changed = listOf(rel),
                readPaths = listOf(rel),   // hasil dibuat langsung oleh alat ini dan ukurannya sudah dicek; tak perlu "dibaca ulang"
            )
        } finally {
            doc.close()
        }
    }

    /** Dibaca dengan sampling supaya foto besar tidak menghabiskan memori; sisi terpanjang maks. [MAX_SIDE_PX]. */
    private fun decode(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun addPage(doc: PdfDocument, bmp: Bitmap, number: Int) {
        val landscape = bmp.width > bmp.height
        val pw = if (landscape) A4_H else A4_W
        val ph = if (landscape) A4_W else A4_H
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, number).create())
        val c: Canvas = page.canvas
        c.drawColor(Color.WHITE)
        val availW = (pw - 2 * MARGIN).toFloat()
        val availH = (ph - 2 * MARGIN).toFloat()
        val scale = minOf(availW / bmp.width, availH / bmp.height)
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate((pw - bmp.width * scale) / 2f, (ph - bmp.height * scale) / 2f)
        c.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
    }

    private companion object {
        const val MAX_IMAGES = 30
        const val MAX_SIDE_PX = 1800
        const val A4_W = 595
        const val A4_H = 842
        const val MARGIN = 20
    }
}
