package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val PdfTint = Color(0xFF1F5FA8)
private val PdfBg = Color(0xFFE3EEFB)

/** "uploads/Bukti_Pengaduan_OJK.pdf" -> "Bukti Pengaduan OJK". */
internal fun prettyFileName(path: String): String =
    path.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF").replace('_', ' ').trim().ifEmpty { path.substringAfterLast('/') }

/** Teks seluruh draf untuk tombol salin. */
internal fun emailDraftText(d: EmailDraftItem): String = buildString {
    append("Kepada: ").append(d.to.joinToString())
    if (d.cc.isNotEmpty()) append("\nCc: ").append(d.cc.joinToString())
    append("\nSubjek: ").append(d.subject).append("\n\n").append(d.body)
}

/** Kartu berkas PDF: ikon, nama, "Dokumen · PDF", dan tombol bagikan; ketuk kartunya untuk membuka. */
@Composable
internal fun PdfFileCard(path: String, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val title = prettyFileName(path)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MythosColors.Background)
            .border(1.dp, MythosColors.Border, shape)
            .clickable(onClickLabel = "Buka $title", role = Role.Button, onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(PdfBg),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = PdfTint) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MythosColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text("Dokumen · PDF", style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Hint))
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Unduh atau bagikan $title", role = Role.Button, onClick = onShare),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Share, contentDescription = null, tint = MythosColors.Ink) }
    }
}

/**
 * Kartu draf email di chat: Kepada/Cc, Subjek, Isi, lampiran PDF, dan tombol "Kirim email". Tombol hanya MEMBUKA aplikasi email dengan
 * draf ini; email baru terkirim bila pengguna menekan Kirim di sana.
 */
@Composable
internal fun EmailDraftCard(item: EmailDraftItem, onOpenPath: (String, Boolean) -> Unit, onSend: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(MythosColors.Background)
            .border(1.dp, MythosColors.Border, shape)
    ) {
        // Kepala
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Email, contentDescription = null, tint = MythosColors.Ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Email", style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MythosColors.Ink), modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Salin draf email", role = Role.Button) {
                    clipboard.setText(AnnotatedString(emailDraftText(item)))
                    copied = true
                },
                contentAlignment = Alignment.Center
            ) { Icon(if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy, contentDescription = null, tint = MythosColors.Ink, modifier = Modifier.size(20.dp)) }
        }
        EmailDivider()
        SelectionContainer {
            Column(Modifier.fillMaxWidth()) {
                EmailSection("Kepada", item.to.joinToString())
                if (item.cc.isNotEmpty()) {
                    EmailDivider()
                    EmailSection("Cc", item.cc.joinToString())
                }
                EmailDivider()
                EmailSection("Subjek", item.subject)
                EmailDivider()
                EmailSection("Isi", item.body)
            }
        }
        if (item.attachments.isNotEmpty()) {
            EmailDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmailLabel("Lampiran")
                item.attachments.forEach { p ->
                    PdfFileCard(p, onOpen = { onOpenPath(p, false) }, onShare = { onOpenPath(p, true) })
                }
            }
        }
        EmailDivider()
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Buka aplikasi email dengan draf ini", role = Role.Button) { opened = true; onSend() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Kirim email", style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MythosColors.Ink))
        }
        if (opened) {
            Text(
                "Aplikasi email dibuka dengan draf ini. Email baru terkirim setelah Anda menekan Kirim di sana.",
                style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, lineHeight = 17.sp, color = MythosColors.Hint, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun EmailDivider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(MythosColors.Border))
}

@Composable
private fun EmailLabel(text: String) {
    Text(text, style = TextStyle(fontFamily = MythosSans, fontSize = 13.sp, color = MythosColors.Hint))
}

@Composable
private fun EmailSection(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        EmailLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(value, style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, lineHeight = 22.sp, color = MythosColors.Ink))
    }
}
