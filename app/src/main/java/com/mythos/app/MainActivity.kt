package com.mythos.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mythos.app.ui.AgentViewModel
import com.mythos.app.ui.ApprovalRequest
import com.mythos.app.ui.ArtifactsScreen
import com.mythos.app.ui.AttachSource
import com.mythos.app.ui.ChatMeta
import com.mythos.app.ui.ChatScreen
import com.mythos.app.ui.CodeScreen
import com.mythos.app.ui.DrawerBg
import com.mythos.app.ui.HistoryDrawerContent
import com.mythos.app.ui.IntroSplash
import com.mythos.app.ui.MythosHomeScreen
import com.mythos.app.ui.MythosTheme
import com.mythos.app.ui.ProjectsScreen
import com.mythos.app.ui.QuestionRequest
import com.mythos.app.ui.Screen
import com.mythos.app.ui.SettingsScreen
import com.mythos.app.ui.Workspace
import com.mythos.app.ui.WorkspaceHooks
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        getSharedPreferences("mythos_ui", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("asked_notifications", true)
            .apply()
    }

    private var oauthRedirectUri by mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data != null) oauthRedirectUri = intent.data
        val bar = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B))
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        askNotificationPermissionOnce()
        val playIntro = savedInstanceState == null && !introPlayed
        introPlayed = true
        setContent {
            MythosTheme {
                var showIntro by rememberSaveable { mutableStateOf(playIntro) }
                Box(Modifier.fillMaxSize()) {
                    MythosApp(
                        pendingOAuthUri = oauthRedirectUri,
                        onOAuthUriConsumed = { oauthRedirectUri = null },
                    )
                    if (showIntro) IntroSplash(onFinished = { showIntro = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null) oauthRedirectUri = intent.data
    }

    private companion object {
        var introPlayed = false
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("mythos_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun MythosApp(
    vm: AgentViewModel = viewModel(),
    pendingOAuthUri: android.net.Uri? = null,
    onOAuthUriConsumed: () -> Unit = {},
) {
    val ctx = LocalContext.current
    BackHandler(enabled = vm.screen != Screen.HOME) { vm.requestLeave() }
    LaunchedEffect(pendingOAuthUri) {
        if (pendingOAuthUri != null) {
            vm.handleOAuthRedirect(pendingOAuthUri)
            onOAuthUriConsumed()
        }
    }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast = null }
    }

    var tick by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<ChatMeta?>(null) }

    val s = vm.settings

    when (vm.screen) {
        Screen.HOME, Screen.CHAT -> DrawerHost(
            vm = vm,
            onDelete = { toDelete = it },
            onExport = {
                vm.export { f ->
                    if (f != null) shareZip(ctx, f)
                    else Toast.makeText(ctx, "Belum ada proyek untuk diekspor", Toast.LENGTH_SHORT).show()
                }
            },
        )

        Screen.PROJECTS -> ProjectsScreen(
            chats = vm.chats,
            refreshKey = vm.running,
            onBack = { vm.back() },
            onOpen = { vm.openChat(it.id) },
            onFiles = { vm.openCode(it.id) },
            onExport = { m ->
                vm.exportProject(m.projectPath) { f ->
                    if (f != null) shareZip(ctx, f) else Toast.makeText(ctx, "Folder proyek tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { toDelete = it },
        )

        Screen.CODE -> CodeScreen(
            projects = vm.workspaceProjects(),
            selectedId = vm.browseId,
            refreshKey = vm.running,
            onBack = { vm.back() },
            onSelect = { vm.selectBrowse(it) },
            onOpenExternal = { shareOrOpen(ctx, it, share = false) },
            onShare = { shareOrOpen(ctx, it, share = true) },
        )

        Screen.ARTIFACTS -> ArtifactsScreen(
            chats = vm.chats,
            refreshKey = vm.running,
            onBack = { vm.back() },
            onOpenExternal = { shareOrOpen(ctx, it, share = false) },
            onShare = { shareOrOpen(ctx, it, share = true) },
        )

        Screen.SETTINGS -> SettingsScreen(
            section = vm.settingsSection,
            initial = s,
            draft = vm.settingsDraft,
            onDraftChange = { vm.settingsDraft = it },
            loadFor = { vm.settingsFor(it) },
            scan = { st, refresh -> vm.scanModels(st, refresh) },
            onSave = { vm.saveSettings(it) },
            onBack = { vm.back() },
            extraKeys = vm.extraKeys,
            onAddExtra = { p, text -> vm.addExtraKeys(p, text) },
            onRemoveExtra = { p, k -> vm.removeExtraKey(p, k) },
            onRemoveMain = { p, next -> vm.removeMainKey(p, next) },
            onSetMain = { p, k -> vm.setMainKey(p, k) },
            onRecheck = { tick++ },
            onPersistImmediate = { vm.persistSettingsImmediate(it) },
            onStartPkceSession = { vm.startPkceSession(it) },
            onLoadPkceSession = { vm.loadPkceSession() },
        )
    }

    vm.pendingApproval?.let { ApprovalDialog(it, vm::respondApproval) }
    vm.pendingQuestion?.let { QuestionDialog(it, vm::respondQuestion) }
    vm.pendingUndo?.let { u ->
        AlertDialog(
            onDismissRequest = vm::dismissUndo,
            title = { Text("Batalkan tugas ini?") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text("File dikembalikan ke keadaan sebelum tugas. File yang sudah diubah lagi sesudahnya dilewati, tidak ditimpa.")
                    u.files.forEach { f ->
                        Text("• ${f.path}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmUndo) { Text("Batalkan tugas") } },
            dismissButton = { TextButton(onClick = { vm.dismissUndo() }) { Text("Tidak") } },
        )
    }
    if (vm.askLeave) {
        AlertDialog(
            onDismissRequest = vm::dismissLeave,
            title = { Text("Agen masih bekerja") },
            text = { Text("Keluar sekarang akan menghentikan tugas yang sedang berjalan. File yang sudah dibuat tetap tersimpan di proyek.") },
            confirmButton = { TextButton(onClick = vm::confirmLeave) { Text("Hentikan & keluar") } },
            dismissButton = { TextButton(onClick = vm::dismissLeave) { Text("Tetap di sini") } },
        )
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus obrolan?") },
            text = { Text("\"${c.title}\"\nPilih apakah file proyeknya ikut dihapus.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteChat(c, false); toDelete = null }) { Text("Hapus riwayat saja") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { toDelete = null }) { Text("Batal") }
                    TextButton(onClick = { vm.deleteChat(c, true); toDelete = null }) { Text("Hapus + file") }
                }
            },
        )
    }
}

/**
 * Laci geser model "push" 1:1 sesuai video (menit 00:08..00:10):
 * Saat laci dibuka, layar utama bergeser ke kanan (sekitar 82 % lebar layar) tanpa scrim gelap,
 * menampilkan sudut membulat 32 dp dan tepi tipis #C6C6C4 di sisi kirinya.
 */
@Composable
private fun DrawerHost(vm: AgentViewModel, onDelete: (ChatMeta) -> Unit, onExport: () -> Unit) {
    val ctx = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> vm.attachFromUris(uris) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.attachFromUris(uris) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.cameraDone(ok) }
    val onAttach: (AttachSource) -> Unit = {
        try {
            when (it) {
                AttachSource.PHOTO -> pickImage.launch("image/*")
                AttachSource.FILE -> pickFile.launch(arrayOf("*/*"))
                AttachSource.CAMERA -> takePicture.launch(vm.newCameraUri())
            }
        } catch (e: Exception) {
            if (it == AttachSource.CAMERA) vm.cameraDone(false)
            Toast.makeText(ctx, "Tidak dapat membuka pemilih berkas/kamera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(vm.attachError) {
        vm.attachError?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.attachError = null }
    }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    val s = vm.settings
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) vm.appendSpeech(t)
    }
    val onMic: () -> Unit = {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Bicara sekarang…")
                }
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Pengenalan suara tidak tersedia di perangkat ini", Toast.LENGTH_LONG).show()
        }
    }
    val activeGoogle = s.activeGoogleAccount
    val hooks = WorkspaceHooks(
        projects = vm.chats.toList(),
        currentProjectId = if (vm.screen == Screen.CHAT) vm.projectName else null,
        webEnabled = vm.webEnabled,
        research = vm.researchOn,
        githubReady = s.github != null,
        onResearch = { vm.setResearch(it) },
        onWeb = { vm.setWeb(it) },
        onPickProject = { vm.pickProject(it) },
        onMic = onMic,
        workspaceReady = s.workspace != null,
        activeGoogleAccountEmail = activeGoogle?.email.orEmpty(),
        googleAccountCount = s.allGoogleAccounts.size,
        gmailConnected = activeGoogle != null && activeGoogle.gmailConnected && s.gmailEnabled,
        calendarConnected = activeGoogle != null && activeGoogle.calendarConnected && s.calendarEnabled,
        driveConnected = activeGoogle != null && activeGoogle.driveConnected && s.driveEnabled,
        onToggleGoogleService = { kind, enabled -> vm.toggleWorkspaceService(kind, enabled) },
        currentModel = s.model,
        onPickModel = { vm.pickModel(it) },
    )

    val progress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "drawer-push",
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(DrawerBg)) {
        val drawerWidth = maxWidth * 0.82f
        val drawerWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { drawerWidth.toPx() }

        if (progress > 0f) {
            HistoryDrawerContent(
                chats = vm.chats,
                onNew = {
                    drawerOpen = false
                    vm.newChat()
                },
                onOpen = {
                    drawerOpen = false
                    vm.openChat(it.id)
                },
                onDelete = onDelete,
                onProjects = { drawerOpen = false; vm.openWorkspace(Screen.PROJECTS) },
                onCode = { drawerOpen = false; vm.openCode() },
                onArtifacts = { drawerOpen = false; vm.openWorkspace(Screen.ARTIFACTS) },
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset { IntOffset((-(1f - progress) * drawerWidthPx * 0.25f).roundToInt(), 0) },
            )
        }

        val cornerRadius = (32f * progress).dp
        val mainShape = RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius)
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((progress * drawerWidthPx).roundToInt(), 0) }
                .then(
                    if (progress > 0.01f) {
                        Modifier
                            .shadow(12.dp, mainShape, ambientColor = Color(0x1F000000), spotColor = Color(0x29000000))
                            .clip(mainShape)
                            .border(0.67.dp, Color(0xFFC6C6C4), mainShape)
                    } else Modifier
                )
        ) {
            if (vm.screen == Screen.HOME) {
                MythosHomeScreen(
                    settings = s,
                    text = vm.homeDraft,
                    onTextChange = { vm.homeDraft = it },
                    incognito = vm.homeIncognito,
                    onIncognitoChange = { vm.homeIncognito = it },
                    onOpenSidebar = { drawerOpen = true },
                    onPickProvider = { vm.pickProvider(it) },
                    onOpenSettings = { vm.openSettings(it) },
                    ws = hooks,
                    onSend = { t, incog -> vm.submitFromHome(t, incog) },
                    attachments = vm.attachments,
                    onAttach = onAttach,
                    onRemoveAttachment = { vm.removeAttachment(it) },
                )
            } else {
                key(vm.chatEpoch) {
                    ChatScreen(
                        chat = vm.items,
                        running = vm.running,
                        activity = vm.liveActivity,
                        subAgents = vm.subAgents,
                        settings = s,
                        incognito = vm.incognito,
                        text = vm.chatDraft,
                        onTextChange = { vm.chatDraft = it },
                        onSend = { vm.send(it) },
                        onStop = { vm.stop() },
                        onNewChat = { vm.newChat() },
                        onMenu = { drawerOpen = true },
                        onPickProvider = { vm.pickProvider(it) },
                        onOpenSettings = { vm.openSettings(it) },
                        onExport = onExport,
                        ws = hooks,
                        attachments = vm.attachments,
                        onAttach = onAttach,
                        onRemoveAttachment = { vm.removeAttachment(it) },
                        onUndo = { vm.requestUndo(it) },
                        loadDiff = { item, path -> vm.loadDiff(item, path) },
                        onOpenFile = { _, path, share ->
                            val f = vm.projectFile(path)
                            if (f != null) shareOrOpen(ctx, f, share)
                            else Toast.makeText(ctx, "Berkas tidak ditemukan di proyek ini", Toast.LENGTH_LONG).show()
                        },
                        onOpenPath = { path, share ->
                            val f = vm.projectFile(path)
                            if (f != null) shareOrOpen(ctx, f, share)
                            else Toast.makeText(ctx, "Berkas tidak ditemukan di proyek ini", Toast.LENGTH_LONG).show()
                        },
                        onSendEmail = { d -> vm.openEmailDraft(d)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } },
                    )
                }
            }
            // Saat laci terbuka, ketuk bagian layar utama yang mengintip di sisi kanan untuk menutup laci
            if (drawerOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Tutup riwayat",
                        ) { drawerOpen = false }
                )
            }
        }
    }
}

@Composable
private fun ApprovalDialog(req: ApprovalRequest, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text("Izinkan aksi ini?") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(req.tool, fontWeight = FontWeight.Bold)
                Text(req.summary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text("Izinkan") } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Tolak") } },
    )
}

@Composable
private fun QuestionDialog(req: QuestionRequest, onAnswer: (String) -> Unit) {
    var answer by remember(req) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onAnswer("") },
        title = { Text("Agen bertanya") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(req.question)
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(answer) }) { Text("Kirim") } },
        dismissButton = { TextButton(onClick = { onAnswer("") }) { Text("Lewati") } },
    )
}

private fun shareZip(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Bagikan proyek"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Gagal membagikan ZIP: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun shareOrOpen(ctx: Context, f: File, share: Boolean) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val mime = Workspace.mimeOf(f)
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        ctx.startActivity(Intent.createChooser(intent, if (share) "Bagikan ${f.name}" else "Buka ${f.name} dengan"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Tidak bisa membuka/membagikan file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
