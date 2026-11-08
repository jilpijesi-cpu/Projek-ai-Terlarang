package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Potongan jawaban: teks biasa atau blok berpagar (```). [closed] = false bila pagar penutup belum tiba (masih mengalir). */
internal sealed interface AnswerPart {
    data class Prose(val text: String) : AnswerPart
    data class Fence(val lang: String, val body: String, val closed: Boolean) : AnswerPart
}

/**
 * Memecah jawaban menjadi teks biasa dan blok berpagar. Pagar dikenali bila baris diawali ``` (boleh didahului spasi);
 * baris pembuka boleh memuat nama bahasa ("```salin"). Teks di luar blok dipertahankan apa adanya.
 */
internal fun splitAnswer(src: String): List<AnswerPart> {
    val out = ArrayList<AnswerPart>()
    val prose = StringBuilder()
    val body = StringBuilder()
    var lang = ""
    var inFence = false
    fun flushProse() {
        if (prose.isNotEmpty()) {
            out.add(AnswerPart.Prose(prose.toString().trimEnd('\n')))
            prose.clear()
        }
    }
    val lines = src.split('\n')
    for ((idx, raw) in lines.withIndex()) {
        val t = raw.trim()
        if (!inFence && t.startsWith("```")) {
            flushProse()
            inFence = true
            lang = t.removePrefix("```").trim().lowercase()
            body.clear()
            continue
        }
        if (inFence && t.startsWith("```")) {
            out.add(AnswerPart.Fence(lang, body.toString().trim('\n'), true))
            inFence = false
            body.clear()
            continue
        }
        val sink = if (inFence) body else prose
        sink.append(raw)
        if (idx < lines.lastIndex) sink.append('\n')
    }
    if (inFence) {
        val rawBody = body.toString().trim('\n').replace(Regex("\n`{1,2}\\z"), "")
        out.add(AnswerPart.Fence(lang, rawBody, false))
    } else flushProse()
    return out.filterNot { it is AnswerPart.Prose && it.text.isBlank() }
}

/** Teks polos untuk kotak salin (lihat [plainText] di CleanText.kt). */
internal fun stripMarkdown(src: String): String = plainText(src)

private val TEXT_LANGS = setOf("salin", "teks", "text", "txt", "surat", "pesan", "md", "markdown")

internal fun isTextFence(lang: String, closed: Boolean = true): Boolean =
    lang.isEmpty() || lang in TEXT_LANGS || (!closed && TEXT_LANGS.any { it.startsWith(lang) })

/** Judul kecil di kepala kotak: blok teks siap salin vs. kode. */
internal fun fenceTitle(lang: String, closed: Boolean = true): String =
    if (isTextFence(lang, closed)) "Teks siap salin" else lang

/** Teks biasa dipecah menjadi paragraf dan butir daftar, supaya butir yang melipat baris punya indentasi menggantung yang rapi. */
internal sealed interface ProseBlock {
    data class Para(val text: String) : ProseBlock
    /** [marker] = "•" atau nomor ("1.", "2)"); [level] = tingkat menjorok (0..3) dari spasi di depan baris. */
    data class Item(val marker: String, val body: String, val level: Int) : ProseBlock
    /** [level] 1..6 dari jumlah "#"; teks tanpa tanda "#". */
    data class Heading(val level: Int, val text: String) : ProseBlock
    /** Baris pertama = kepala tabel; baris pemisah |---| sudah dibuang. */
    data class Table(val rows: List<List<String>>) : ProseBlock
}

private val HEADING_RE = Regex("^\\s{0,3}(#{1,6})\\s+(.*\\S)\\s*$")
private val TABLE_SEP_RE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

private val ITEM_RE = Regex("^(\\s*)([-*•]|\\d{1,3}[.)])\\s+(.*)$")

internal fun splitProse(src: String): List<ProseBlock> {
    val out = ArrayList<ProseBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out.add(ProseBlock.Para(para.toString().trim('\n')))
        para.clear()
    }
    val lines = src.split('\n')
    var li = 0
    while (li < lines.size) {
        val raw = lines[li]
        li++
        val hm = HEADING_RE.find(raw)
        if (hm != null) {
            flush()
            out.add(ProseBlock.Heading(hm.groupValues[1].length, hm.groupValues[2]))
            continue
        }
        val tr = raw.trim()
        if (tr.startsWith("|") && tr.count { it == '|' } >= 2) {
            val rows = ArrayList<List<String>>()
            rows.add(tr.trim('|').split('|').map { it.trim() })
            while (li < lines.size && lines[li].trim().startsWith("|")) {
                val t2 = lines[li].trim()
                if (!TABLE_SEP_RE.matches(t2)) rows.add(t2.trim('|').split('|').map { it.trim() })
                li++
            }
            if (!TABLE_SEP_RE.matches(tr)) {
                flush()
                out.add(ProseBlock.Table(rows))
            }
            continue
        }
        val m = ITEM_RE.find(raw)
        if (m != null) {
            flush()
            val mark = m.groupValues[2]
            out.add(ProseBlock.Item(if (mark[0].isDigit()) mark else "•", m.groupValues[3], (m.groupValues[1].length / 2).coerceAtMost(3)))
        } else {
            para.append(raw.trimStart(' ', '\t')).append('\n')   // spasi depan tidak boleh membuat baris pertama menjorok sendiri
        }
    }
    flush()
    return out
}

/** Jawaban asisten dengan blok berpagar sebagai kotak berisi tombol "Salin". [shownProse] mengubah teks biasa menjadi AnnotatedString. */
@Composable
internal fun AnswerBody(
    text: String,
    style: TextStyle,
    shownProse: (String, Boolean) -> AnnotatedString,
) {
    val parts = remember(text) { splitAnswer(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { i, part ->
            when (part) {
                is AnswerPart.Prose -> {
                    val blocks = remember(part.text) { splitProse(part.text) }
                    Column {
                        blocks.forEachIndexed { bi, blk ->
                            val last = i == parts.lastIndex && bi == blocks.lastIndex
                            val top = if (bi == 0) 0.dp else if (blk is ProseBlock.Item && blocks[bi - 1] is ProseBlock.Item) 3.dp else 8.dp
                            when (blk) {
                                is ProseBlock.Para -> SelectionContainer {
                                    Text(shownProse(blk.text, last), style = style, modifier = Modifier.padding(top = top))
                                }
                                is ProseBlock.Heading -> {
                                    val size = when (blk.level) { 1 -> 24f; 2 -> 22f; 3 -> 18f; else -> 16f }
                                    SelectionContainer {
                                        Text(
                                            shownProse(blk.text, last),
                                            style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.3f).sp),
                                            modifier = Modifier.padding(top = if (bi == 0) 0.dp else 14.dp)
                                        )
                                    }
                                }
                                is ProseBlock.Table -> TableBox(blk.rows, Modifier.padding(top = top + 2.dp))
                                is ProseBlock.Item -> {
                                    val bullet = blk.marker == "•"
                                    // Terukur: teks butir mulai 17,7 dp dari tepi paragraf, daftar bernomor 32 dp.
                                    Row(Modifier.padding(top = top, start = ((if (bullet) 6 else 16) + blk.level * 16).dp)) {
                                        Text(blk.marker, style = style, modifier = Modifier.widthIn(min = if (bullet) 12.dp else 16.dp))
                                        SelectionContainer {
                                            Text(shownProse(blk.body, last), style = style, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is AnswerPart.Fence -> CopyBox(part, style)
            }
        }
    }
}

/** Tabel Markdown: sudut 12 dp, garis tipis, kepala berlatar abu, kolom sama lebar. */
@Composable
private fun TableBox(rows: List<List<String>>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val cols = rows.maxOf { it.size }
    val cell = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp, color = MythosColors.Ink)
    Column(modifier.fillMaxWidth().clip(shape).border(0.67.dp, MythosColors.Border, shape)) {
        rows.forEachIndexed { ri, row ->
            if (ri > 0) Box(Modifier.fillMaxWidth().height(0.67.dp).background(MythosColors.Border))
            Row(Modifier.fillMaxWidth().then(if (ri == 0) Modifier.background(MythosColors.Card) else Modifier)) {
                for (ci in 0 until cols) {
                    Text(
                        markdownToAnnotated(row.getOrElse(ci) { "" }),
                        style = if (ri == 0) cell.copy(fontWeight = FontWeight.SemiBold) else cell,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val d = density
        val st = Stroke(1.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, topLeft = Offset(6.5f * d, 6.5f * d), size = Size(9f * d, 9f * d), cornerRadius = CornerRadius(2.2f * d), style = st)
        drawRoundRect(color, topLeft = Offset(2.5f * d, 2.5f * d), size = Size(9f * d, 9f * d), cornerRadius = CornerRadius(2.2f * d), style = st)
    }
}

@Composable
private fun CopyBox(f: AnswerPart.Fence, base: TextStyle) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(f.body) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    val code = !isTextFence(f.lang, f.closed)
    val shown = if (code) f.body else stripMarkdown(f.body)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MythosColors.Card).border(1.dp, MythosColors.Border, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                fenceTitle(f.lang, f.closed).replaceFirstChar { it.uppercase() },
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = MythosColors.Hint)
            )
            if (f.closed && f.body.isNotBlank()) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Salin" }
                        .clickable {
                            clipboard.setText(AnnotatedString(shown))
                            copied = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (copied) Text("✓", style = TextStyle(fontSize = 15.sp, color = MythosColors.Hint)) else CopyGlyph(MythosColors.Hint)
                }
            }
        }
        SelectionContainer {
            Text(
                shown,
                style = if (code) base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, lineHeight = 19.sp)
                else base.copy(fontSize = 15.sp, lineHeight = 22.sp),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 14.dp)
            )
        }
    }
}
