package com.mythos.app.agent

// Fungsi murni (tanpa Android) untuk EditImage dan PdfEdit, supaya bisa diuji di JVM.

/** Persegi dalam piksel: kiri, atas, lebar, tinggi. */
internal class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Wilayah pecahan (0..1) dari gambar -> piksel, dipotong ke batas gambar; null bila kosong atau di luar gambar. */
internal fun toPixelRect(fx: Double, fy: Double, fw: Double, fh: Double, imgW: Int, imgH: Int): PxRect? {
    if (imgW <= 0 || imgH <= 0 || fx.isNaN() || fy.isNaN() || fw.isNaN() || fh.isNaN()) return null
    val x0 = (fx.coerceIn(0.0, 1.0) * imgW).toInt()
    val y0 = (fy.coerceIn(0.0, 1.0) * imgH).toInt()
    val x1 = ((fx + fw).coerceIn(0.0, 1.0) * imgW).toInt()
    val y1 = ((fy + fh).coerceIn(0.0, 1.0) * imgH).toInt()
    return if (x1 - x0 >= 1 && y1 - y0 >= 1) PxRect(x0, y0, x1 - x0, y1 - y0) else null
}

/** Piksel-kan [r] pada array ARGB selebar [imgW]: tiap blok [block] x [block] diganti rata-ratanya. Hanya menyentuh isi [r]. */
internal fun pixelate(px: IntArray, imgW: Int, r: PxRect, block: Int) {
    val b = block.coerceAtLeast(2)
    var by = r.y
    while (by < r.y + r.h) {
        val bh = minOf(b, r.y + r.h - by)
        var bx = r.x
        while (bx < r.x + r.w) {
            val bw = minOf(b, r.x + r.w - bx)
            var a = 0L; var rr = 0L; var g = 0L; var bl = 0L
            for (yy in by until by + bh) for (xx in bx until bx + bw) {
                val c = px[yy * imgW + xx]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255
            }
            val n = (bw * bh).toLong()
            val avg = ((a / n).toInt() shl 24) or ((rr / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
            for (yy in by until by + bh) for (xx in bx until bx + bw) px[yy * imgW + xx] = avg
            bx += bw
        }
        by += bh
    }
}

/**
 * "1-3,5,8-" -> [1,2,3,5,8,...total]. Kosong atau "all" = semua halaman. Null bila ada bagian yang tidak sah
 * atau tidak ada halaman yang cocok. Urutan dipertahankan; duplikat dibuang.
 */
internal fun parsePageRanges(spec: String, total: Int): List<Int>? {
    if (total <= 0) return null
    val s = spec.trim().lowercase()
    if (s.isEmpty() || s == "all" || s == "semua") return (1..total).toList()
    val out = LinkedHashSet<Int>()
    for (part in s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val m = Regex("^(\\d+)?\\s*-\\s*(\\d+)?$").matchEntire(part)
        if (m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
            val a = m.groupValues[1].ifEmpty { "1" }.toInt()
            val b = m.groupValues[2].ifEmpty { total.toString() }.toInt()
            if (a < 1 || b < a || b > total) return null
            for (n in a..b) out.add(n)
        } else {
            val n = part.toIntOrNull() ?: return null
            if (n !in 1..total) return null
            out.add(n)
        }
    }
    return out.toList().ifEmpty { null }
}
