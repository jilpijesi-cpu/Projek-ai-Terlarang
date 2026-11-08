package com.mythos.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

/**
 * Membuat dokumen PDF A4 dari teks dan gambar (surat, pengaduan, laporan, berkas bukti dengan lampiran), memakai PdfDocument bawaan Android.
 * Penataan ada di PdfLayout.kt (murni, teruji); berkas ini hanya mengukur, menggambar, dan menulis hasilnya ke folder proyek.
 */
class MakePdf : Tool {
    override val name = "MakePdf"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE
    override val timeoutMs: Long get() = 180_000L
    override val doc = """MakePdf {"output": "uploads/surat.pdf", "footer": "Bukti pendukung - Agenia - Tiket 123", "blocks": [{"type":"title","text":"Judul"}, {"type":"subtitle","text":"Tanggal / nomor tiket"}, {"type":"fields","rows":[["Nama","Agenia"],["No. HP","0812..."]]}, {"type":"heading","text":"Kronologi"}, {"type":"numbered","items":["...","..."]}, {"type":"text","text":"Paragraf."}, {"type":"pagebreak"}, {"type":"heading","text":"Lampiran 1: KTP"}, {"type":"text","text":"Satu-dua kalimat tentang gambar."}, {"type":"image","path":"uploads/ktp.jpg"}]} — create ONE real A4 PDF from text and images. Block types: title, subtitle, heading, text (a blank line makes a paragraph break), numbered and bullets (field "items": list of strings; do NOT type the number or bullet yourself, the PDF adds it), fields (field "rows": list of [label, value]), image (field "path": a picture inside the project, scaled to fit and never cropped), rule (thin line), pagebreak. Text flows over pages automatically; a heading stays on the same page as the paragraph and image right after it. The footer (optional) and page numbers "Hal. 1/N" are added to every page automatically. Plain text only: no emoji, no markdown. The result lists the page of every image, so you can check page numbers you mention in a list of attachments. Use ImagesToPdf only when the user wants pictures alone; use MakePdf whenever text is needed. Never fake a PDF with Write."""

    override fun describe(args: JSONObject): String {
        val n = args.optJSONArray("blocks")?.length() ?: 0
        return "MakePdf $n blok -> ${args.str("output") ?: "?"}"
    }

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val spec = PdfSpecParser.parse(args)
        val out = env.sandbox.resolve(spec.output)
        if (out.isDirectory) return ToolResult(false, "${env.sandbox.relative(out)} is a directory.")

        // Semua gambar diperiksa lebih dulu agar kegagalan muncul sebelum ada yang ditulis.
        val files = HashMap<String, File>()
        val sizes = HashMap<String, Pair<Int, Int>>()
        for (b in spec.blocks) {
            if (b !is PdfBlock.Image || files.containsKey(b.path)) continue
            val f = env.sandbox.resolve(b.path)
            if (!f.isFile) return ToolResult(false, "Image not found: ${b.path}")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return ToolResult(false, "Cannot read ${b.path} as an image (is it really a picture?).")
            }
            files[b.path] = f
            sizes[b.path] = bounds.outWidth to bounds.outHeight
        }

        val paints = HashMap<PdfStyle, Paint>()
        fun paintFor(style: PdfStyle): Paint = paints.getOrPut(style) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.size
                typeface = Typeface.create(Typeface.SANS_SERIF, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
                color = when {
                    style.gray -> Color.rgb(100, 100, 100)
                    style == PdfStyle.TITLE || style == PdfStyle.HEADING -> Color.rgb(31, 58, 110) // biru tua seperti contoh dokumen verifikasi
                    else -> Color.rgb(20, 20, 20)
                }
            }
        }
        val measurer = object : PdfMeasurer {
            override fun width(text: String, style: PdfStyle): Float = paintFor(style).measureText(text)
            override fun imageSize(path: String): Pair<Int, Int>? = sizes[path]
        }

        val pages = try {
            PdfPlanner.plan(spec.blocks, measurer)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Cannot lay out the document.")
        }
        if (pages.isEmpty()) return ToolResult(false, "The document is empty.")
        if (pages.size > MAX_PAGES) return ToolResult(false, "The document would have ${pages.size} pages; at most $MAX_PAGES. Split it into several PDFs.")

        val total = pages.size
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 190, 190)
            strokeWidth = 0.7f
        }
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val doc = PdfDocument()
        try {
            for ((index, page) in pages.withIndex()) {
                currentCoroutineContext().ensureActive()
                val pdfPage = doc.startPage(
                    PdfDocument.PageInfo.Builder(PdfGeometry.PAGE_W.toInt(), PdfGeometry.PAGE_H.toInt(), index + 1).create()
                )
                val c: Canvas = pdfPage.canvas
                c.drawColor(Color.WHITE)
                for (op in page.ops) {
                    when (op) {
                        is PdfOp.Text -> c.drawText(op.text, op.x, op.baseline, paintFor(op.style))
                        is PdfOp.Line -> c.drawLine(op.x1, op.y, op.x2, op.y, linePaint)
                        is PdfOp.Img -> {
                            val f = files[op.path] ?: continue
                            val bmp = loadScaled(f, (op.w * PX_PER_PT).toInt().coerceAtLeast(1)) ?: continue
                            try {
                                c.drawBitmap(bmp, null, RectF(op.x, op.y, op.x + op.w, op.y + op.h), bitmapPaint)
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                }
                drawFooter(c, spec.footer, index + 1, total, ::paintFor, measurer)
                doc.finishPage(pdfPage)
            }
            env.journal?.beforeChange(out)
            out.parentFile?.mkdirs()
            val existed = out.exists()
            out.outputStream().use { doc.writeTo(it) }
            val rel = env.sandbox.relative(out)
            env.session.known.remove(rel)
            val kb = (out.length() + 1023) / 1024
            val where = imagePages(pages)
            return ToolResult(
                true,
                "${if (existed) "PDF replaced" else "PDF created"}: $rel ($total pages, $kb KB). " +
                    (if (where.isNotEmpty()) "Images by page: $where. " else "") +
                    "The chat shows a card with Open and Download/Share buttons for it, and it is also in the Files screen.",
                changed = listOf(rel),
                readPaths = listOf(rel),
            )
        } finally {
            doc.close()
        }
    }

    private fun drawFooter(
        c: Canvas,
        footer: String,
        number: Int,
        total: Int,
        paintFor: (PdfStyle) -> Paint,
        m: PdfMeasurer,
    ) {
        val paint = paintFor(PdfStyle.SMALL)
        val label = "Hal. $number/$total"
        val labelW = paint.measureText(label)
        c.drawText(label, PdfGeometry.PAGE_W - PdfGeometry.MARGIN_X - labelW, PdfGeometry.FOOTER_BASELINE, paint)
        if (footer.isNotEmpty()) {
            val room = PdfGeometry.CONTENT_W - 2 * (labelW + 16f) // simetris agar teks footer tetap di tengah halaman
            val text = fitSingleLine(footer, PdfStyle.SMALL, room, m)
            c.drawText(text, (PdfGeometry.PAGE_W - paint.measureText(text)) / 2f, PdfGeometry.FOOTER_BASELINE, paint)
        }
    }

    /** "uploads/a.jpg → hal. 2, uploads/b.jpg → hal. 3" (maks. 40 gambar per PDF). */
    private fun imagePages(pages: List<PlannedPage>): String {
        val parts = ArrayList<String>()
        for ((i, p) in pages.withIndex()) for (op in p.ops) if (op is PdfOp.Img) parts.add("${op.path} -> p.${i + 1}")
        return parts.joinToString(", ")
    }

    /** Dibaca dengan sampling lalu diperkecil ke piksel yang benar-benar dipakai halaman, supaya PDF tidak membengkak. */
    private fun loadScaled(f: File, targetW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (src.width <= targetW) return src
        val th = (src.height.toLong() * targetW / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetW, th, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private companion object {
        const val MAX_PAGES = 60
        /** Piksel per poin halaman (2 = 144 dpi): cukup tajam untuk teks kecil pada tangkapan layar tanpa PDF raksasa. */
        const val PX_PER_PT = 2f
    }
}
