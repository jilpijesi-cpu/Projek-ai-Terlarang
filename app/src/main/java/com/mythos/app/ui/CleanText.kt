package com.mythos.app.ui

/**
 * Pintu tunggal pembersih teks dari model sebelum tampil di layar. Tujuannya satu: pengguna tidak pernah melihat
 * teks mentah (simbol Markdown, tag alat, JSON panggilan alat, penanda yang belum lengkap saat streaming).
 *
 *  - [cleanForDisplay]: balasan/narasi multi-baris (pagar ``` dipertahankan untuk splitAnswer, isinya tidak disentuh).
 *  - [cleanLine]: label satu baris (baris aktivitas, judul langkah).
 *  - [plainText]: teks polos penuh (kotak "Teks siap salin" dan isi yang disalin).
 */

private val LEAK_BLOCK = Regex("""(?<!`)<(tool_use|tool_result|tool_results|tool_call|function_calls?|thinking|think)>[\s\S]*?(?:</\1>|\z)""")
private val LEAK_STRAY = Regex("""(?<!`)</?(?:tool_use|tool_result|tool_results|tool_call|function_calls?|thinking|think)>(?!`)""")
private val LEAK_PARTIAL_TAIL = Regex("""<(?:\|[A-Za-z_]*|/?(?:tool|func|arg|think|invoke|dots)[A-Za-z0-9_|]*|/?[A-Za-z]{0,2})\z""")
private val BARE_CALL_LINE = Regex("""(?m)^[ \t]*\{[ \t]*"(?:status|name|tool)"[ \t]*:.*"(?:input|args|arguments|parameters)"[ \t]*:.*\}[ \t]*(?:\r?\n|\z)""")
private val BR_TAG = Regex("""(?i)<br\s*/?>""")

private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val HR = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val QUOTE = Regex("""^\s{0,3}>+\s?""")

private val IMG = Regex("""!\[([^\]\n]*)]\([^)\n]*\)""")
private val LINK = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
private val STRIKE = Regex("""~~(.+?)~~""")
private val UNDER = Regex("""(?<![\p{L}\p{N}_])__(.+?)__(?![\p{L}\p{N}_.])""")
private val TRIPLE = Regex("""\*\*\*(.+?)\*\*\*""")
private val ITALIC = Regex("""(?<![\p{L}\p{N}*])\*(?![\s./])([^*\n]+?)(?<![\s*])\*(?![\p{L}\p{N}*])""")

private val PARTIAL_LINK_URL = Regex("""\[([^\]\n]+)]\([^)\n]*\z""")
private val PARTIAL_LINK_TEXT = Regex("""\[([^\]\n]*)\z""")
private val PARTIAL_ITALIC = Regex("""(?<![\p{L}\p{N}*])\*(?![\s*./])([^*]*)\z""")
private val PARTIAL_TAIL_MARKS = Regex("""[ \t]*(?:~+|__+)\z""")

private val SPECIAL_TOKEN = Regex("""(?<!`)</?\|[A-Za-z_]+\|>""")
private val FAKE_RESULT_LINE = Regex("""(?m)^[ \t]*(?:<tool_results?[ >\r\n]|<\|[A-Za-z_]+\|>[ \t]*tool_results?\b)""")

/**
 * Token khusus palsu (<|start_header_id|>, <|start_code|>, ...) dan hasil alat karangan model bukan teks untuk pengguna.
 * Dua token atau lebih = markup, dibuang bersama semua sesudahnya (sama dengan Protocol.garbleCut); satu token = sebutan biasa, dibiarkan.
 */
private fun cutSpecialMarkup(src: String): String {
    if (!src.contains('<')) return src
    var cut = -1
    val tokens = SPECIAL_TOKEN.findAll(src).toList()
    if (tokens.size >= 2) cut = tokens[0].range.first
    FAKE_RESULT_LINE.find(src)?.let { m ->
        val at = m.range.first + m.value.length - m.value.trimStart().length
        if (cut < 0 || at < cut) cut = at
    }
    return if (cut < 0) src else src.substring(0, cut)
}

/** Buang blok alat/pikiran yang bocor (juga yang belum tertutup saat mengalir), tag sisa, token khusus palsu, dan JSON panggilan alat polos. */
internal fun stripLeaks(srcIn: String): String {
    if (!srcIn.contains('<') && !srcIn.contains('{')) return srcIn
    val src = cutSpecialMarkup(srcIn)
    return src.replace(LEAK_BLOCK, "").replace(LEAK_STRAY, "").replace(BARE_CALL_LINE, "").replace(LEAK_PARTIAL_TAIL, "")
}

/** Membagi teks menjadi bagian di luar pagar (diproses [f]) dan bagian berpagar (dibiarkan apa adanya). */
private fun mapOutsideFences(src: String, f: (String) -> String): String {
    val out = StringBuilder()
    val chunk = StringBuilder()
    var inFence = false
    val lines = src.split('\n')
    for ((i, raw) in lines.withIndex()) {
        val sep = if (i < lines.lastIndex) "\n" else ""
        if (raw.trim().startsWith("```")) {
            if (!inFence) {
                out.append(f(chunk.toString()))
                chunk.clear()
            }
            inFence = !inFence
            out.append(raw).append(sep)
        } else if (inFence) {
            out.append(raw).append(sep)
        } else {
            chunk.append(raw).append(sep)
        }
    }
    out.append(f(chunk.toString()))
    return out.toString()
}

private fun endsInsideFence(src: String): Boolean = src.lines().count { it.trim().startsWith("```") } % 2 == 1

/** Tabel, garis pemisah, kutipan dan <br> menjadi teks biasa per baris; baris pemisah dibuang. */
private fun normalizeBlocks(src: String, keepTables: Boolean = false): String {
    val out = ArrayList<String>()
    for (raw in src.replace(BR_TAG, "\n").split('\n')) {
        var line = raw
        val t = line.trim()
        if (keepTables && t.startsWith("|")) {   // dirender sebagai tabel oleh AnswerBody
            out.add(t)
            continue
        }
        if (t.contains('|') && TABLE_SEP.matches(line)) continue
        if (HR.matches(line)) continue
        if (t.length > 2 && t.startsWith("|") && t.endsWith("|")) {
            line = t.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("  ·  ")
        }
        line = line.replace(QUOTE, "")
        out.add(line)
    }
    return out.joinToString("\n")
}

private fun inlineOne(s: String): String =
    s.replace(IMG, "$1").replace(LINK, "$1 ($2)").replace(STRIKE, "$1").replace(TRIPLE, "**$1**")
        .replace(UNDER, "**$1**").replace(ITALIC, "$1")

/** Sintaks inline selain **tebal** dan `kode` diratakan; isi `kode` tidak disentuh (mis. `__init__.py`). */
private fun normalizeInline(line: String): String {
    if (!line.contains('*') && !line.contains('_') && !line.contains('~') && !line.contains('[')) return line
    val parts = line.split('`')
    return parts.mapIndexed { i, p -> if (i % 2 == 0) inlineOne(p) else p }.joinToString("`")
}

/** Penanda yang belum lengkap di ujung teks yang sedang mengalir tidak ditampilkan sampai pasangannya datang. */
private fun dropPartialTail(src: String): String {
    if (src.isEmpty() || endsInsideFence(src)) return src
    val nl = src.lastIndexOf('\n') + 1
    var last = src.substring(nl)
    if (last.trim().startsWith("```")) return src
    last = last.replace(PARTIAL_LINK_URL, "$1").replace(PARTIAL_LINK_TEXT, "$1").replace(PARTIAL_ITALIC, "$1")
    last = last.replace(PARTIAL_TAIL_MARKS, "")
    if (last.count { it == '`' } % 2 == 1) last = last.trimEnd('`')
    return src.substring(0, nl) + last
}

/** Teks balasan/narasi untuk layar. Pagar ``` dipertahankan; renderer menangani **tebal**, `kode`, # judul, daftar. */
internal fun cleanForDisplay(src: String, keepTables: Boolean = false): String {
    if (src.isEmpty()) return src
    val body = mapOutsideFences(src) { chunk ->
        normalizeBlocks(stripLeaks(chunk), keepTables).split('\n').joinToString("\n") { normalizeInline(it) }
    }
    return dropPartialTail(body).replace(Regex("\n{3,}"), "\n\n")
}

/** Label satu baris: semua simbol Markdown dan kebocoran teknis dibuang, spasi dipadatkan. */
internal fun cleanLine(src: String): String {
    if (src.isEmpty()) return src
    var t = stripLeaks(src).replace('\n', ' ')
    t = t.replace(IMG, "$1").replace(LINK, "$1").replace(STRIKE, "$1")
        .replace(UNDER, "$1").replace(Regex("""\*\*\*?"""), "")
        .replace(ITALIC, "$1").replace("`", "")
        .replace(Regex("""^\s*(#{1,6}|>+|[-•])\s+"""), "")
    return t.replace(Regex("""\s+"""), " ").trim()
}

/** Teks polos penuh untuk kotak salin: tanpa simbol Markdown apa pun. */
internal fun plainText(src: String): String {
    var t = normalizeBlocks(stripLeaks(src)).split('\n').joinToString("\n") { normalizeInline(it) }
    t = t.replace("**", "")
    t = t.replace(Regex("""`([^`\n]+)`"""), "$1")
    t = t.replace(Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+"""), "")
    t = t.replace(Regex("""(?m)^([ \t]*)[-*\u2022][ \t]+"""), "$1")
    return t.replace(Regex("\n{3,}"), "\n\n")
}
