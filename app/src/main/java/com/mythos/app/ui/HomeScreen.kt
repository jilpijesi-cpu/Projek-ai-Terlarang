package com.mythos.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.ProviderChoice
import com.mythos.app.UserSettings
import com.mythos.app.agent.AgentConfig

/** Bilah atas: tinggi 60 dp di bawah status bar. */
@Composable
internal fun TopBar(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp), content = content)
}

/** Sheet pemilih/penambah yang dipakai bersama oleh beranda dan obrolan. */
@Composable
internal fun MythosSheets(
    kind: SheetKind,
    onClose: () -> Unit,
    onSwitch: (SheetKind) -> Unit,
    settings: UserSettings,
    onPickProvider: (ProviderChoice) -> Unit,
    onOpenSettings: (SettingsSection) -> Unit,
    ws: WorkspaceHooks,
    onAttach: (AttachSource) -> Unit,
) {
    when (kind) {
        SheetKind.NONE -> {}
        SheetKind.ADD -> AddToChatSheet(onClose, onSwitch, onAttach, ws)
        SheetKind.PROJECT -> ProjectSheet(ws, onClose)
        SheetKind.CONNECTORS -> ConnectorsSheet(ws, onOpenSettings, onClose)
        SheetKind.MODEL -> ModelSheet(
            current = settings.provider,
            buildReady = settings.github != null,
            onPick = onPickProvider,
            onBuild = { onOpenSettings(SettingsSection.GITHUB) },
            onMore = { onOpenSettings(SettingsSection.MODEL) },
            onDismiss = onClose,
            currentModel = settings.model,
            onPickModel = ws.onPickModel,
        )
        SheetKind.VOICE -> VoiceSheet(
            onDismiss = onClose,
            onContinue = {
                onClose()
                ws.onMic()
            },
        )
    }
}

@Composable
fun MythosHomeScreen(
    settings: UserSettings,
    text: String,
    onTextChange: (String) -> Unit,
    incognito: Boolean,
    onIncognitoChange: (Boolean) -> Unit,
    onOpenSidebar: () -> Unit,
    onPickProvider: (ProviderChoice) -> Unit,
    onOpenSettings: (SettingsSection) -> Unit,
    ws: WorkspaceHooks,
    onSend: (String, Boolean) -> Unit,
    attachments: List<Attachment>,
    onAttach: (AttachSource) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf(SheetKind.NONE) }
    val greeting = remember { AgentConfig().userAddress?.let { "Halo, $it" } ?: "Halo" }
    BackHandler(enabled = incognito) { onIncognitoChange(false) }

    Column(Modifier.fillMaxSize().background(MythosColors.Background)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val roomy = maxHeight > 280.dp
            if (!incognito) {
                // Logo maskot dan sapaan di tengah (tanpa pil paket/upgrade).
                Column(
                    Modifier.align(Alignment.Center).offset(y = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PixelMascot(Modifier.size(61.5.dp, 41.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        greeting,
                        style = TextStyle(
                            fontFamily = MythosSerif,
                            fontWeight = FontWeight.Normal,
                            fontSize = 25.sp,
                            lineHeight = 32.sp,
                            color = Color(0xFF2B2A27),
                        )
                    )
                }
            } else if (roomy) {
                // Layar Chat samaran
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = (-12).dp)
                        .padding(horizontal = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FilledGhost(28.dp)
                    Spacer(Modifier.height(22.dp))
                    val incogCopy = buildAnnotatedString {
                        append("Chat samaran tidak masuk ke riwayat dan dihapus saat aplikasi dibuka ulang. Pesan tetap dikirim ke penyedia model yang Anda pilih. ")
                    }
                    Text(
                        incogCopy,
                        style = TextStyle(
                            fontFamily = MythosSans,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.5.sp,
                            lineHeight = 20.5.sp,
                            color = Color(0xFF3D3C3A),
                            textAlign = TextAlign.Center,
                        )
                    )
                }
            }
            TopBar {
                CircleIconButton(
                    onClick = onOpenSidebar,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                    label = "Buka riwayat",
                ) { MenuGlyph() }
                if (incognito) {
                    Text(
                        "Chat samaran",
                        style = TextStyle(
                            fontFamily = MythosSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = MythosColors.Ink,
                        ),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                IncognitoToggleButton(
                    incognito = incognito,
                    onToggle = { onIncognitoChange(!incognito) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                )
            }
        }
        val chip = uiModelChip(settings)
        MythosInputCard(
            text = text,
            onText = onTextChange,
            placeholder = "Chat Mythos...",
            modelName = chip.first,
            effort = chip.second,
                provider = chipProvider(settings),
            running = false,
            onPlus = { sheet = SheetKind.ADD },
            onModel = { sheet = SheetKind.MODEL },
            onMic = { sheet = SheetKind.VOICE },
            onSend = { if (text.isNotBlank() || attachments.isNotEmpty()) onSend(text.trim(), incognito) },
            onStop = {},
            dashed = false,
            attachments = attachments,
            onRemoveAttachment = onRemoveAttachment,
        )
        NavBarFill()
    }
    MythosSheets(sheet, { sheet = SheetKind.NONE }, { sheet = it }, settings, onPickProvider, onOpenSettings, ws, onAttach)
}
