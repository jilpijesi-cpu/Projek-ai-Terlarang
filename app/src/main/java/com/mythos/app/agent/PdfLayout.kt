package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject

/*
 * Logika murni (tanpa kelas Android) untuk alat MakePdf: membaca spesifikasi JSON, membungkus teks, dan menata blok ke halaman A4.
 * Pengukuran teks dan ukuran gambar diberikan lewat PdfMeasurer, jadi seluruh penataan bisa diuji di JVM biasa.
 */

/** Gaya teks dokumen. Ukuran dalam poin (1/72 inci). */
enum class PdfStyle(val size: Float, val bold: Boolean, val gray: Boolean, val before: Float, val after: Float) {
    TITLE(18f, true, false, 0f, 6f),
    SUBTITLE(10.5f, false, true, 0f, 10f),
    HEADING(13.5f, true, false, 10f, 4f),
    BODY(10.5f, false, false, 0f, 6f),
    SMALL(8.5f, false, true, 0f, 0f);

    val lineHeight: Float get() = size * 1.4f
}

sealed class PdfBlock {
    data class Text(val style: PdfStyle, val text: String) : PdfBlock()
    data class Numbered(val items: List<String>) : PdfBlock()
    data class Bullets(val items: List<String>) : PdfBlock()
    data class Fields(val rows: List<Pair<String, String>>) : PdfBlock()
    data class Image(val path: String) : PdfBlock()
    object Rule : PdfBlock()
    object PageBreak : PdfBlock()
}

class PdfSpec(val output: String, val footer: String, val blocks: List<PdfBlock>)

object PdfGeometry {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARGIN_X = 48f
    const val TOP = 52f
    const val BOTTOM = 782f
    const val FOOTER_BASELINE = 814f
    const val CONTENT_W = PAGE_W - 2 * MARGIN_X
    const val CONTENT_H = BOTTOM - TOP
}

interface PdfMeasurer {
    fun width(text: String, style: PdfStyle): Float

    /** Ukuran piksel gambar (lebar, tinggi), atau null bila tidak terbaca. */
    fun imageSize(path: String): Pair<Int, Int>?
}

sealed class PdfOp {
    data class Text(val x: Float, val baseline: Float, val text: String, val style: PdfStyle) : PdfOp()
    data class Line(val x1: Float, val x2: Float, val y: Float) : PdfOp()
    data class Img(val path: String, val x: Float, val y: Float, val w: Float, val h: Float) : PdfOp()
}

class PlannedPage(val ops: List<PdfOp>)

/** Membersihkan teks dari karakter yang tidak bisa digambar font bawaan (emoji, kontrol) dan dari penanda tebal markdown. */
internal fun cleanPdfText(s: String): String {
    val t = s.replace("\r\n", "\n").replace('\r', '\n').replace("**", "")
    val sb = StringBuilder(t.length)
    for (ch in t) {
        when {
            ch == '\n' -> sb.append(ch)
            ch == '\t' -> sb.append("    ")
            ch.isISOControl() -> {}
            Character.isSurrogate(ch) -> {}
            ch == '\u200d' || ch == '\ufe0f' -> {}
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

object PdfSpecParser {
    const val MAX_BLOCKS = 300
    const val MAX_IMAGES = 40
    const val MAX_ROWS = 60
    const val MAX_TOTAL_CHARS = 150_000

    fun parse(args: JSONObject): PdfSpec {
        val output = args.reqStr("output").trim()
        if (!output.lowercase().endsWith(".pdf")) throw ArgError("'output' must end with .pdf")
        val footer = cleanPdfText(args.str("footer") ?: "").replace('\n', ' ').trim().take(200)
        val arr: JSONArray = args.optJSONArray("blocks") ?: throw ArgError("argumen 'blocks' wajib berupa daftar blok")
        if (arr.length() == 0) throw ArgError("'blocks' is empty")
        if (arr.length() > MAX_BLOCKS) throw ArgError("Too many blocks (${arr.length()}); at most $MAX_BLOCKS.")

        var chars = 0
        var images = 0
        val blocks = ArrayList<PdfBlock>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw ArgError("blocks[$i] must be an object like {\"type\":\"text\",\"text\":\"...\"}")
            val type = (o.str("type") ?: "").trim().lowercase()
            when (type) {
                "title", "subtitle", "heading", "text", "paragraph" -> {
                    val text = cleanPdfText(o.str("text") ?: "").trim()
                    if (text.isEmpty()) throw ArgError("blocks[$i] ($type) needs a non-empty 'text'")
                    chars += text.length
                    val style = when (type) {
                        "title" -> PdfStyle.TITLE
                        "subtitle" -> PdfStyle.SUBTITLE
                        "heading" -> PdfStyle.HEADING
                        else -> PdfStyle.BODY
                    }
                    blocks.add(PdfBlock.Text(style, text))
                }
                "numbered", "bullets" -> {
                    val items = readItems(o.optJSONArray("items"), i, type)
                    chars += items.sumOf { it.length }
                    blocks.add(if (type == "numbered") PdfBlock.Numbered(items) else PdfBlock.Bullets(items))
                }
                "fields" -> {
                    val rows = readRows(o.optJSONArray("rows"), i)
                    chars += rows.sumOf { it.first.length + it.second.length }
                    blocks.add(PdfBlock.Fields(rows))
                }
                "image" -> {
                    val path = (o.str("path") ?: "").trim()
                    if (path.isEmpty()) throw ArgError("blocks[$i] (image) needs a 'path' such as uploads/foto.jpg")
                    images++
                    if (images > MAX_IMAGES) throw ArgError("Too many images ($images); at most $MAX_IMAGES per PDF. Split them into several PDFs.")
                    blocks.add(PdfBlock.Image(path))
                }
                "rule" -> blocks.add(PdfBlock.Rule)
                "pagebreak", "page_break", "newpage" -> blocks.add(PdfBlock.PageBreak)
                else -> throw ArgError("blocks[$i]: unknown type '$type'. Use title, subtitle, heading, text, numbered, bullets, fields, image, rule or pagebreak.")
            }
        }
        if (chars > MAX_TOTAL_CHARS) throw ArgError("Text is too long ($chars characters; at most $MAX_TOTAL_CHARS). Split it into several PDFs.")
        return PdfSpec(output, footer, blocks)
    }

    private fun readItems(a: JSONArray?, i: Int, type: String): List<String> {
        val items = ArrayList<String>()
        if (a != null) for (k in 0 until a.length()) {
            val t = stripListMarker(cleanPdfText(a.optString(k)).replace('\n', ' ').trim())
            if (t.isNotEmpty()) items.add(t)
        }
        if (items.isEmpty()) throw ArgError("blocks[$i] ($type) needs a non-empty 'items' list of strings")
        return items
    }

    private fun readRows(a: JSONArray?, i: Int): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        if (a != null) for (k in 0 until a.length()) {
            val r = a.opt(k)
            val pair = when (r) {
                is JSONArray -> r.optString(0) to r.optString(1)
                is JSONObject -> r.optString("label") to r.optString("value")
                else -> null
            } ?: continue
            val label = cleanPdfText(pair.first).replace('\n', ' ').trim()
            val value = cleanPdfText(pair.second).trim()
            if (label.isNotEmpty()) rows.add(label to value)
        }
        if (rows.isEmpty()) throw ArgError("blocks[$i] (fields) needs 'rows' such as [[\"Nama\",\"Agenia\"],[\"No. HP\",\"0812...\"]]")
        if (rows.size > MAX_ROWS) throw ArgError("blocks[$i] (fields) has too many rows (${rows.size}); at most $MAX_ROWS.")
        return rows
    }
}

private val LIST_MARKER = Regex("^(?:\\(?\\d{1,3}[.)]|[-\u2022*])\\s+")

/** Membuang penomoran/peluru yang sudah diketik model di awal item ("1. Foto KTP"), karena PdfPlanner menggambar penandanya sendiri. */
internal fun stripListMarker(s: String): String {
    var t = s
    // Maksimal dua lapis, mis. "1. 1. Foto KTP" atau "- 2) Foto".
    repeat(2) { t = LIST_MARKER.replace(t, "").trimStart() }
    return t
}

/** Memecah teks menjadi baris yang muat dalam [maxWidth]; "\n" dipertahankan (baris kosong = jeda paragraf). */
internal fun wrapLines(text: String, style: PdfStyle, maxWidth: Float, m: PdfMeasurer): List<String> {
    val out = ArrayList<String>()
    for (para in text.split('\n')) {
        if (para.isBlank()) {
            out.add("")
            continue
        }
        var line = ""
        for (word in para.trim().split(' ').filter { it.isNotEmpty() }) {
            var w = word
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (m.width(candidate, style) <= maxWidth) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) {
                out.add(line)
                line = ""
            }
            // Kata yang lebih lebar dari satu baris dipecah per huruf.
            while (m.width(w, style) > maxWidth && w.length > 1) {
                var n = w.length - 1
                while (n > 1 && m.width(w.substring(0, n), style) > maxWidth) n--
                out.add(w.substring(0, n))
                w = w.substring(n)
            }
            line = w
        }
        if (line.isNotEmpty()) out.add(line)
    }
    return out
}

/** Satu baris yang dipotong dengan "…" bila terlalu panjang. */
internal fun fitSingleLine(text: String, style: PdfStyle, maxWidth: Float, m: PdfMeasurer): String {
    val t = text.replace('\n', ' ').trim()
    if (m.width(t, style) <= maxWidth) return t
    var n = t.length
    while (n > 1 && m.width(t.substring(0, n) + "…", style) > maxWidth) n--
    return t.substring(0, n).trimEnd() + "…"
}

object PdfPlanner {
    private const val INDENT = 24f
    private const val IMAGE_GAP = 8f

    /**
     * Menata blok ke halaman A4. Teks mengalir antarhalaman per baris; gambar tidak pernah dipotong dan tidak diperbesar melebihi
     * ukuran aslinya. Judul bagian dijaga satu halaman dengan paragraf dan gambar yang langsung mengikutinya.
     * @throws IllegalArgumentException bila sebuah gambar tidak terbaca.
     */
    fun plan(blocks: List<PdfBlock>, m: PdfMeasurer): List<PlannedPage> {
        val pages = ArrayList<PlannedPage>()
        var ops = ArrayList<PdfOp>()
        var y = PdfGeometry.TOP
        val contentW = PdfGeometry.CONTENT_W
        val left = PdfGeometry.MARGIN_X

        fun newPage() {
            if (ops.isNotEmpty()) {
                pages.add(PlannedPage(ops))
                ops = ArrayList()
            }
            y = PdfGeometry.TOP
        }

        fun room(): Float = PdfGeometry.BOTTOM - y

        fun ensure(h: Float) {
            if (y + h > PdfGeometry.BOTTOM) newPage()
        }

        fun place(lines: List<String>, style: PdfStyle, x: Float, marker: String? = null, markerX: Float = x) {
            lines.forEachIndexed { i, line ->
                ensure(style.lineHeight)
                val base = y + style.size * 1.1f
                if (i == 0 && marker != null) ops.add(PdfOp.Text(markerX, base, marker, style))
                if (line.isNotEmpty()) ops.add(PdfOp.Text(x, base, line, style))
                y += style.lineHeight
            }
        }

        fun imageSizeOf(path: String): Pair<Int, Int> =
            m.imageSize(path) ?: throw IllegalArgumentException("Cannot read $path as an image (is it really a picture?).")

        fun imageMin(path: String): Float {
            val (w, h) = imageSizeOf(path)
            return minOf(h * minOf(1f, contentW / w), PdfGeometry.CONTENT_H * 0.35f)
        }

        fun textHeight(b: PdfBlock.Text): Float =
            b.style.before + wrapLines(b.text, b.style, contentW, m).size * b.style.lineHeight + b.style.after

        // Tinggi yang dibutuhkan agar judul tidak terpisah dari paragraf (dan gambar) yang langsung mengikutinya.
        fun headingNeed(i: Int, h: PdfBlock.Text): Float {
            var need = textHeight(h)
            var bodies = 0f
            var withImage = false
            var j = i + 1
            while (j < blocks.size) {
                val nb = blocks[j]
                if (nb is PdfBlock.Text && nb.style == PdfStyle.BODY) {
                    bodies += textHeight(nb)
                    j++
                } else {
                    if (nb is PdfBlock.Image) {
                        need += bodies + imageMin(nb.path)
                        withImage = true
                    }
                    break
                }
            }
            if (!withImage) need += minOf(bodies, 2 * PdfStyle.BODY.lineHeight)
            return minOf(need, PdfGeometry.CONTENT_H)
        }

        for ((i, b) in blocks.withIndex()) {
            when (b) {
                is PdfBlock.PageBreak -> newPage()
                is PdfBlock.Rule -> {
                    ensure(12f)
                    y += 4f
                    ops.add(PdfOp.Line(left, left + contentW, y))
                    y += 8f
                }
                is PdfBlock.Text -> {
                    if (b.style == PdfStyle.HEADING && y > PdfGeometry.TOP && room() < headingNeed(i, b)) newPage()
                    if (y > PdfGeometry.TOP) y += b.style.before
                    place(wrapLines(b.text, b.style, contentW, m), b.style, left)
                    y += b.style.after
                }
                is PdfBlock.Numbered -> {
                    b.items.forEachIndexed { k, item ->
                        place(wrapLines(item, PdfStyle.BODY, contentW - INDENT, m), PdfStyle.BODY, left + INDENT, "${k + 1}.", left)
                        y += 2f
                    }
                    y += 4f
                }
                is PdfBlock.Bullets -> {
                    for (item in b.items) {
                        place(wrapLines(item, PdfStyle.BODY, contentW - INDENT, m), PdfStyle.BODY, left + INDENT, "\u2022", left + 6f)
                        y += 2f
                    }
                    y += 4f
                }
                is PdfBlock.Fields -> {
                    val maxLabel = b.rows.maxOfOrNull { m.width(it.first, PdfStyle.BODY) } ?: 0f
                    val col = minOf(maxLabel + 16f, contentW * 0.4f)
                    for ((label, value) in b.rows) {
                        val valueLines = wrapLines(value, PdfStyle.BODY, contentW - col, m).ifEmpty { listOf("") }
                        ensure(PdfStyle.BODY.lineHeight)
                        val base = y + PdfStyle.BODY.size * 1.1f
                        ops.add(PdfOp.Text(left, base, fitSingleLine(label, PdfStyle.BODY, col - 14f, m), PdfStyle.BODY))
                        ops.add(PdfOp.Text(left + col - 10f, base, ":", PdfStyle.BODY))
                        place(valueLines, PdfStyle.BODY, left + col)
                    }
                    y += 6f
                }
                is PdfBlock.Image -> {
                    val (pw, ph) = imageSizeOf(b.path)
                    if (room() < imageMin(b.path)) newPage()
                    val scale = minOf(contentW / pw, room() / ph, 1f)
                    val w = pw * scale
                    val h = ph * scale
                    ops.add(PdfOp.Img(b.path, left + (contentW - w) / 2f, y, w, h))
                    y += h + IMAGE_GAP
                }
            }
        }
        newPage()
        return pages
    }
}
