package com.mythos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.ProviderChoice
import com.mythos.app.R
import kotlinx.coroutines.launch

private val TileGrey = Color(0xFFEEECEB)
private val RowWhite = Color.White
private val Disabled = Color(0xFFA5A5A2)
private val LinkBlue = Color(0xFF1B5091)

private fun title(c: Color = MythosColors.Ink) =
    TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 20.sp, color = c)

private fun sub(c: Color = MythosColors.Hint) =
    TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = c)

/**
 * Lembar bawah 1:1 sesuai video:
 * - Sudut atas 36 dp, isi #F8F8F6, scrim 22 % hitam, pegangan 57×4 dp #B7B7B5.
 * - Baris judul: tombol bulat X (44 dp) di kiri, judul SemiBold di tengah.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MythosSheet(titleText: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        containerColor = MythosColors.Background,
        scrimColor = Color.Black.copy(alpha = 0.22f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(57.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFB7B7B5))
            )
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp)) {
                CircleIconButton(
                    onClick = { scope.launch { state.hide() }.invokeOnCompletion { if (it == null) onDismiss() } },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                    label = "Tutup",
                ) { XGlyph(13.dp) }
                Text(
                    titleText,
                    style = TextStyle(
                        fontFamily = MythosSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.5.sp,
                        color = MythosColors.Ink,
                    ),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun Tile(label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier
            .height(100.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(TileGrey)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(10.dp))
        Text(label, style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = MythosColors.Ink))
    }
}

@Composable
private fun SheetCardRow(
    leading: @Composable () -> Unit,
    heading: String,
    headingColor: Color = MythosColors.Ink,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Row(
        Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(RowWhite)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Text(heading, style = title(headingColor), modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    heading: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Row(
        Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(RowWhite)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) MythosColors.Icon else Disabled, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(heading, style = title(if (enabled) MythosColors.Ink else Disabled))
            if (subtitle != null) Text(subtitle, style = sub(if (enabled) MythosColors.Hint else Disabled))
        }
        trailing()
    }
}

/**
 * "Tambahkan ke chat" 1:1 sesuai video:
 * - Tiga petak #EEECEB (Kamera, Foto, File) tanpa lingkaran latar ikon.
 * - Baris "Tampilkan foto terbaru" berwarna biru #1B5091.
 * - Baris "Tambahkan ke proyek" ("Tidak ada" >).
 * - Baris "Konektor" (>).
 */
@Composable
fun AddToChatSheet(
    onDismiss: () -> Unit,
    onSwitch: (SheetKind) -> Unit,
    onPick: (AttachSource) -> Unit,
    ws: WorkspaceHooks,
) {
    MythosSheet("Tambahkan ke chat", onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Tile("Kamera", Modifier.weight(1f), { onPick(AttachSource.CAMERA); onDismiss() }) { CameraGlyph() }
            Tile("Foto", Modifier.weight(1f), { onPick(AttachSource.PHOTO); onDismiss() }) { PhotoGlyph() }
            Tile("File", Modifier.weight(1f), { onPick(AttachSource.FILE); onDismiss() }) { FileUploadGlyph() }
        }
        Spacer(Modifier.height(16.dp))
        SheetCardRow(
            leading = { RecentPhotosGlyph(LinkBlue) },
            heading = "Tampilkan foto terbaru",
            headingColor = LinkBlue,
            onClick = { onPick(AttachSource.PHOTO); onDismiss() },
        )
        Spacer(Modifier.height(15.dp))
        SheetCardRow(
            leading = { ProyekGlyph() },
            heading = "Tambahkan ke proyek",
            trailing = {
                Text(ws.projectLabel, style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, color = MythosColors.Hint))
                Spacer(Modifier.width(10.dp))
                ChevronGlyph()
            },
            onClick = { onSwitch(SheetKind.PROJECT) },
        )
        Spacer(Modifier.height(15.dp))
        SheetCardRow(
            leading = { ConnectorGlyph() },
            heading = "Konektor",
            trailing = { ChevronGlyph() },
            onClick = { onSwitch(SheetKind.CONNECTORS) },
        )
    }
}

/** Pemilih proyek: memilih proyek berarti melanjutkan pekerjaan di folder proyek itu (membuka obrolannya). */
@Composable
fun ProjectSheet(ws: WorkspaceHooks, onDismiss: () -> Unit) {
    MythosSheet("Tambahkan ke proyek", onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFF4F4F1)),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(56.dp).background(RowWhite).clickable { ws.onPickProject(null); onDismiss() }) {
                Column(Modifier.padding(start = 16.dp, end = 52.dp, top = 9.dp)) {
                    Text("Tidak ada", style = title())
                    Text("Chat baru dengan proyek baru", style = sub())
                }
                if (ws.currentProjectId == null) {
                    Icon(Icons.Outlined.Check, null, tint = LinkBlue, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).size(20.dp))
                }
            }
            ws.projects.forEach { m ->
                Box(Modifier.fillMaxWidth().height(56.dp).background(RowWhite).clickable { ws.onPickProject(m); onDismiss() }) {
                    Column(Modifier.padding(start = 16.dp, end = 52.dp, top = 9.dp)) {
                        Text(m.title.ifBlank { "(tanpa judul)" }, style = title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.id, style = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (m.id == ws.currentProjectId) {
                        Icon(Icons.Outlined.Check, null, tint = LinkBlue, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).size(20.dp))
                    }
                }
            }
        }
        if (ws.projects.isEmpty()) {
            Text(
                "Belum ada proyek. Kirim pesan pertama; proyeknya dibuat otomatis.",
                style = sub(), modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
    }
}

/** Konektor yang sungguhan: pusat integrasi, pencarian web, mode riset, Google Workspace per layanan, build GitHub Actions, dan pengaturan model. */
@Composable
fun ConnectorsSheet(ws: WorkspaceHooks, onOpenSettings: (SettingsSection) -> Unit, onDismiss: () -> Unit) {
    val researchOn = ws.research && ws.webEnabled
    MythosSheet("Konektor & Integrasi", onDismiss) {
        InfoRow(
            Icons.Outlined.Extension,
            "Pengaturan Integrasi & Uji Koneksi",
            "Status seluruh koneksi, uji koneksi nyata, dan manajemen multi-akun",
            trailing = { ChevronGlyph() },
            onClick = { onOpenSettings(SettingsSection.INTEGRATIONS); onDismiss() }
        )
        Spacer(Modifier.height(12.dp))
        InfoRow(
            Icons.Outlined.TravelExplore, "Pencarian web", "Alat WebSearch dan WebFetch untuk agen (halaman dibuka otomatis tanpa izin)",
            trailing = { Switch(checked = ws.webEnabled, onCheckedChange = { ws.onWeb(it) }) },
            onClick = { ws.onWeb(!ws.webEnabled) }
        )
        Spacer(Modifier.height(12.dp))
        InfoRow(
            Icons.Outlined.TravelExplore, "Riset mendalam",
            if (ws.webEnabled) "Agen mencari beberapa sumber web, membaca halamannya, lalu menulis laporan bersumber"
            else "Aktifkan pencarian web untuk menggunakan riset",
            enabled = ws.webEnabled,
            trailing = {
                Switch(
                    checked = researchOn, onCheckedChange = { ws.onResearch(it) }, enabled = ws.webEnabled,
                    colors = SwitchDefaults.colors(
                        disabledCheckedTrackColor = Color(0xFFE4E4E1), disabledUncheckedTrackColor = Color(0xFFE4E4E1),
                        disabledUncheckedThumbColor = Color.White, disabledCheckedThumbColor = Color.White,
                        disabledUncheckedBorderColor = Color.Transparent, disabledCheckedBorderColor = Color.Transparent,
                    )
                )
            },
            onClick = { ws.onResearch(!researchOn) }
        )
        Spacer(Modifier.height(12.dp))
        val googleSub = if (ws.activeGoogleAccountEmail.isNotBlank()) {
            val activeList = buildList {
                if (ws.gmailConnected) add("Gmail")
                if (ws.calendarConnected) add("Calendar")
                if (ws.driveConnected) add("Drive")
            }
            val svcText = if (activeList.isEmpty()) "Semua layanan diputuskan" else activeList.joinToString(" • ")
            "Akun aktif: ${ws.activeGoogleAccountEmail} (${ws.googleAccountCount} akun) — $svcText"
        } else {
            "Tambah/ganti akun Google & kelola Gmail, Calendar, Drive (OAuth 2.0 PKCE)"
        }
        InfoRow(
            Icons.Outlined.Extension, "Google Workspace (Multi-Akun)",
            googleSub,
            trailing = { ChevronGlyph() }, onClick = { onOpenSettings(SettingsSection.WORKSPACE); onDismiss() }
        )
        if (ws.activeGoogleAccountEmail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            InfoRow(
                Icons.Outlined.Extension,
                "Gmail (${ws.activeGoogleAccountEmail})",
                if (ws.gmailConnected) "Terhubung: baca email & buat/kirim draf" else "Diputuskan",
                trailing = {
                    Switch(
                        checked = ws.gmailConnected,
                        onCheckedChange = { ws.onToggleGoogleService(com.mythos.app.agent.WorkspaceServiceKind.GMAIL, it) }
                    )
                },
                onClick = { ws.onToggleGoogleService(com.mythos.app.agent.WorkspaceServiceKind.GMAIL, !ws.gmailConnected) }
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(
                Icons.Outlined.Extension,
                "Google Calendar (${ws.activeGoogleAccountEmail})",
                if (ws.calendarConnected) "Terhubung: sinkronisasi jadwal & SLA" else "Diputuskan",
                trailing = {
                    Switch(
                        checked = ws.calendarConnected,
                        onCheckedChange = { ws.onToggleGoogleService(com.mythos.app.agent.WorkspaceServiceKind.CALENDAR, it) }
                    )
                },
                onClick = { ws.onToggleGoogleService(com.mythos.app.agent.WorkspaceServiceKind.CALENDAR, !ws.calendarConnected) }
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(
                Icons.Outlined.Extension,
                "Google Drive (${ws.activeGoogleAccountEmail})",
                if (ws.driveConnected) "Terhubung: folder & PDF bukti (scope drive.file)" else "Diputuskan",
                trailing = {
                    Switch(
                        checked = ws.driveConnected,
                        onCheckedChange = { ws.onToggleGoogleService(com.mythos.app.agent.WorkspaceServiceKind.DRIVE, it) }
                    )
                },
                onClick = { ws.onToggleGoogleService(com.mythos.app.agent.WorkspaceServiceKind.DRIVE, !ws.driveConnected) }
            )
        }
        Spacer(Modifier.height(12.dp))
        InfoRow(
            Icons.Outlined.Extension, "GitHub Actions", if (ws.githubReady) "Aktif: agen bisa membangun dan menguji" else "Belum diatur: ketuk untuk mengisi token dan repo",
            trailing = { ChevronGlyph() }, onClick = { onOpenSettings(SettingsSection.GITHUB); onDismiss() }
        )
        Spacer(Modifier.height(12.dp))
        InfoRow(
            Icons.Outlined.Inventory2, "Model & kunci API", "Penyedia, model dinamis, Ollama lokal",
            trailing = { ChevronGlyph() }, onClick = { onOpenSettings(SettingsSection.MODEL); onDismiss() }
        )
        Spacer(Modifier.height(12.dp))
        InfoRow(
            Icons.Outlined.Key, "Kunci pencarian web", "Brave Search dan Tavily (opsional)",
            trailing = { ChevronGlyph() }, onClick = { onOpenSettings(SettingsSection.SEARCH); onDismiss() }
        )
    }
}

private fun providerNote(p: ProviderChoice): String = when (p) {
    ProviderChoice.OLLAMA -> "Model lokal di perangkat atau jaringan Anda"
    ProviderChoice.OPENROUTER -> "Model gratis lewat kunci OpenRouter"
    else -> "Memakai kunci API Anda sendiri"
}

/**
 * "Pemilihan model": daftar penyedia (OpenAI, Claude, Gemini, dst.) serta preset model dinamis untuk penyedia aktif.
 * Memilih salah satu langsung mengganti penyedia/model yang dipakai agen; bila kuncinya belum diisi, layar Pengaturan terbuka.
 */
@Composable
fun ModelSheet(
    current: ProviderChoice,
    buildReady: Boolean,
    onPick: (ProviderChoice) -> Unit,
    onBuild: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    currentModel: String = "",
    onPickModel: (String) -> Unit = {},
) {
    val all = ProviderChoice.values().toList()
    val presets = com.mythos.app.PopularModels.forProvider(current)
    MythosSheet("Pemilihan penyedia & model AI", onDismiss) {
        if (current.lockedModel == null && presets.isNotEmpty()) {
            Text(
                "Model aktif (${current.label}):",
                style = sub(Color(0xFF6B6965)),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
            )
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(RowWhite)
            ) {
                val options = listOf("" to "Otomatis (disarankan)") + presets.map { it to it }
                options.forEachIndexed { idx, (modelId, label) ->
                    val isSelected = currentModel == modelId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickModel(modelId); onDismiss() }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            style = TextStyle(
                                fontFamily = MythosSans,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.5.sp,
                                color = MythosColors.Ink,
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(Icons.Outlined.Check, contentDescription = "Model terpilih", tint = LinkBlue, modifier = Modifier.size(19.dp))
                        }
                    }
                    if (idx < options.lastIndex) {
                        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.67.dp).background(Color(0xFFE3E3E3)))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            "Penyedia AI:",
            style = sub(Color(0xFF6B6965)),
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
        )
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(RowWhite)
        ) {
            all.forEachIndexed { idx, p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(p); onDismiss() }
                        .padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderLogo(p, 24.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.label,
                            style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MythosColors.Ink)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(providerNote(p), style = sub(Color(0xFF6B6965)))
                    }
                    if (p == current) {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Outlined.Check, contentDescription = "Terpilih", tint = LinkBlue, modifier = Modifier.size(21.dp))
                    }
                }
                if (idx < all.lastIndex) {
                    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.67.dp).background(Color(0xFFE3E3E3)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val footerText = buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF6B6965))) {
                append("Nama model kustom, kunci API, Google Workspace, dan uji koneksi diatur di Pengaturan. ")
            }
            withStyle(SpanStyle(color = LinkBlue, textDecoration = TextDecoration.Underline)) {
                append("Buka pengaturan")
            }
        }
        Text(
            footerText,
            style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.5.sp),
            modifier = Modifier
                .padding(horizontal = 36.dp)
                .clickable(onClickLabel = "Pengaturan model") { onMore(); onDismiss() }
        )
    }
}

/**
 * Layar modal penuh pengantar input suara. "Lanjutkan" membuka pengenal suara perangkat; hasilnya masuk ke kolom chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var continuing by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        containerColor = MythosColors.Background,
        scrimColor = Color.Black.copy(alpha = 0.22f),
        dragHandle = null,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to MythosColors.Background,
                        0.52f to MythosColors.Background,
                        0.78f to Color(0xFFF3E4DE),
                        1.0f to Color(0xFFEDD7CF),
                    )
                )
        ) {
            CircleIconButton(
                onClick = { scope.launch { state.hide() }.invokeOnCompletion { if (it == null) onDismiss() } },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp),
                label = "Tutup",
            ) { XGlyph(13.dp) }

            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_voice_mic),
                    contentDescription = "Mikrofon",
                    modifier = Modifier.size(width = 86.dp, height = 168.dp)
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    "Ucapkan tugas Anda\nke Mythos.",
                    style = TextStyle(
                        fontFamily = MythosSerif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 27.sp,
                        lineHeight = 35.sp,
                        color = MythosColors.Ink,
                        textAlign = TextAlign.Center,
                    )
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    "Teks hasil ucapan masuk ke kolom chat",
                    style = TextStyle(
                        fontFamily = MythosSans,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        color = Color(0xFF4B4948),
                        textAlign = TextAlign.Center,
                    )
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Indonesia",
                        style = TextStyle(
                            fontFamily = MythosSans,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.5.sp,
                            color = Color(0xFF4B4948),
                        )
                    )
                    Spacer(Modifier.width(5.dp))
                    UpDownChevronGlyph(Color(0xFF4B4948))
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF161616))
                    .clickable(enabled = !continuing, onClickLabel = "Lanjutkan", role = Role.Button) {
                        continuing = true
                        scope.launch { state.hide() }.invokeOnCompletion { if (it == null) onContinue() else continuing = false }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Lanjutkan",
                    style = TextStyle(
                        fontFamily = MythosSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        color = Color.White,
                    )
                )
            }
        }
    }
}

enum class SheetKind { NONE, ADD, MODEL, PROJECT, CONNECTORS, VOICE }
