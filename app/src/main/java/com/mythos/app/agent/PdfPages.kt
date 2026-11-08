package com.mythos.app.agent

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Merender halaman PDF menjadi gambar untuk model (PdfRenderer bawaan Android). Dipakai ViewImages agar PDF dari pesan
 * sebelumnya, atau halaman setelah 6 yang pertama, tetap bisa dilihat ulang.
 */
object PdfPages {
    const val RENDER_WIDTH_PX = 1400

    /** Halaman hasil render: [number] berbasis 1, dari total [pageCount] halaman. */
    class Rendered(val pageCount: Int, val pages: List<Pair<Int, ImagePart>>)

    /** Null bila berkas tidak bisa dibuka sebagai PDF (rusak, terenkripsi, bukan PDF). */
    fun render(file: File, requested: List<Int>?, limit: Int): Rendered? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val out = ArrayList<Pair<Int, ImagePart>>()
                    for (n in pickPdfPages(requested, r.pageCount, limit)) {
                        r.openPage(n - 1).use { p ->
                            val w = RENDER_WIDTH_PX
                            val h = (w.toFloat() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(1, RENDER_WIDTH_PX * 3)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val bos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                                out.add(n to ImagePart("image/jpeg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                    Rendered(r.pageCount, out)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Nomor halaman PDF (berbasis 1) yang akan dirender: tanpa permintaan = halaman pertama; nomor di luar rentang dan duplikat
 * dibuang, urutan permintaan dipertahankan, paling banyak [limit].
 */
internal fun pickPdfPages(requested: List<Int>?, total: Int, limit: Int): List<Int> {
    if (total <= 0 || limit <= 0) return emptyList()
    if (requested.isNullOrEmpty()) return (1..minOf(total, limit)).toList()
    return requested.filter { it in 1..total }.distinct().take(limit)
}
