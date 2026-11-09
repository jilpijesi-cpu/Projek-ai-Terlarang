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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.PkceOAuthSession
import com.mythos.app.PopularModels
import com.mythos.app.ProviderChoice
import com.mythos.app.UserSettings
import com.mythos.app.agent.ExtraKeys
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

/** Satu fungsi = satu tab pengaturan (tanpa duplikasi). */
enum class SettingsSection(val title: String, val shortLabel: String) {
    MODEL("AI & Model", "AI & Model"),
    WORKSPACE("Akun Google", "Google"),
    GITHUB("GitHub", "GitHub"),
    SEARCH("Pencarian Web", "Pencarian"),
    PERMISSION("Izin Agen", "Izin"),
    INTEGRATIONS("Pengaturan", "AI & Model"),
}

private val VisibleSettingsTabs = listOf(
    SettingsSection.MODEL,
    SettingsSection.WORKSPACE,
    SettingsSection.GITHUB,
    SettingsSection.SEARCH,
    SettingsSection.PERMISSION,
)

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
    val initialTab = if (section == SettingsSection.INTEGRATIONS) SettingsSection.MODEL else section
    var activeSection by remember(section) { mutableStateOf(initialTab) }
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
            Text(
                "Pengaturan",
                style = TextStyle(fontFamily = MythosSerif, fontSize = 23.sp, color = MythosColors.Ink)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 5 tab tunggal: satu fungsi satu tab
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VisibleSettingsTabs.forEach { sec ->
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
            SettingsSection.MODEL, SettingsSection.INTEGRATIONS -> ModelAndProviderSection(
                s = s,
                onSettingsChange = { s = it },
                loadFor = loadFor,
                scanUi = scanUi,
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
                            testResults["AI (${s.provider.label})"] = IntegrationTester.testAiProvider(s)
                        } finally {
                            testingKey = null
                        }
                    }
                }
            )

            SettingsSection.WORKSPACE -> GoogleWorkspaceSection(
                s = s,
                onSettingsChange = { updated ->
                    s = updated
                    onPersistImmediate(updated)
                },
                testResults = testResults,
                testingKey = testingKey,
                onTestAllWorkspace = {
                    if (testingKey != null) return@GoogleWorkspaceSection
                    testingKey = "WORKSPACE_ALL"
                    scope.launch {
                        try {
                            IntegrationTester.testAllGoogleServices(s).forEach { testResults[it.service] = it }
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

        Spacer(Modifier.height(24.dp))
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
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_settings_button")
        ) { Text("Simpan", fontSize = 16.sp) }
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
    // 1. Penyedia AI
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

    Spacer(Modifier.height(16.dp))

    // 2. Model (Satu pemilih model terpadu tanpa duplikasi)
    Label("Model")
    if (s.provider.lockedModel != null) {
        Text(
            s.provider.lockedModel.orEmpty(),
            style = TextStyle(fontSize = 14.5.sp, color = MythosColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MythosColors.Card)
                .border(1.dp, MythosColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        )
    } else {
        val scannedReady = (scanUi as? ScanUi.Done)?.models
            ?.filter { it.state != ModelState.FAILED }
            ?.map { it.model }
            .orEmpty()
        val modelChoices = (PopularModels.forProvider(s.provider) + scannedReady).distinct()

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PresetModelChip(
                label = "Otomatis",
                selected = s.model.isBlank(),
                onClick = {
                    val updated = s.copy(model = "")
                    onSettingsChange(updated)
                    onPersistImmediate(updated)
                }
            )
            modelChoices.forEach { m ->
                PresetModelChip(
                    label = m,
                    selected = s.model == m,
                    onClick = {
                        val updated = s.copy(model = m)
                        onSettingsChange(updated)
                        onPersistImmediate(updated)
                    }
                )
            }
        }

        OutlinedTextField(
            value = s.model,
            onValueChange = { onSettingsChange(s.copy(model = it)) },
            singleLine = true,
            label = { Text("Model kustom (kosong = otomatis)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("custom_model_input")
        )
    }

    // 3. Kunci API atau Alamat Ollama
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
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("test_ai_connection_button")
    ) {
        Text(if (isTestingAi) "Menguji…" else "Uji Koneksi AI")
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
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 13.sp, color = if (selected) Color.White else MythosColors.Ink)
        )
    }
}

@Composable
private fun GoogleWorkspaceSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    testResults: Map<String, IntegrationCheckResult>,
    testingKey: String?,
    onTestAllWorkspace: () -> Unit,
    onStartPkceSession: (PkceOAuthSession) -> Unit,
    onLoadPkceSession: () -> PkceOAuthSession?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showConsentDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<GoogleAccount?>(null) }
    var showAdvancedInput by remember { mutableStateOf(false) }

    var authCodeInput by remember { mutableStateOf("") }
    var pkceStatusMsg by remember { mutableStateOf<String?>(null) }
    var exchangingPkce by remember { mutableStateOf(false) }

    var newAccountEmail by remember { mutableStateOf("") }
    var newAccessToken by remember { mutableStateOf("") }
    var newRefreshToken by remember { mutableStateOf("") }

    val accounts = s.allGoogleAccounts
    val activeAccount = s.activeGoogleAccount

    // 1. Akun Google Tersimpan
    Label("Akun Google")
    if (accounts.isEmpty()) {
        Text(
            "Belum ada akun terhubung.",
            style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    } else {
        accounts.forEach { acct ->
            val isActive = acct.id == activeAccount?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MythosColors.Card)
                    .border(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) MythosColors.Ink else MythosColors.Border,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF2E7D32) else Color(0xFF9E9E9E))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        acct.email,
                        style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = MythosColors.Ink)
                    )
                    Text(
                        if (isActive) "Aktif • ${acct.maskedTokenPreview()}" else acct.maskedTokenPreview(),
                        style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
                    )
                }
                if (!isActive) {
                    TextButton(
                        onClick = { onSettingsChange(s.withActiveGoogleAccount(acct.id)) },
                        modifier = Modifier.testTag("switch_account_${acct.id}")
                    ) { Text("Pakai") }
                }
                TextButton(
                    onClick = { accountToDelete = acct },
                    modifier = Modifier.testTag("remove_account_${acct.id}")
                ) { Text("Hapus", color = Color(0xFFB3261E)) }
            }
        }
    }

    // 2. Layanan Google (Satu Switch per layanan, tanpa tombol ganda)
    Spacer(Modifier.height(14.dp))
    Label("Layanan Google")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MythosColors.Card)
            .border(1.dp, MythosColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
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
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    kind.label,
                    style = TextStyle(fontSize = 14.5.sp, color = MythosColors.Ink),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isServiceConnected,
                    onCheckedChange = { connected ->
                        onSettingsChange(s.withServiceConnection(kind, connected))
                    },
                    modifier = Modifier.testTag("toggle_service_${kind.id}")
                )
            }
            testResults[kind.label]?.let { res ->
                CheckResultBanner(res)
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (activeAccount != null) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onTestAllWorkspace,
            enabled = testingKey == null,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("test_all_workspace_button")
        ) {
            Text(if (testingKey == "WORKSPACE_ALL") "Menguji…" else "Uji Koneksi Google")
        }
    }

    // 3. Tambah Akun Baru
    Spacer(Modifier.height(16.dp))
    Label("Tambah Akun Google")
    OutlinedTextField(
        value = s.googleClientId,
        onValueChange = { onSettingsChange(s.copy(googleClientId = it)) },
        singleLine = true,
        label = { Text("OAuth Client ID") },
        modifier = Modifier.fillMaxWidth().testTag("google_client_id_input")
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { showConsentDialog = true },
        enabled = (s.gmailEnabled || s.calendarEnabled || s.driveEnabled) && s.googleClientId.isNotBlank(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().testTag("start_oauth_pkce_button")
    ) {
        Text("Login dengan Google")
    }

    TextButton(
        onClick = { showAdvancedInput = !showAdvancedInput },
        modifier = Modifier.padding(top = 4.dp).testTag("toggle_manual_account_button")
    ) {
        Text(if (showAdvancedInput) "Sembunyikan input manual" else "Input kode / token manual")
    }

    if (showAdvancedInput) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MythosColors.Card)
                .border(1.dp, MythosColors.Border, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = authCodeInput,
                onValueChange = { authCodeInput = it; pkceStatusMsg = null },
                singleLine = true,
                label = { Text("Kode otorisasi / URL redirect") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("pkce_auth_code_input")
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val pkceSession = onLoadPkceSession()
                    if (pkceSession == null) {
                        pkceStatusMsg = "Ketuk \"Login dengan Google\" terlebih dahulu."
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
                                onSettingsChange(s.withAddedOrUpdatedGoogleAccount(newAcct))
                                authCodeInput = ""
                                pkceStatusMsg = "✓ Akun ${newAcct.email} terhubung."
                            },
                            onFailure = { err ->
                                pkceStatusMsg = "✕ ${SensitiveDataGuard.redactSensitiveCredentials(err.message ?: "Gagal menukar kode")}"
                            }
                        )
                    }
                },
                enabled = authCodeInput.isNotBlank() && !exchangingPkce,
                colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().testTag("exchange_pkce_code_button")
            ) {
                Text(if (exchangingPkce) "Memproses…" else "Tukar Kode Otorisasi")
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

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newAccountEmail,
                onValueChange = { newAccountEmail = it },
                singleLine = true,
                label = { Text("Email akun") },
                modifier = Modifier.fillMaxWidth().testTag("manual_account_email_input")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newAccessToken,
                onValueChange = { newAccessToken = it },
                singleLine = true,
                label = { Text("Access Token") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("manual_access_token_input")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newRefreshToken,
                onValueChange = { newRefreshToken = it },
                singleLine = true,
                label = { Text("Refresh Token (opsional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("manual_refresh_token_input")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val labelEmail = newAccountEmail.trim().ifEmpty { "Akun Google #${accounts.size + 1}" }
                    val acct = GoogleAccount(
                        id = if (labelEmail.contains("@")) labelEmail.lowercase() else UUID.randomUUID().toString(),
                        email = labelEmail,
                        displayName = labelEmail.substringBefore("@"),
                        accessToken = newAccessToken.trim(),
                        refreshToken = newRefreshToken.trim(),
                        clientId = s.googleClientId.ifBlank { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID },
                        gmailConnected = s.gmailEnabled,
                        calendarConnected = s.calendarEnabled,
                        driveConnected = s.driveEnabled,
                    )
                    onSettingsChange(s.withAddedOrUpdatedGoogleAccount(acct))
                    newAccountEmail = ""
                    newAccessToken = ""
                    newRefreshToken = ""
                    showAdvancedInput = false
                },
                enabled = newAccessToken.isNotBlank() || newRefreshToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("save_manual_account_button")
            ) {
                Text("Simpan Token Manual")
            }
        }
    }

    if (showConsentDialog) {
        val selectedScopes = GoogleWorkspaceConfig.scopesFor(
            gmail = s.gmailEnabled,
            calendar = s.calendarEnabled,
            drive = s.driveEnabled,
        )
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Hubungkan Akun Google") },
            text = {
                Column {
                    Text("Izin layanan yang diminta:", style = TextStyle(fontSize = 13.5.sp, color = MythosColors.Ink))
                    Spacer(Modifier.height(6.dp))
                    selectedScopes.forEach { sc ->
                        Text("• ${sc.substringAfterLast('/')}", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MythosColors.Ink))
                    }
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
                                gmailRequested = s.gmailEnabled,
                                calendarRequested = s.calendarEnabled,
                                driveRequested = s.driveEnabled,
                            )
                            onStartPkceSession(session)
                            val url = GoogleWorkspaceConfig.buildAuthorizationUrl(
                                clientId = session.clientId,
                                redirectUri = redirectUri,
                                codeChallenge = challenge,
                                scopes = selectedScopes,
                            )
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    modifier = Modifier.testTag("confirm_oauth_consent_button")
                ) {
                    Text("Lanjutkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) { Text("Batal") }
            }
        )
    }

    accountToDelete?.let { acct ->
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text("Hapus akun?") },
            text = { Text("Hapus \"${acct.email}\" dari perangkat ini?") },
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
    Label("GitHub Actions")
    OutlinedTextField(
        value = s.githubRepo,
        onValueChange = { onSettingsChange(s.copy(githubRepo = it)) },
        singleLine = true,
        label = { Text("Repository (pemilik/nama)") },
        modifier = Modifier.fillMaxWidth().testTag("github_repo_input")
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = s.githubToken,
        onValueChange = { onSettingsChange(s.copy(githubToken = it)) },
        singleLine = true,
        label = { Text("Token GitHub") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("github_token_input")
    )
    if (s.githubToken.isNotBlank()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Tersimpan: ${ExtraKeys.mask(s.githubToken)}",
                style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onSettingsChange(s.copy(githubToken = "")) }) {
                Text("Hapus token", color = Color(0xFFB3261E))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onTestGitHub,
        enabled = !isTesting && s.github != null,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("test_github_connection_button")
    ) {
        Text(if (isTesting) "Menguji…" else "Uji Koneksi GitHub")
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
    Label("Kunci API Pencarian Web (Opsional)")
    OutlinedTextField(
        value = s.braveKey,
        onValueChange = { onSettingsChange(s.copy(braveKey = it)) },
        singleLine = true,
        label = { Text("Kunci Brave Search") },
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
        label = { Text("Kunci Tavily") },
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
        Text(if (isTesting) "Menguji…" else "Uji Koneksi Pencarian")
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
    Label("Izin Agen")
    val modes = listOf(
        PermissionMode.ACCEPT_EDITS to ("Tulis otomatis" to "Buat & ubah file langsung"),
        PermissionMode.DEFAULT to ("Tanya dulu" to "Minta izin sebelum ubah file"),
        PermissionMode.PLAN to ("Rencana saja" to "Susun rencana sebelum ubah file"),
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
                Text(text.first, style = TextStyle(fontSize = 15.5.sp, color = MythosColors.Ink))
                Text(text.second, style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint))
            }
        }
    }
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
) {
    var message by remember(provider) { mutableStateOf<String?>(null) }
    var removing by remember(provider) { mutableStateOf<String?>(null) }
    val all = (listOf(main).filter { it.isNotBlank() } + extras).distinct()
    val small = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
    Label("Kunci API ${provider.label}" + if (all.isNotEmpty()) " (${all.size})" else "")
    OutlinedTextField(
        value = input,
        onValueChange = { onInputChange(it); message = null },
        singleLine = true,
        label = { Text("Tempel kunci API") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("api_key_input")
    )
    Spacer(Modifier.height(8.dp))
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
                    message = "Kunci tersimpan."
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
    ) { Text("Tambah Kunci") }
    message?.let {
        Text(it, style = TextStyle(fontSize = 12.5.sp, color = MythosColors.Ink), modifier = Modifier.padding(top = 6.dp))
    }
    all.forEachIndexed { i, k ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${provider.label} #${i + 1}" + if (i == 0) " (utama)" else "",
                    style = TextStyle(fontSize = 13.5.sp, color = MythosColors.Ink)
                )
                Text(ExtraKeys.mask(k), style = small)
            }
            if (i > 0) {
                TextButton(onClick = {
                    val oldMain = main
                    onMainChange(k)
                    onRemove(k)
                    if (oldMain.isNotBlank()) onAdd(oldMain)
                }) { Text("Utama") }
            }
            TextButton(onClick = { removing = k }) { Text("Hapus", color = Color(0xFFB3261E)) }
        }
    }
    removing?.let { k ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Hapus kunci?") },
            text = { Text("Hapus ${ExtraKeys.mask(k)}?") },
            confirmButton = {
                TextButton(onClick = {
                    if (k == main) {
                        val newMain = extras.firstOrNull { it != k }.orEmpty()
                        onMainChange(newMain)
                        onRemoveMain(newMain)
                        if (k in extras) onRemove(k)
                    } else onRemove(k)
                    removing = null
                }) { Text("Hapus", color = Color(0xFFB3261E)) }
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
