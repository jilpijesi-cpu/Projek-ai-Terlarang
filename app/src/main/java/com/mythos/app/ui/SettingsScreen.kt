package com.mythos.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.PkceOAuthSession
import com.mythos.app.PopularModels
import com.mythos.app.ProviderChoice
import com.mythos.app.UserSettings
import com.mythos.app.agent.ExtraKeys
import com.mythos.app.agent.GitHubConfig
import com.mythos.app.agent.GoogleAccount
import com.mythos.app.agent.GoogleWorkspaceConfig
import com.mythos.app.agent.HttpGoogleWorkspaceService
import com.mythos.app.agent.IntegrationCheckResult
import com.mythos.app.agent.IntegrationTester
import com.mythos.app.agent.ModelState
import com.mythos.app.agent.ModelStatus
import com.mythos.app.agent.PermissionMode
import com.mythos.app.agent.SensitiveDataGuard
import com.mythos.app.agent.WorkspaceServiceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Halaman Pengaturan Integrasi: tiap tab/pintasan membuka bagian yang sesuai dan dapat berpindah langsung tanpa keluar layar. */
enum class SettingsSection(val title: String, val shortLabel: String) {
    INTEGRATIONS("Pengaturan Integrasi", "Status & Uji"),
    MODEL("Model & penyedia AI", "AI & Model"),
    WORKSPACE("Google Workspace (OAuth 2.0)", "Akun Google"),
    GITHUB("Build (GitHub Actions)", "GitHub"),
    SEARCH("Pencarian web", "Pencarian"),
    PERMISSION("Izin agen", "Izin Agen"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    section: SettingsSection = SettingsSection.MODEL,
    initial: UserSettings,
    draft: UserSettings?,
    onDraftChange: (UserSettings) -> Unit,
    loadFor: (ProviderChoice) -> UserSettings,
    scan: suspend (UserSettings, Boolean) -> List<ModelStatus>,
    onSave: (UserSettings) -> Unit,
    onBack: () -> Unit,
    onRecheck: () -> Unit,
    extraKeys: Map<ProviderChoice, List<String>> = emptyMap(),
    onAddExtra: (ProviderChoice, String) -> String = { _, _ -> "" },
    onRemoveExtra: (ProviderChoice, String) -> Unit = { _, _ -> },
    onRemoveMain: (ProviderChoice, String) -> Unit = { _, _ -> },
    onSetMain: (ProviderChoice, String) -> Unit = { _, _ -> },
    onPersistImmediate: (UserSettings) -> Unit = {},
    onStartPkceSession: (PkceOAuthSession) -> Unit = {},
    onLoadPkceSession: () -> PkceOAuthSession? = { null },
) {
    var activeSection by remember(section) { mutableStateOf(section) }
    var s by remember { mutableStateOf(draft ?: initial) }
    LaunchedEffect(initial) {
        if (draft == null) s = initial
    }
    var keyInput by remember(s.provider) { mutableStateOf("") }
    var scanUi by remember { mutableStateOf<ScanUi>(ScanUi.Idle) }
    var recheck by remember { mutableIntStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val testResults = remember { mutableStateMapOf<String, IntegrationCheckResult>() }
    var testingKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(s) { onDraftChange(s) }

    LaunchedEffect(activeSection, s.provider, s.apiKey, s.ollamaHost, recheck) {
        if (activeSection != SettingsSection.MODEL || s.provider.lockedModel != null || (s.provider.needsKey && s.apiKey.isBlank())) {
            scanUi = ScanUi.Idle
            return@LaunchedEffect
        }
        scanUi = ScanUi.Scanning
        delay(800)
        val force = forceNext
        forceNext = false
        scanUi = try {
            ScanUi.Done(scan(s, force))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanUi.Error(SensitiveDataGuard.redactSensitiveCredentials(e.message ?: "gagal memeriksa"))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MythosColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(onClickLabel = "Kembali", role = Role.Button, onClick = onBack)
                    .testTag("settings_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = MythosColors.Ink)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    activeSection.title,
                    style = TextStyle(fontFamily = MythosSerif, fontSize = 23.sp, color = MythosColors.Ink)
                )
                Text(
                    "Kelola seluruh layanan eksternal secara langsung tanpa rebuild APK",
                    style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Bilah navigasi antar-tab Pengaturan Integrasi
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsSection.entries.forEach { sec ->
                val selected = sec == activeSection
                val shape = RoundedCornerShape(18.dp)
                Box(
                    Modifier
                        .clip(shape)
                        .background(if (selected) MythosColors.Ink else MythosColors.Card)
                        .border(1.dp, if (selected) MythosColors.Ink else MythosColors.Border, shape)
                        .clickable { activeSection = sec }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("tab_${sec.name.lowercase()}")
                ) {
                    Text(
                        sec.shortLabel,
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) Color.White else MythosColors.Ink,
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        when (activeSection) {
            SettingsSection.INTEGRATIONS -> IntegrationsOverviewSection(
                s = s,
                testResults = testResults,
                testingKey = testingKey,
                onNavigateSection = { activeSection = it },
                onTestAll = {
                    if (testingKey != null) return@IntegrationsOverviewSection
                    testingKey = "ALL"
                    scope.launch {
                        try {
                            val results = IntegrationTester.testAll(s)
                            results.forEach { testResults[it.service] = it }
                        } finally {
                            testingKey = null
                        }
                    }
                },
                onToggleWorkspaceService = { kind, enabled ->
                    val updated = s.withServiceConnection(kind, enabled)
                    s = updated
                    onPersistImmediate(updated)
                }
            )

            SettingsSection.MODEL -> ModelAndProviderSection(
                s = s,
                onSettingsChange = { s = it },
                loadFor = loadFor,
                scanUi = scanUi,
                onRecheckModels = { forceNext = true; recheck++ },
                keyInput = keyInput,
                onKeyInputChange = { keyInput = it },
                extraKeys = extraKeys,
                onAddExtra = onAddExtra,
                onRemoveExtra = onRemoveExtra,
                onRemoveMain = onRemoveMain,
                onSetMain = onSetMain,
                onPersistImmediate = onPersistImmediate,
                aiTestResult = testResults["AI (${s.provider.label})"],
                isTestingAi = testingKey == "AI",
                onTestAi = {
                    if (testingKey != null) return@ModelAndProviderSection
                    testingKey = "AI"
                    scope.launch {
                        try {
                            val res = IntegrationTester.testAiProvider(s)
                            testResults["AI (${s.provider.label})"] = res
                        } finally {
                            testingKey = null
                        }
                    }
                }
            )

            SettingsSection.WORKSPACE -> GoogleWorkspaceMultiAccountSection(
                s = s,
                onSettingsChange = { updated ->
                    s = updated
                    onPersistImmediate(updated)
                },
                testResults = testResults,
                testingKey = testingKey,
                onTestService = { kind ->
                    if (testingKey != null) return@GoogleWorkspaceMultiAccountSection
                    testingKey = kind.id
                    scope.launch {
                        try {
                            val res = IntegrationTester.testGoogleService(s, kind)
                            testResults[kind.label] = res
                        } finally {
                            testingKey = null
                        }
                    }
                },
                onTestAllWorkspace = {
                    if (testingKey != null) return@GoogleWorkspaceMultiAccountSection
                    testingKey = "WORKSPACE_ALL"
                    scope.launch {
                        try {
                            val results = IntegrationTester.testAllGoogleServices(s)
                            results.forEach { testResults[it.service] = it }
                        } finally {
                            testingKey = null
                        }
                    }
                },
                onStartPkceSession = onStartPkceSession,
                onLoadPkceSession = onLoadPkceSession,
            )

            SettingsSection.GITHUB -> GitHubIntegrationSection(
                s = s,
                onSettingsChange = { s = it },
                testResult = testResults["GitHub Actions"],
                isTesting = testingKey == "GITHUB",
                onTestGitHub = {
                    if (testingKey != null) return@GitHubIntegrationSection
                    testingKey = "GITHUB"
                    scope.launch {
                        try {
                            testResults["GitHub Actions"] = IntegrationTester.testGitHub(s)
                        } finally {
                            testingKey = null
                        }
                    }
                }
            )

            SettingsSection.SEARCH -> WebSearchIntegrationSection(
                s = s,
                onSettingsChange = { s = it },
                braveResult = testResults["Brave Search API"],
                tavilyResult = testResults["Tavily Search API"],
                isTesting = testingKey == "SEARCH",
                onTestSearch = {
                    if (testingKey != null) return@WebSearchIntegrationSection
                    testingKey = "SEARCH"
                    scope.launch {
                        try {
                            IntegrationTester.testWebSearch(s).forEach { testResults[it.service] = it }
                        } finally {
                            testingKey = null
                        }
                    }
                }
            )

            SettingsSection.PERMISSION -> PermissionModeSection(
                s = s,
                onSettingsChange = { s = it },
            )
        }

        val pendingKeys = if (activeSection == SettingsSection.MODEL && s.provider.needsKey && keyInput.isNotBlank()) {
            ExtraKeys.parse(keyInput, extraKeys[s.provider].orEmpty() + s.apiKey).added
        } else emptyList()

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                var toSave = s
                if (pendingKeys.isNotEmpty()) {
                    if (toSave.apiKey.isBlank()) {
                        toSave = toSave.copy(apiKey = pendingKeys.first())
                        val rest = pendingKeys.drop(1)
                        if (rest.isNotEmpty()) onAddExtra(toSave.provider, rest.joinToString("\n"))
                    } else {
                        onAddExtra(toSave.provider, pendingKeys.joinToString("\n"))
                    }
                    keyInput = ""
                }
                onSave(toSave)
            },
            enabled = s.isConfigured || pendingKeys.isNotEmpty() || activeSection != SettingsSection.MODEL,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_settings_button")
        ) { Text("Simpan & Kembali", fontSize = 16.sp) }
    }
}

@Composable
private fun IntegrationsOverviewSection(
    s: UserSettings,
    testResults: Map<String, IntegrationCheckResult>,
    testingKey: String?,
    onNavigateSection: (SettingsSection) -> Unit,
    onTestAll: () -> Unit,
    onToggleWorkspaceService: (WorkspaceServiceKind, Boolean) -> Unit,
) {
    Label("Ringkasan Status Integrasi & Pengujian Koneksi")
    Text(
        "Seluruh kredensial disimpan secara persisten dan terenkripsi menggunakan Android Keystore (AES-256-GCM). " +
            "Token dan kunci rahasia tidak pernah ditampilkan penuh atau dicatat ke dalam log.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 12.dp)
    )

    Button(
        onClick = onTestAll,
        enabled = testingKey == null,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("test_all_connections_button")
    ) {
        Text(if (testingKey == "ALL") "Menguji seluruh koneksi layanan…" else "Uji Semua Koneksi Sekarang")
    }

    Spacer(Modifier.height(14.dp))

    // Kartu 1: AI Provider & Model
    IntegrationSummaryCard(
        title = "Provider & Model AI",
        configured = s.isConfigured,
        subtitle = "Penyedia: ${s.displayProvider} • Model: ${s.displayModel}",
        testResult = testResults["AI (${s.provider.label})"],
        actionLabel = "Kelola AI",
        onAction = { onNavigateSection(SettingsSection.MODEL) }
    )

    Spacer(Modifier.height(10.dp))

    // Kartu 2: Google Workspace Multi-Akun
    val activeAcct = s.activeGoogleAccount
    val accountsCount = s.allGoogleAccounts.size
    IntegrationSummaryCard(
        title = "Google Workspace (OAuth 2.0 PKCE)",
        configured = s.workspace != null,
        subtitle = if (activeAcct != null) {
            "Akun aktif: ${activeAcct.email} ($accountsCount akun tersimpan)\nLayanan: ${activeAcct.connectedServicesSummary}"
        } else {
            "Belum ada akun Google yang terhubung"
        },
        testResult = testResults["Gmail"] ?: testResults["Google Calendar"] ?: testResults["Google Drive"],
        actionLabel = "Kelola Akun",
        onAction = { onNavigateSection(SettingsSection.WORKSPACE) }
    ) {
        if (activeAcct != null) {
            Spacer(Modifier.height(8.dp))
            WorkspaceServiceKind.entries.forEach { kind ->
                val connected = activeAcct.isServiceConnected(kind) && when (kind) {
                    WorkspaceServiceKind.GMAIL -> s.gmailEnabled
                    WorkspaceServiceKind.CALENDAR -> s.calendarEnabled
                    WorkspaceServiceKind.DRIVE -> s.driveEnabled
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        kind.label,
                        style = TextStyle(fontSize = 13.sp, color = MythosColors.Ink),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (connected) "Terhubung" else "Diputuskan",
                        style = TextStyle(fontSize = 12.sp, color = if (connected) Color(0xFF2E7D32) else MythosColors.Hint),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = connected,
                        onCheckedChange = { onToggleWorkspaceService(kind, it) }
                    )
                }
                testResults[kind.label]?.let { res ->
                    CheckResultBanner(res)
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // Kartu 3: GitHub Actions
    IntegrationSummaryCard(
        title = "GitHub & Build APK",
        configured = s.github != null,
        subtitle = if (s.github != null) "Repo: ${s.githubRepo} • Token terenkripsi siap" else "Repo atau token GitHub belum diisi",
        testResult = testResults["GitHub Actions"],
        actionLabel = "Kelola GitHub",
        onAction = { onNavigateSection(SettingsSection.GITHUB) }
    )

    Spacer(Modifier.height(10.dp))

    // Kartu 4: Pencarian Web
    val hasSearchKey = s.braveKey.isNotBlank() || s.tavilyKey.isNotBlank()
    IntegrationSummaryCard(
        title = "Pencarian Web (Brave / Tavily)",
        configured = true,
        subtitle = buildString {
            append("Pencari publik aktif")
            if (s.braveKey.isNotBlank()) append(" • Brave API siap")
            if (s.tavilyKey.isNotBlank()) append(" • Tavily API siap")
            if (!hasSearchKey) append(" (opsional: tambah kunci Brave/Tavily)")
        },
        testResult = testResults["Brave Search API"] ?: testResults["Tavily Search API"],
        actionLabel = "Kelola Kunci",
        onAction = { onNavigateSection(SettingsSection.SEARCH) }
    )
}

@Composable
private fun IntegrationSummaryCard(
    title: String,
    configured: Boolean,
    subtitle: String,
    testResult: IntegrationCheckResult?,
    actionLabel: String,
    onAction: () -> Unit,
    extraContent: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MythosColors.Card)
            .border(1.dp, MythosColors.Border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (configured) Color(0xFF2E7D32) else Color(0xFFB26A00))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = MythosColors.Ink),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
        Text(subtitle, style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint))
        extraContent()
        if (testResult != null) {
            Spacer(Modifier.height(6.dp))
            CheckResultBanner(testResult)
        }
    }
}

@Composable
private fun CheckResultBanner(res: IntegrationCheckResult) {
    val bg = if (res.ok) Color(0xFFE8F5E9) else Color(0xFFFCE8E6)
    val fg = if (res.ok) Color(0xFF1B5E20) else Color(0xFFB3261E)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (res.ok) "✓" else "✕",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val latencyText = if (res.latencyMs > 0) " (${res.latencyMs} ms)" else ""
            Text(
                "${res.service}$latencyText",
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = fg)
            )
            Text(
                res.safeDetail,
                style = TextStyle(fontSize = 12.sp, color = fg)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelAndProviderSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    loadFor: (ProviderChoice) -> UserSettings,
    scanUi: ScanUi,
    onRecheckModels: () -> Unit,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    extraKeys: Map<ProviderChoice, List<String>>,
    onAddExtra: (ProviderChoice, String) -> String,
    onRemoveExtra: (ProviderChoice, String) -> Unit,
    onRemoveMain: (ProviderChoice, String) -> Unit,
    onSetMain: (ProviderChoice, String) -> Unit,
    onPersistImmediate: (UserSettings) -> Unit,
    aiTestResult: IntegrationCheckResult?,
    isTestingAi: Boolean,
    onTestAi: () -> Unit,
) {
    Label("Penyedia AI")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderChoice.values().forEach { p ->
            val selected = p == s.provider
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(if (selected) MythosColors.Ink else MythosColors.Card)
                    .border(1.dp, MythosColors.Border, shape)
                    .clickable {
                        val loaded = loadFor(p).copy(
                            githubToken = s.githubToken,
                            githubRepo = s.githubRepo,
                            ollamaHost = s.ollamaHost,
                            braveKey = s.braveKey,
                            tavilyKey = s.tavilyKey,
                            permissionMode = s.permissionMode,
                            googleAccessToken = s.googleAccessToken,
                            googleRefreshToken = s.googleRefreshToken,
                            googleClientId = s.googleClientId,
                            googleAccounts = s.googleAccounts,
                            activeGoogleAccountId = s.activeGoogleAccountId,
                            gmailEnabled = s.gmailEnabled,
                            calendarEnabled = s.calendarEnabled,
                            driveEnabled = s.driveEnabled,
                        )
                        onSettingsChange(loaded)
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .testTag("provider_${p.name.lowercase()}")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (providerLogo(p) != null) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            ProviderLogo(p, 16.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(p.label, style = TextStyle(fontSize = 15.sp, color = if (selected) Color.White else MythosColors.Ink))
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))

    if (s.provider.lockedModel != null) {
        Label("Model")
        Text(
            s.provider.lockedModel.orEmpty(),
            style = TextStyle(fontSize = 15.sp, color = MythosColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MythosColors.Card)
                .border(1.dp, MythosColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
        Text(
            "Model dikunci. OpenRouter memilih sendiri model gratis yang tersedia untuk tiap permintaan.",
            style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
            modifier = Modifier.padding(top = 6.dp)
        )
    } else {
        Label("Pilih Model AI Secara Dinamis")
        val presets = PopularModels.forProvider(s.provider)
        if (presets.isNotEmpty()) {
            Text(
                "Preset cepat ${s.provider.label} (ketuk untuk langsung mengganti):",
                style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                val autoSelected = s.model.isBlank()
                PresetModelChip(
                    label = "Otomatis",
                    selected = autoSelected,
                    onClick = {
                        val updated = s.copy(model = "")
                        onSettingsChange(updated)
                        onPersistImmediate(updated)
                    }
                )
                presets.forEach { presetModel ->
                    PresetModelChip(
                        label = presetModel,
                        selected = s.model == presetModel,
                        onClick = {
                            val updated = s.copy(model = presetModel)
                            onSettingsChange(updated)
                            onPersistImmediate(updated)
                        }
                    )
                }
            }
        }

        ModelPicker(scanUi, s.model) { picked ->
            val updated = s.copy(model = picked)
            onSettingsChange(updated)
            onPersistImmediate(updated)
        }
        if (scanUi !is ScanUi.Scanning && scanUi !is ScanUi.Idle) {
            TextButton(onClick = onRecheckModels) { Text("Periksa ulang daftar model") }
        }
        OutlinedTextField(
            value = s.model,
            onValueChange = { onSettingsChange(s.copy(model = it)) },
            singleLine = true,
            label = { Text("Nama model kustom (kosong = otomatis)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("custom_model_input")
        )
    }

    if (s.provider.needsKey) {
        Spacer(Modifier.height(16.dp))
        KeysSection(
            provider = s.provider,
            main = s.apiKey,
            input = keyInput,
            onInputChange = onKeyInputChange,
            onMainChange = {
                onSettingsChange(s.copy(apiKey = it))
                onSetMain(s.provider, it)
            },
            extras = extraKeys[s.provider].orEmpty(),
            onAdd = { onAddExtra(s.provider, it) },
            onRemove = { onRemoveExtra(s.provider, it) },
            onRemoveMain = { onRemoveMain(s.provider, it) },
            hint = keyHint(s.provider),
        )
    }

    if (s.provider == ProviderChoice.OLLAMA) {
        Spacer(Modifier.height(16.dp))
        Label("Alamat Ollama")
        OutlinedTextField(
            value = s.ollamaHost,
            onValueChange = { onSettingsChange(s.copy(ollamaHost = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(14.dp))
    OutlinedButton(
        onClick = onTestAi,
        enabled = !isTestingAi && (!s.provider.needsKey || s.apiKey.isNotBlank()),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("test_ai_connection_button")
    ) {
        Text(if (isTestingAi) "Menguji koneksi ${s.provider.label}…" else "Uji Koneksi AI (${s.provider.label})")
    }
    aiTestResult?.let {
        Spacer(Modifier.height(8.dp))
        CheckResultBanner(it)
    }
}

@Composable
private fun PresetModelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) MythosColors.Ink else MythosColors.Card)
            .border(1.dp, if (selected) MythosColors.Ink else MythosColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 12.5.sp, color = if (selected) Color.White else MythosColors.Ink)
        )
    }
}

@Composable
private fun GoogleWorkspaceMultiAccountSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    testResults: Map<String, IntegrationCheckResult>,
    testingKey: String?,
    onTestService: (WorkspaceServiceKind) -> Unit,
    onTestAllWorkspace: () -> Unit,
    onStartPkceSession: (PkceOAuthSession) -> Unit,
    onLoadPkceSession: () -> PkceOAuthSession?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showConsentDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<GoogleAccount?>(null) }
    var showAddManual by remember { mutableStateOf(false) }

    // Pilihan scope minimum untuk akun baru
    var reqGmail by remember { mutableStateOf(true) }
    var reqCalendar by remember { mutableStateOf(true) }
    var reqDrive by remember { mutableStateOf(true) }
    var loginHintInput by remember { mutableStateOf("") }

    // Pertukaran kode PKCE
    var authCodeInput by remember { mutableStateOf("") }
    var pkceStatusMsg by remember { mutableStateOf<String?>(null) }
    var exchangingPkce by remember { mutableStateOf(false) }

    // Tambah/perbarui akun dengan token langsung (tanpa menampilkan token tersimpan)
    var newAccountEmail by remember { mutableStateOf("") }
    var newAccessToken by remember { mutableStateOf("") }
    var newRefreshToken by remember { mutableStateOf("") }

    val accounts = s.allGoogleAccounts
    val activeAccount = s.activeGoogleAccount

    Label("Manajemen Multi-Akun Google (OAuth 2.0 PKCE)")
    Text(
        "Tambahkan beberapa akun Google, ganti akun aktif kapan saja, atau putuskan koneksi Gmail, Calendar, dan Drive secara terpisah " +
            "tanpa rebuild APK. Semua token disimpan terenkripsi dengan Android Keystore dan tidak pernah ditampilkan penuh.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 12.dp)
    )

    // 1. Daftar Akun Google Tersimpan
    if (accounts.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MythosColors.Card)
                .border(1.dp, MythosColors.Border, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(
                "Belum ada akun Google yang tersimpan. Gunakan tombol \"Tambah Akun Google (OAuth 2.0 PKCE)\" di bawah.",
                style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint)
            )
        }
    } else {
        Label("Daftar Akun Google Tersimpan (${accounts.size})")
        accounts.forEach { acct ->
            val isActive = acct.id == activeAccount?.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MythosColors.Card)
                    .border(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) MythosColors.Ink else MythosColors.Border,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isActive) Color(0xFF2E7D32) else Color(0xFF757575))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                acct.email,
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MythosColors.Ink)
                            )
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE8F5E9))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Aktif", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1B5E20)))
                                }
                            }
                        }
                        Text(
                            "Token terenkripsi: ${acct.maskedTokenPreview()} • Layanan: ${acct.connectedServicesSummary}",
                            style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) {
                        OutlinedButton(
                            onClick = { onSettingsChange(s.withActiveGoogleAccount(acct.id)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("switch_account_${acct.id}")
                        ) {
                            Text("Gunakan Akun Ini", fontSize = 12.5.sp)
                        }
                    }
                    TextButton(
                        onClick = { accountToDelete = acct },
                        modifier = Modifier.testTag("remove_account_${acct.id}")
                    ) {
                        Text("Hapus Akun", color = Color(0xFFB3261E), fontSize = 12.5.sp)
                    }
                }
            }
        }
    }

    // 2. Hubungkan & Putuskan Layanan Secara Terpisah (Gmail, Calendar, Drive)
    Spacer(Modifier.height(18.dp))
    Label("Kontrol Layanan Terpisah (${activeAccount?.email ?: "Belum ada akun aktif"})")
    Text(
        "Hubungkan atau putuskan masing-masing layanan secara independen. Layanan yang diputuskan tidak dapat diakses oleh agen.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    WorkspaceServiceKind.entries.forEach { kind ->
        val isServiceConnected = if (activeAccount != null) {
            activeAccount.isServiceConnected(kind) && when (kind) {
                WorkspaceServiceKind.GMAIL -> s.gmailEnabled
                WorkspaceServiceKind.CALENDAR -> s.calendarEnabled
                WorkspaceServiceKind.DRIVE -> s.driveEnabled
            }
        } else {
            when (kind) {
                WorkspaceServiceKind.GMAIL -> s.gmailEnabled
                WorkspaceServiceKind.CALENDAR -> s.calendarEnabled
                WorkspaceServiceKind.DRIVE -> s.driveEnabled
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MythosColors.Card)
                .border(1.dp, MythosColors.Border, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            kind.label,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MythosColors.Ink)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isServiceConnected) "• Terhubung" else "• Diputuskan",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isServiceConnected) Color(0xFF2E7D32) else Color(0xFFB3261E)
                            )
                        )
                    }
                    Text(kind.description, style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint))
                    Text(
                        "Scope minimum: ${kind.scopes.joinToString { it.substringAfterLast('/') }}",
                        style = TextStyle(fontSize = 11.sp, color = MythosColors.Hint)
                    )
                }
                Switch(
                    checked = isServiceConnected,
                    onCheckedChange = { connected ->
                        onSettingsChange(s.withServiceConnection(kind, connected))
                    },
                    modifier = Modifier.testTag("toggle_service_${kind.id}")
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onTestService(kind) },
                    enabled = testingKey == null && activeAccount != null,
                    modifier = Modifier.testTag("test_service_${kind.id}")
                ) {
                    Text(
                        if (testingKey == kind.id) "Menguji ${kind.label}…"
                        else "Uji Koneksi ${kind.label}"
                    )
                }
                if (activeAccount != null) {
                    TextButton(
                        onClick = { onSettingsChange(s.withServiceConnection(kind, !isServiceConnected)) }
                    ) {
                        Text(if (isServiceConnected) "Putuskan ${kind.label}" else "Hubungkan ${kind.label}")
                    }
                }
            }
            testResults[kind.label]?.let { res ->
                Spacer(Modifier.height(4.dp))
                CheckResultBanner(res)
            }
        }
    }

    if (activeAccount != null) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTestAllWorkspace,
            enabled = testingKey == null,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("test_all_workspace_button")
        ) {
            Text(if (testingKey == "WORKSPACE_ALL") "Menguji Gmail, Calendar & Drive…" else "Uji Ketiga Layanan Google Sekaligus")
        }
    }

    // 3. Tambah / Ganti Akun Baru dengan OAuth 2.0 PKCE & Scope Minimum
    Spacer(Modifier.height(20.dp))
    Label("Tambah atau Hubungkan Akun Google Baru (OAuth 2.0 PKCE)")
    OutlinedTextField(
        value = s.googleClientId,
        onValueChange = { onSettingsChange(s.copy(googleClientId = it)) },
        singleLine = true,
        label = { Text("OAuth 2.0 Client ID") },
        modifier = Modifier.fillMaxWidth().testTag("google_client_id_input")
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = loginHintInput,
        onValueChange = { loginHintInput = it },
        singleLine = true,
        label = { Text("Email akun Google yang ingin ditambahkan (opsional, login_hint)") },
        modifier = Modifier.fillMaxWidth().testTag("google_login_hint_input")
    )

    Spacer(Modifier.height(8.dp))
    Text(
        "Pilih izin layanan minimum (Least-Privilege Scopes) untuk akun yang akan dihubungkan:",
        style = TextStyle(fontSize = 12.5.sp, color = MythosColors.Ink)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = reqGmail, onCheckedChange = { reqGmail = it })
        Text("Gmail", style = TextStyle(fontSize = 13.sp, color = MythosColors.Ink))
        Spacer(Modifier.width(10.dp))
        Checkbox(checked = reqCalendar, onCheckedChange = { reqCalendar = it })
        Text("Calendar", style = TextStyle(fontSize = 13.sp, color = MythosColors.Ink))
        Spacer(Modifier.width(10.dp))
        Checkbox(checked = reqDrive, onCheckedChange = { reqDrive = it })
        Text("Drive", style = TextStyle(fontSize = 13.sp, color = MythosColors.Ink))
    }

    Button(
        onClick = { showConsentDialog = true },
        enabled = (reqGmail || reqCalendar || reqDrive) && s.googleClientId.isNotBlank(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().testTag("start_oauth_pkce_button")
    ) {
        Text("Tambah / Login Akun Google (OAuth 2.0 + PKCE S256)")
    }

    // Kotak penyelesaian Kode Otorisasi PKCE (untuk menyelesaikan pertukaran kode -> token di dalam aplikasi)
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MythosColors.Card)
            .border(1.dp, MythosColors.Border, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(
            "Verifikasi Kode Otorisasi OAuth 2.0 (PKCE)",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MythosColors.Ink)
        )
        Text(
            "Jika peramban mengarahkan ke URL callback atau menampilkan kode otorisasi, tempel URL/kode tersebut di sini untuk menukarnya menjadi token terenkripsi dengan PKCE code_verifier.",
            style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        OutlinedTextField(
            value = authCodeInput,
            onValueChange = { authCodeInput = it; pkceStatusMsg = null },
            singleLine = true,
            label = { Text("Tempel kode otorisasi atau URL redirect (?code=…)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("pkce_auth_code_input")
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val pkceSession = onLoadPkceSession()
                if (pkceSession == null) {
                    pkceStatusMsg = "Sesi PKCE belum dimulai. Ketuk tombol \"Tambah / Login Akun Google\" terlebih dahulu."
                    return@Button
                }
                exchangingPkce = true
                pkceStatusMsg = null
                scope.launch {
                    val result = HttpGoogleWorkspaceService.exchangeCodeWithPkce(
                        clientId = pkceSession.clientId.ifBlank { s.googleClientId },
                        rawCodeOrUrl = authCodeInput,
                        codeVerifier = pkceSession.codeVerifier,
                        redirectUri = pkceSession.redirectUri,
                        gmailConnected = pkceSession.gmailRequested,
                        calendarConnected = pkceSession.calendarRequested,
                        driveConnected = pkceSession.driveRequested,
                    )
                    exchangingPkce = false
                    result.fold(
                        onSuccess = { newAcct ->
                            val finalAcct = if (newAcct.email == "Akun Google" && pkceSession.loginHint.isNotBlank()) {
                                newAcct.copy(id = pkceSession.loginHint.lowercase(), email = pkceSession.loginHint)
                            } else newAcct
                            onSettingsChange(s.withAddedOrUpdatedGoogleAccount(finalAcct))
                            authCodeInput = ""
                            pkceStatusMsg = "✓ Akun ${finalAcct.email} berhasil dihubungkan dan disimpan terenkripsi."
                        },
                        onFailure = { err ->
                            pkceStatusMsg = "✕ ${SensitiveDataGuard.redactSensitiveCredentials(err.message ?: "Gagal menukar kode OAuth")}"
                        }
                    )
                }
            },
            enabled = authCodeInput.isNotBlank() && !exchangingPkce,
            colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().testTag("exchange_pkce_code_button")
        ) {
            Text(if (exchangingPkce) "Menukar kode otorisasi dengan PKCE…" else "Tukar Kode & Hubungkan Akun")
        }
        pkceStatusMsg?.let { msg ->
            Text(
                msg,
                style = TextStyle(
                    fontSize = 12.5.sp,
                    color = if (msg.startsWith("✓")) Color(0xFF1B5E20) else Color(0xFFB3261E)
                ),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    // Opsi Tambah Akun dengan Token OAuth Langsung (Token Disembunyikan Sepenuhnya)
    Spacer(Modifier.height(10.dp))
    TextButton(
        onClick = { showAddManual = !showAddManual },
        modifier = Modifier.testTag("toggle_manual_account_button")
    ) {
        Text(if (showAddManual) "Sembunyikan input token manual" else "Tambah akun dengan Token OAuth langsung (opsional)")
    }
    if (showAddManual) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MythosColors.Card)
                .border(1.dp, MythosColors.Border, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                "Masukkan email/label akun dan token OAuth. Demi keamanan, token disamarkan dan langsung dienkripsi ke Android Keystore begitu disimpan.",
                style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = newAccountEmail,
                onValueChange = { newAccountEmail = it },
                singleLine = true,
                label = { Text("Email / Label Akun Google (mis. kerja@gmail.com)") },
                modifier = Modifier.fillMaxWidth().testTag("manual_account_email_input")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newAccessToken,
                onValueChange = { newAccessToken = it },
                singleLine = true,
                label = { Text("OAuth 2.0 Access Token (disembunyikan)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("manual_access_token_input")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newRefreshToken,
                onValueChange = { newRefreshToken = it },
                singleLine = true,
                label = { Text("OAuth 2.0 Refresh Token (opsional, disembunyikan)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("manual_refresh_token_input")
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val labelEmail = newAccountEmail.trim().ifEmpty { "Akun Google #${accounts.size + 1}" }
                    val acct = GoogleAccount(
                        id = if (labelEmail.contains("@")) labelEmail.lowercase() else UUID.randomUUID().toString(),
                        email = labelEmail,
                        displayName = labelEmail.substringBefore("@"),
                        accessToken = newAccessToken.trim(),
                        refreshToken = newRefreshToken.trim(),
                        clientId = s.googleClientId.ifBlank { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID },
                        gmailConnected = reqGmail,
                        calendarConnected = reqCalendar,
                        driveConnected = reqDrive,
                    )
                    onSettingsChange(s.withAddedOrUpdatedGoogleAccount(acct))
                    newAccountEmail = ""
                    newAccessToken = ""
                    newRefreshToken = ""
                    showAddManual = false
                },
                enabled = newAccessToken.isNotBlank() || newRefreshToken.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().testTag("save_manual_account_button")
            ) {
                Text("Tambah & Enkripsi Akun Google")
            }
        }
    }

    // Dialog Persetujuan Eksplisit Pengguna sebelum OAuth 2.0 PKCE dimulai
    if (showConsentDialog) {
        val selectedScopes = GoogleWorkspaceConfig.scopesFor(
            gmail = reqGmail,
            calendar = reqCalendar,
            drive = reqDrive,
        )
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Konfirmasi Izin OAuth 2.0 (PKCE)") },
            text = {
                Column {
                    Text(
                        "Anda akan membuka halaman otorisasi resmi Google dengan proteksi PKCE (S256) dan scope minimum berikut:",
                        style = TextStyle(fontSize = 13.5.sp, color = MythosColors.Ink)
                    )
                    Spacer(Modifier.height(8.dp))
                    selectedScopes.forEach { sc ->
                        Text("• ${sc.substringAfterLast('/')}", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MythosColors.Ink))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Token hanya disimpan secara terenkripsi di Android Keystore perangkat Anda dan setiap pengiriman email tetap memerlukan persetujuan eksplisit Anda.",
                        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConsentDialog = false
                        runCatching {
                            val (verifier, challenge) = GoogleWorkspaceConfig.generatePkce()
                            val redirectUri = GoogleWorkspaceConfig.DEFAULT_REDIRECT_URI
                            val session = PkceOAuthSession(
                                codeVerifier = verifier,
                                clientId = s.googleClientId.ifBlank { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID },
                                redirectUri = redirectUri,
                                gmailRequested = reqGmail,
                                calendarRequested = reqCalendar,
                                driveRequested = reqDrive,
                                loginHint = loginHintInput.trim(),
                            )
                            onStartPkceSession(session)
                            val url = GoogleWorkspaceConfig.buildAuthorizationUrl(
                                clientId = session.clientId,
                                redirectUri = redirectUri,
                                codeChallenge = challenge,
                                scopes = selectedScopes,
                                loginHint = session.loginHint.takeIf { it.isNotEmpty() },
                            )
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    modifier = Modifier.testTag("confirm_oauth_consent_button")
                ) {
                    Text("Setuju & Lanjutkan ke Google")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) { Text("Batal") }
            }
        )
    }

    // Dialog Konfirmasi Hapus Akun Google
    accountToDelete?.let { acct ->
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text("Hapus akun Google?") },
            text = {
                Text("Akun \"${acct.email}\" beserta token OAuth terenkripsinya akan dihapus dari perangkat ini.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSettingsChange(s.withRemovedGoogleAccount(acct.id))
                        accountToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text("Hapus", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun GitHubIntegrationSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    testResult: IntegrationCheckResult?,
    isTesting: Boolean,
    onTestGitHub: () -> Unit,
) {
    Label("Build & Repository (GitHub Actions)")
    Text(
        "Semua build/tes agen berjalan di GitHub Actions: proyek di-push ke branch mythos/<proyek> di repo di bawah, lalu hasil run dibaca agen. " +
            "Token disimpan terenkripsi dengan Android Keystore dan tidak pernah ditampilkan.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedTextField(
        value = s.githubRepo,
        onValueChange = { onSettingsChange(s.copy(githubRepo = it)) },
        singleLine = true,
        label = { Text("Repo (pemilik/nama)") },
        modifier = Modifier.fillMaxWidth().testTag("github_repo_input")
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = s.githubToken,
        onValueChange = { onSettingsChange(s.copy(githubToken = it)) },
        singleLine = true,
        label = { Text("Token GitHub (disembunyikan)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("github_token_input")
    )
    if (s.githubToken.isNotBlank()) {
        Text(
            "Token tersimpan (disamarkan): ${ExtraKeys.mask(s.githubToken)}",
            style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    Text(
        "Token fine-grained untuk repo itu dengan izin Contents: Read and write, Workflows: Read and write, dan Actions: Read. " +
            "Disimpan terenkripsi (Android Keystore).",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
    StatusRow("Repo valid", GitHubConfig.isValidRepo(s.githubRepo), null, {})
    StatusRow("Token terisi", s.githubToken.isNotBlank(), if (s.githubToken.isNotBlank()) "Hapus token" else null) {
        onSettingsChange(s.copy(githubToken = ""))
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onTestGitHub,
        enabled = !isTesting && s.github != null,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("test_github_connection_button")
    ) {
        Text(if (isTesting) "Menguji koneksi GitHub…" else "Uji Koneksi GitHub & Izin Repo")
    }
    testResult?.let {
        Spacer(Modifier.height(8.dp))
        CheckResultBanner(it)
    }
}

@Composable
private fun WebSearchIntegrationSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    braveResult: IntegrationCheckResult?,
    tavilyResult: IntegrationCheckResult?,
    isTesting: Boolean,
    onTestSearch: () -> Unit,
) {
    Label("Pencarian web (opsional, gratis)")
    Text(
        "Tanpa kunci pun agen mencari lewat DuckDuckGo, Bing, dan Wikipedia. Kunci paket gratis di bawah membuat hasil lebih stabil " +
            "(tanpa captcha): Brave Search API (api.search.brave.com) atau Tavily (tavily.com). Disimpan terenkripsi.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedTextField(
        value = s.braveKey,
        onValueChange = { onSettingsChange(s.copy(braveKey = it)) },
        singleLine = true,
        label = { Text("Kunci Brave Search (disembunyikan)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    if (s.braveKey.isNotBlank()) {
        Text("Tersimpan: ${ExtraKeys.mask(s.braveKey)}", style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint), modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = s.tavilyKey,
        onValueChange = { onSettingsChange(s.copy(tavilyKey = it)) },
        singleLine = true,
        label = { Text("Kunci Tavily (disembunyikan)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    if (s.tavilyKey.isNotBlank()) {
        Text("Tersimpan: ${ExtraKeys.mask(s.tavilyKey)}", style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint), modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onTestSearch,
        enabled = !isTesting && (s.braveKey.isNotBlank() || s.tavilyKey.isNotBlank()),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("test_search_connection_button")
    ) {
        Text(if (isTesting) "Menguji kunci pencarian web…" else "Uji Koneksi Kunci Pencarian Web")
    }
    braveResult?.let {
        Spacer(Modifier.height(8.dp))
        CheckResultBanner(it)
    }
    tavilyResult?.let {
        Spacer(Modifier.height(6.dp))
        CheckResultBanner(it)
    }
}

@Composable
private fun PermissionModeSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
) {
    Label("Mode izin agen")
    Text(
        "Menentukan kapan agen harus bertanya sebelum bertindak. Menghapus file, mengirim email eksternal, dan build workflow baru selalu meminta persetujuan di semua mode.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 8.dp)
    )
    val modes = listOf(
        PermissionMode.ACCEPT_EDITS to ("Tulis otomatis" to "Agen boleh membuat dan mengubah file tanpa bertanya (bawaan)."),
        PermissionMode.DEFAULT to ("Tanya dulu" to "Setiap menulis atau mengubah file meminta persetujuan Anda."),
        PermissionMode.PLAN to ("Rencana saja" to "Agen hanya membaca dan menyusun rencana; baru boleh mengubah file setelah Anda menyetujui rencananya."),
    )
    modes.forEach { (m, text) ->
        val on = s.permissionMode == m
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (on) Color.White else Color.Transparent)
                .clickable(role = Role.RadioButton) { onSettingsChange(s.copy(permissionMode = m)) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = on, onClick = null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(text.first, style = TextStyle(fontSize = 16.sp, color = MythosColors.Ink))
                Text(text.second, style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint))
            }
        }
    }
    Text(
        "Perubahan berlaku pada tugas berikutnya.",
        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun StatusRow(text: String, ok: Boolean, actionLabel: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "✓" else "✕",
            style = TextStyle(fontSize = 15.sp, color = if (ok) Color(0xFF2E7D32) else Color(0xFFB3261E))
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = TextStyle(fontSize = 14.sp, color = MythosColors.Ink), modifier = Modifier.weight(1f))
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun KeysSection(
    provider: ProviderChoice,
    main: String,
    input: String,
    onInputChange: (String) -> Unit,
    onMainChange: (String) -> Unit,
    extras: List<String>,
    onAdd: (String) -> String,
    onRemove: (String) -> Unit,
    onRemoveMain: (String) -> Unit,
    hint: String?,
) {
    var message by remember(provider) { mutableStateOf<String?>(null) }
    var removing by remember(provider) { mutableStateOf<String?>(null) }
    val all = (listOf(main).filter { it.isNotBlank() } + extras).distinct()
    val small = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
    Label("Kunci API ${provider.label}" + if (all.isNotEmpty()) " (${all.size})" else "")
    OutlinedTextField(
        value = input,
        onValueChange = { onInputChange(it); message = null },
        minLines = 2,
        maxLines = 4,
        label = { Text("Tempel satu atau banyak kunci (disembunyikan saat disimpan)") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("api_key_input")
    )
    hint?.let { Text(it, style = small, modifier = Modifier.padding(top = 6.dp)) }
    Text(
        "Boleh banyak sekaligus (pisahkan dengan baris baru atau spasi). Kunci pertama jadi kunci utama; " +
            "yang lain dipakai bergantian bila kunci utama kena batas laju atau ditolak. Disimpan terenkripsi (Android Keystore) di perangkat ini.",
        style = small,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
    )
    Button(
        onClick = {
            val text = input.trim()
            if (main.isBlank()) {
                val r = ExtraKeys.parse(text, extras)
                if (r.added.isEmpty()) {
                    message = onAdd(text)
                } else {
                    onMainChange(r.added.first())
                    val rest = r.added.drop(1)
                    if (rest.isNotEmpty()) onAdd(rest.joinToString("\n"))
                    message = "Kunci utama tersimpan" + (if (rest.isNotEmpty()) ", ${rest.size} kunci cadangan ditambahkan" else "") + "."
                    onInputChange("")
                }
            } else {
                message = onAdd(text)
                if (message?.startsWith("Ditambahkan") == true) onInputChange("")
            }
        },
        enabled = input.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
        modifier = Modifier.testTag("add_api_key_button")
    ) { Text("Tambah kunci") }
    message?.let {
        Text(it, style = TextStyle(fontSize = 13.sp, color = MythosColors.Ink), modifier = Modifier.padding(top = 8.dp))
    }
    all.forEachIndexed { i, k ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${provider.label} #${i + 1}" + if (i == 0) " (utama)" else " (cadangan)",
                    style = TextStyle(fontSize = 14.sp, color = MythosColors.Ink)
                )
                Text(ExtraKeys.mask(k), style = small)
            }
            if (i > 0) {
                TextButton(onClick = {
                    val oldMain = main
                    onMainChange(k)
                    onRemove(k)
                    if (oldMain.isNotBlank()) onAdd(oldMain)
                }) { Text("Jadikan Utama") }
            }
            TextButton(onClick = { removing = k }) { Text("Hapus") }
        }
    }
    removing?.let { k ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Hapus kunci ini?") },
            text = { Text(ExtraKeys.mask(k) + " akan dihapus dari aplikasi sekarang juga. Anda bisa menempelkannya lagi nanti.") },
            confirmButton = {
                TextButton(onClick = {
                    if (k == main) {
                        val newMain = extras.firstOrNull { it != k }.orEmpty()
                        onMainChange(newMain)
                        onRemoveMain(newMain)
                        if (k in extras) onRemove(k)
                    } else onRemove(k)
                    removing = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Batal") } },
        )
    }
}

private sealed interface ScanUi {
    data object Idle : ScanUi
    data object Scanning : ScanUi
    data class Done(val models: List<ModelStatus>) : ScanUi
    data class Error(val message: String) : ScanUi
}

@Composable
private fun ModelPicker(ui: ScanUi, selected: String, onPick: (String) -> Unit) {
    val hint = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
    ModelRow(
        dot = Color(0xFF2E7D32), title = "Otomatis (disarankan)",
        subtitle = "Aplikasi memilih model terbaik yang jalan dan berganti sendiri bila error/sibuk.",
        selected = selected.isBlank(), enabled = true
    ) { onPick("") }
    when (ui) {
        ScanUi.Idle -> Text("Isi API key untuk memindai daftar model langsung dari server penyedia.", style = hint, modifier = Modifier.padding(top = 6.dp))
        ScanUi.Scanning -> Text("Memeriksa model yang tersedia untuk kunci ini…", style = hint, modifier = Modifier.padding(top = 6.dp))
        is ScanUi.Error -> Text(
            "Daftar model tidak bisa diambil: ${ui.message}. Anda tetap bisa memakai preset cepat di atas, mode otomatis, atau ketik nama model manual.",
            style = hint, modifier = Modifier.padding(top = 6.dp)
        )
        is ScanUi.Done -> ui.models.forEach { m ->
            val (color, label) = when (m.state) {
                ModelState.READY -> Color(0xFF2E7D32) to "siap"
                ModelState.UNTESTED -> Color(0xFF757575) to "cadangan (belum diuji)"
                ModelState.BUSY -> Color(0xFFB26A00) to "sibuk: ${m.detail}"
                ModelState.FAILED -> Color(0xFFB3261E) to "tidak bisa: ${m.detail}"
            }
            ModelRow(color, m.model, label, selected == m.model, m.state != ModelState.FAILED) { onPick(m.model) }
        }
    }
}

@Composable
private fun ModelRow(dot: Color, title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(shape)
            .background(if (selected) MythosColors.Card else Color.Transparent)
            .border(1.dp, if (selected) MythosColors.Ink else MythosColors.Border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 14.sp, color = if (enabled) MythosColors.Ink else MythosColors.Hint))
            Text(subtitle, style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint))
        }
    }
}

private fun keyHint(p: ProviderChoice): String? = when (p) {
    ProviderChoice.HUGGINGFACE -> "Token akses Hugging Face (hf_…) dengan izin \"Make calls to Inference Providers\"."
    ProviderChoice.GITHUB_MODELS -> "Personal access token GitHub dengan izin \"models:read\"."
    ProviderChoice.OPENROUTER -> "Kunci API OpenRouter (sk-or-…)."
    else -> null
}
