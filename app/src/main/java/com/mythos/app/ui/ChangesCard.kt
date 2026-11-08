package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.agent.ChangeKind
import com.mythos.app.agent.FileChange

private val AddColor = Color(0xFF1B7F3B)
private val RemoveColor = Color(0xFFB3261E)

private fun monoStyle(color: Color, bold: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = color,
)

/**
 * Ringkasan perubahan file satu tugas: ketuk untuk melihat daftar file, ketuk file untuk melihat diff-nya, dan tombol
 * "Batalkan tugas ini" mengembalikan semua file ke keadaan sebelum tugas (file yang diubah lagi sesudahnya dilewati).
 */
@Composable
internal fun ChangesCard(
    item: ChangesItem,
    canUndo: Boolean,
    onUndo: () -> Unit,
    loadDiff: suspend (String) -> String,
    onFile: (String, Boolean) -> Unit = { _, _ -> },
) {
    var open by remember { mutableStateOf(false) }
    val added = item.files.sumOf { it.added }
    val removed = item.files.sumOf { it.removed }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(MythosColors.Card)
            .border(1.dp, MythosColors.Border, shape)
            .padding(12.dp)
    ) {
        if (!item.undone) {
            item.files.filter { it.kind != ChangeKind.DELETED && it.path.lowercase().endsWith(".pdf") }.forEach { f ->
                PdfFileCard(f.path, onOpen = { onFile(f.path, false) }, onShare = { onFile(f.path, true) })
                Spacer(Modifier.height(10.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Sembunyikan perubahan" else "Lihat perubahan", role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (item.undone) "Dibatalkan · " else "") + "${item.files.size} file diubah",
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MythosColors.Ink),
                modifier = Modifier.weight(1f)
            )
            Text("+$added", style = monoStyle(AddColor, bold = true))
            Spacer(Modifier.width(8.dp))
            Text("−$removed", style = monoStyle(RemoveColor, bold = true))
            Spacer(Modifier.width(10.dp))
            ChevronGlyph(if (open) 90f else 0f)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            item.files.forEach { f -> FileChangeRow(f, loadDiff) }
        }
        if (!item.undone) {
            Spacer(Modifier.height(10.dp))
            val buttonShape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .clip(buttonShape)
                    .border(1.dp, if (canUndo) MythosColors.Ink else MythosColors.Border, buttonShape)
                    .clickable(enabled = canUndo, onClickLabel = "Batalkan perubahan tugas ini", role = Role.Button, onClick = onUndo)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "Batalkan tugas ini",
                    style = TextStyle(
                        fontFamily = MythosSans, fontSize = 13.sp,
                        color = if (canUndo) MythosColors.Ink else MythosColors.Hint
                    )
                )
            }
        }
    }
}

@Composable
private fun FileChangeRow(f: FileChange, loadDiff: suspend (String) -> String) {
    var open by remember(f.path) { mutableStateOf(false) }
    var diff by remember(f.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (open && diff == null) diff = loadDiff(f.path)
    }
    val mark = when (f.kind) {
        ChangeKind.ADDED -> "A"
        ChangeKind.MODIFIED -> "M"
        ChangeKind.DELETED -> "D"
    }
    val markColor = when (f.kind) {
        ChangeKind.ADDED -> AddColor
        ChangeKind.MODIFIED -> MythosColors.Hint
        ChangeKind.DELETED -> RemoveColor
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Lihat perubahan ${f.path}", role = Role.Button) { open = !open }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(mark, style = monoStyle(markColor, bold = true))
            Spacer(Modifier.width(8.dp))
            Text(
                f.path,
                style = monoStyle(MythosColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!f.binary) {
                Spacer(Modifier.width(8.dp))
                Text("+${f.added} −${f.removed}", style = monoStyle(MythosColors.Hint))
            }
        }
        if (open) DiffBlock(diff)
    }
}

@Composable
private fun DiffBlock(text: String?) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MythosColors.Background)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        if (text == null) {
            Text("Memuat…", style = monoStyle(MythosColors.Hint))
        } else {
            Column {
                text.lines().forEach { line ->
                    val color = when {
                        line.startsWith("+") -> AddColor
                        line.startsWith("-") -> RemoveColor
                        line.startsWith("@@") -> MythosColors.Hint
                        else -> MythosColors.Ink
                    }
                    Text(line.ifEmpty { " " }, style = monoStyle(color), softWrap = false)
                }
            }
        }
    }
}
