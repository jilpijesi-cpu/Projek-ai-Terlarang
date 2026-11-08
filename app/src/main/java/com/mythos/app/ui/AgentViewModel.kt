package com.mythos.app.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.mythos.app.agent.supportsVision
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mythos.app.AgentService
import com.mythos.app.ProviderChoice
import com.mythos.app.SettingsStore
import com.mythos.app.UserSettings
import com.mythos.app.agent.AgentEvent
import com.mythos.app.agent.LatencyTrace
import com.mythos.app.agent.Approver
import com.mythos.app.agent.BuildService
import com.mythos.app.agent.CheckpointStore
import com.mythos.app.agent.FileChange
import com.mythos.app.agent.TaskJournal
import com.mythos.app.agent.UndoReport
import com.mythos.app.agent.FailoverClient
import com.mythos.app.agent.GitHubBuildService
import com.mythos.app.agent.HttpGoogleWorkspaceService
import com.mythos.app.agent.KeyPool
import com.mythos.app.agent.LlmClient
import com.mythos.app.agent.LlmException
import com.mythos.app.agent.ModelResolver
import com.mythos.app.agent.ModelState
import com.mythos.app.agent.ModelStatus
import com.mythos.app.agent.ExtraKeys
import com.mythos.app.agent.toSpecs
import com.mythos.app.agent.MythosAgent
import com.mythos.app.agent.ProjectSandbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    /** Kunci tambahan per penyedia (selain Otomatis dan Ollama). */
    var extraKeys by mutableStateOf<Map<ProviderChoice, List<String>>>(
        ProviderChoice.values().filter { it.needsKey }.associateWith { store.loadExtraKeys(it) }
    )
        private set
    private val uiPrefs = app.getSharedPreferences("mythos_ui", android.content.Context.MODE_PRIVATE)

    var settings by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf(Screen.HOME)
    /** Naik tiap percakapan diganti (baru/dibuka dari riwayat) agar layar obrolan mulai bersih, tanpa memutar ulang animasi lama. */
    var chatEpoch by mutableIntStateOf(0)
    /** Chat incognito: tidak disimpan ke riwayat. */
    var incognito by mutableStateOf(false)
        private set
    /** Pesan singkat (Toast) dari VM; ditampilkan lalu dikosongkan oleh MainActivity. */
    var toast by mutableStateOf<String?>(null)
    /** Draf per layar: bertahan saat rotasi dan saat berpindah ke Pengaturan lalu kembali (remember biasa menghapusnya). */
    var homeDraft by mutableStateOf("")
    var chatDraft by mutableStateOf("")
    /** Pilihan incognito di beranda sebelum pesan dikirim. */
    var homeIncognito by mutableStateOf(false)
    /** Isian form Pengaturan yang belum disimpan (null = belum ada perubahan). */
    var settingsDraft by mutableStateOf<UserSettings?>(null)
    /** Dialog "agen masih bekerja, tetap keluar?". */
    var askLeave by mutableStateOf(false)
        private set
    /** Perubahan tugas yang menunggu konfirmasi "Batalkan tugas ini". */
    var pendingUndo by mutableStateOf<ChangesItem?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var projectName by mutableStateOf<String?>(null)
        private set
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var pendingQuestion by mutableStateOf<QuestionRequest?>(null)
        private set
    val items = mutableStateListOf<ChatItem>()

    /** Konektor "Pencarian web": bila mati, agen tidak punya WebSearch/WebFetch dan mode Riset tak bisa dipakai. */
    var webEnabled by mutableStateOf(uiPrefs.getBoolean("web_enabled", true))
        private set
    /** Mode Riset: agen wajib mencari beberapa sumber, membaca halamannya, lalu menulis laporan bersumber. */
    var researchOn by mutableStateOf(uiPrefs.getBoolean("research_on", false) && uiPrefs.getBoolean("web_enabled", true))
        private set
    /** Proyek yang sedang dilihat di layar Code. */
    var browseId by mutableStateOf<String?>(null)
        private set
    private var workspaceReturn = Screen.HOME

    fun setWeb(on: Boolean) {
        webEnabled = on
        if (!on) researchOn = false
        uiPrefs.edit().putBoolean("web_enabled", on).putBoolean("research_on", researchOn).apply()
    }

    fun setResearch(on: Boolean) {
        if (on && !webEnabled) return
        researchOn = on
        uiPrefs.edit().putBoolean("research_on", on).apply()
    }

    /** Hasil input suara: ditambahkan ke draf layar yang sedang terbuka. */
    fun appendSpeech(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (screen == Screen.CHAT) chatDraft = if (chatDraft.isBlank()) t else chatDraft.trimEnd() + " " + t
        else homeDraft = if (homeDraft.isBlank()) t else homeDraft.trimEnd() + " " + t
    }

    // ---------- ruang kerja: Proyek / Code / Artefak ----------
    fun openWorkspace(s: Screen) {
        if (screen == Screen.HOME || screen == Screen.CHAT) workspaceReturn = screen
        screen = s
    }

    fun openCode(id: String? = null) {
        browseId = id ?: projectDir?.name ?: chats.firstOrNull()?.id
        openWorkspace(Screen.CODE)
    }

    fun selectBrowse(id: String) {
        browseId = id
    }

    /** Proyek yang bisa dilihat: riwayat tersimpan + chat incognito yang sedang terbuka (belum ada di riwayat). */
    fun workspaceProjects(): List<ChatMeta> {
        val list = chats.toList()
        val cur = projectDir
        return if (cur != null && list.none { it.id == cur.name } && cur.isDirectory)
            listOf(ChatMeta(cur.name, "Chat saat ini", cur.path, System.currentTimeMillis())) + list
        else list
    }

    fun exportProject(path: String, onReady: (File?) -> Unit) {
        val dir = File(path)
        viewModelScope.launch(Dispatchers.IO) {
            var failed = false
            val out = try {
                if (dir.isDirectory) zipProject(dir) else null
            } catch (e: Exception) {
                failed = true
                null
            }
            withContext(Dispatchers.Main) {
                if (failed) toast = "Gagal membuat ZIP proyek (penyimpanan penuh atau berkas hilang)." else onReady(out)
            }
        }
    }

    /** Alat yang sedang ditulis model (JSON-nya masih mengalir); dipakai UI untuk label baris hidup. */
    var liveActivity by mutableStateOf<LiveActivity?>(null)
        private set

    /** Sub-agen (Task) yang sedang berjalan; tampil sebagai satu baris kecil, tidak menambah baris langkah. */
    var subAgents by mutableStateOf(SubAgentStatus())
        private set
    private val timeline = TimelineReducer(items) { nid() }

    private fun resetLive() {
        timeline.reset()
        liveActivity = null
        subAgents = SubAgentStatus()
    }
    val chats = mutableStateListOf<ChatMeta>()

    private var job: Job? = null
    private var projectDir: File? = null
    private var counter = 0L
    private var pendingPrompt: String? = null
    private var pendingIncognito: Boolean = false
    private var settingsReturn = Screen.HOME
    private val history = mutableListOf<Pair<String, String>>()   // (tugas, ringkasan hasil)
    private var finalSeen = false       // giliran ini berakhir dengan jawaban akhir?
    private var runNote = ""            // alasan singkat bila giliran ini gagal
    private var buildWarned = false     // sudah menampilkan info "Build GitHub Actions belum diatur" pada obrolan ini?
    private var persistJob: Job? = null
    private val persistLock = Mutex()
    private val deletedIds = HashSet<String>()
    private var persistSeq = 0L
    private var savedSeq = 0L
    private val chatStore = ChatStore(File(app.filesDir, "chats"))

    init {
        refreshChats()
        cleanIncognito()
        if (store.unreadableSecret) {
            toast = "Kunci/token tersimpan tidak bisa dibaca di perangkat ini (mis. data dipulihkan dari cadangan). Masukkan ulang di Pengaturan."
        }
    }

    /** Proyek chat incognito dan berkas sementara disimpan di cache dan dibersihkan tiap proses baru (VM baru = tidak ada chat yang sedang terbuka). */
    private fun cleanIncognito() {
        val pendingCam = uiPrefs.getString("pending_camera_file", null)?.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (pendingCam != null && pendingCam.exists()) cameraFile = pendingCam
        viewModelScope.launch(Dispatchers.IO) {
            val cache = getApplication<Application>().cacheDir
            File(cache, "incognito").deleteRecursively()
            File(cache, "incognito-checkpoints").deleteRecursively()
            File(cache, "staged").deleteRecursively()
            if (pendingCam == null) File(cache, "camera").deleteRecursively()
            File(cache, "exports").deleteRecursively()
        }
    }

    /** Titik pulih disimpan di luar folder proyek (tak terlihat agen, tak ikut ZIP/GitHub); chat incognito di cache. */
    private fun checkpointStore(incog: Boolean = incognito): CheckpointStore {
        val app = getApplication<Application>()
        return CheckpointStore(if (incog) File(app.cacheDir, "incognito-checkpoints") else File(app.filesDir, "checkpoints"))
    }

    private fun nid(): Long = counter++

    private fun clearStagedAttachments() {
        val stale = attachments.toList()
        attachments.clear()
        if (stale.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { stale.forEach { File(it.path).delete() } }
        }
    }

    // ---------- navigasi ----------
    fun back() {
        when (screen) {
            Screen.CHAT -> screen = Screen.HOME
            Screen.SETTINGS -> {
                pendingPrompt = null
                settingsDraft = null
                settings = store.load()   // buang perubahan yang belum disimpan (mis. dari pemilih model)
                screen = settingsReturn
            }
            Screen.PROJECTS, Screen.CODE, Screen.ARTIFACTS -> screen = workspaceReturn
            Screen.HOME -> {}
        }
    }

    /** Memulai obrolan baru yang bersih (dari tombol Chat baru di laci atau bilah atas). */
    fun newChat() {
        if (screen == Screen.CHAT && running) {
            askLeave = true
            return
        }
        homeDraft = ""
        chatDraft = ""
        homeIncognito = false
        clearStagedAttachments()
        if (screen == Screen.CHAT) {
            items.clear()
            resetLive()
            history.clear()
            projectDir = null
            projectName = null
            incognito = false
            screen = Screen.HOME
        }
    }

    /** Memilih proyek dari lembar "Tambahkan ke proyek": mempertahankan draf dan lampiran yang sudah disiapkan. */
    fun pickProject(meta: ChatMeta?) {
        if (meta == null) {
            if (screen == Screen.CHAT) {
                if (running) {
                    toast = "Tugas masih berjalan. Hentikan dulu sebelum memulai proyek baru."
                    return
                }
                val draft = chatDraft
                chatDraft = ""
                items.clear()
                resetLive()
                history.clear()
                projectDir = null
                projectName = null
                incognito = false
                homeDraft = draft
                screen = Screen.HOME
            }
            return
        }
        val keepStaged = attachments.toList()
        val carryDraft = if (screen == Screen.HOME) homeDraft else chatDraft
        attachments.clear()
        openChat(meta.id, keepAttachments = keepStaged, initialDraft = carryDraft)
    }

    /** Dari layar obrolan: bila agen masih bekerja, tanya dulu; jangan diam-diam membiarkannya jalan tanpa terlihat. */
    fun requestLeave() {
        if (screen == Screen.CHAT && running) askLeave = true else back()
    }

    fun confirmLeave() {
        askLeave = false
        stop()
        back()
    }

    fun dismissLeave() {
        askLeave = false
    }

    var settingsSection by mutableStateOf(SettingsSection.MODEL)

    fun openSettings(section: SettingsSection = SettingsSection.MODEL) {
        settingsSection = section
        if (screen != Screen.SETTINGS) {
            settingsReturn = screen
            settingsDraft = null   // masuk dari luar: mulai dari pengaturan tersimpan, bukan draf lama
        }
        screen = Screen.SETTINGS
    }

    fun settingsFor(p: ProviderChoice): UserSettings = store.load(p)

    /** Tambah kunci untuk penyedia [p] dari teks tempelan (boleh banyak sekaligus). */
    fun addExtraKeys(p: ProviderChoice, text: String): String {
        val cur = extraKeys[p].orEmpty()
        val mainKey = if (settingsDraft?.provider == p) settingsDraft!!.apiKey.ifBlank { store.load(p).apiKey } else store.load(p).apiKey
        val r = ExtraKeys.parse(text, cur + mainKey)
        if (r.added.isEmpty()) {
            return when {
                r.duplicates == 0 && r.rejected == 0 -> "Tempel kunci ${p.label} dulu."
                r.rejected == 0 -> "Kunci itu sudah terdaftar."
                else -> "Tidak ada kunci baru: ${r.rejected} bukan kunci yang valid" +
                    (if (r.duplicates > 0) ", ${r.duplicates} sudah terdaftar." else ".")
            }
        }
        val next = cur + r.added
        if (!store.saveExtraKeys(p, next)) return "Gagal menyimpan kunci secara aman di perangkat ini. Coba lagi."
        extraKeys = extraKeys + (p to next)
        return buildString {
            append("Ditambahkan ${r.added.size} kunci")
            if (r.duplicates > 0) append(", ${r.duplicates} sudah terdaftar")
            if (r.rejected > 0) append(", ${r.rejected} diabaikan (bentuknya bukan kunci)")
            append(". Dipakai bergantian bila kunci utama kena batas/ditolak.")
        }
    }

    /** Simpan kunci utama penyedia [p] secara langsung saat ditambahkan lewat tombol "Tambah kunci". */
    fun setMainKey(p: ProviderChoice, key: String) {
        if (!store.saveMainKey(p, key)) {
            toast = "Kunci tidak bisa disimpan secara aman di perangkat ini. Coba lagi."
            return
        }
        if (settings.provider == p) settings = settings.copy(apiKey = key)
    }

    /**
     * Hapus kunci UTAMA sekarang juga (seperti kunci tambahan, tidak menunggu tombol Simpan). [newMain] = kunci cadangan pertama yang naik
     * jadi utama, atau kosong bila itu kunci terakhir. Sebelumnya penghapusan kunci utama hanya mengubah isian dan baru tersimpan lewat Simpan,
     * padahal Simpan nonaktif saat kunci kosong, sehingga kunci terakhir tidak pernah benar-benar terhapus.
     */
    fun removeMainKey(p: ProviderChoice, newMain: String) {
        if (!store.saveMainKey(p, newMain)) {
            toast = "Perubahan kunci tidak bisa disimpan secara aman di perangkat ini. Coba lagi."
            return
        }
        if (newMain.isNotBlank()) removeExtraKey(p, newMain)
        if (settings.provider == p) settings = settings.copy(apiKey = newMain)
    }

    fun removeExtraKey(p: ProviderChoice, key: String) {
        val next = extraKeys[p].orEmpty().filter { it != key }
        if (store.saveExtraKeys(p, next)) extraKeys = extraKeys + (p to next)
    }

    /** Dari pemilih model: pindah penyedia bila sudah terisi; bila belum, buka Pengaturan dengan penyedia itu terpilih. */
    fun pickProvider(p: ProviderChoice) {
        val ns = store.load(p)
        if (ns.isConfigured) {
            store.save(ns)
            settings = ns
        } else {
            settings = ns
            openSettings()
        }
    }

    /** Ganti model AI secara dinamis untuk penyedia aktif dan simpan langsung. */
    fun pickModel(model: String) {
        val trimmed = model.trim()
        store.saveModel(settings.provider, trimmed)
        val updated = settings.copy(model = trimmed)
        settings = updated
        if (settingsDraft?.provider == settings.provider) {
            settingsDraft = settingsDraft?.copy(model = trimmed)
        }
    }

    /** Simpan perubahan integrasi (akun Google, sakelar Gmail/Calendar/Drive, model) secara langsung dan terenkripsi tanpa keluar layar. */
    fun persistSettingsImmediate(s: UserSettings) {
        if (!store.save(s)) {
            toast = "Kunci/token tidak bisa disimpan secara aman di perangkat ini. Coba lagi."
            return
        }
        settings = s
        if (settingsDraft != null) settingsDraft = s
    }

    /** Hubungkan atau putuskan layanan Google Workspace (Gmail, Calendar, Drive) secara terpisah. */
    fun toggleWorkspaceService(kind: com.mythos.app.agent.WorkspaceServiceKind, enabled: Boolean) {
        val updated = (settingsDraft ?: settings).withServiceConnection(kind, enabled)
        persistSettingsImmediate(updated)
    }

    fun startPkceSession(session: com.mythos.app.PkceOAuthSession) {
        if (!store.savePkceSession(session)) {
            toast = "Gagal menyimpan sesi PKCE secara terenkripsi."
        }
    }

    fun loadPkceSession(): com.mythos.app.PkceOAuthSession? = store.loadPkceSession()

    /** Tangani callback URI OAuth 2.0 PKCE (`com.mythos.app:/oauth2redirect?code=...`) dari peramban. */
    fun handleOAuthRedirect(uri: Uri?) {
        if (uri == null) return
        val scheme = uri.scheme?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        if (scheme != "com.mythos.app" && !path.contains("oauth2redirect")) return
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        if (code.isEmpty()) {
            val err = uri.getQueryParameter("error") ?: "Kode otorisasi tidak ditemukan"
            toast = "Otorisasi Google dibatalkan atau gagal: $err"
            return
        }
        val pkceSession = store.loadPkceSession()
        if (pkceSession == null) {
            toast = "Sesi PKCE tidak ditemukan. Silakan mulai ulang otorisasi dari Pengaturan Integrasi."
            return
        }
        viewModelScope.launch {
            val res = HttpGoogleWorkspaceService.exchangeCodeWithPkce(
                clientId = pkceSession.clientId.ifBlank { settings.googleClientId },
                rawCodeOrUrl = code,
                codeVerifier = pkceSession.codeVerifier,
                redirectUri = pkceSession.redirectUri,
                gmailConnected = pkceSession.gmailRequested,
                calendarConnected = pkceSession.calendarRequested,
                driveConnected = pkceSession.driveRequested,
            )
            res.fold(
                onSuccess = { newAcct ->
                    val finalAcct = if (newAcct.email == "Akun Google" && pkceSession.loginHint.isNotBlank()) {
                        newAcct.copy(id = pkceSession.loginHint.lowercase(), email = pkceSession.loginHint)
                    } else newAcct
                    val updated = (settingsDraft ?: settings).withAddedOrUpdatedGoogleAccount(finalAcct)
                    store.savePkceSession(null)
                    persistSettingsImmediate(updated)
                    toast = "Akun Google ${finalAcct.email} berhasil dihubungkan & dienkripsi."
                },
                onFailure = { err ->
                    toast = "Gagal menukar kode OAuth: ${com.mythos.app.agent.SensitiveDataGuard.redactSensitiveCredentials(err.message ?: "galat")}"
                }
            )
        }
    }

    fun saveSettings(s: UserSettings) {
        if (!store.save(s)) toast = "Kunci/token tidak bisa disimpan secara aman di perangkat ini. Coba lagi atau masukkan ulang."
        settingsDraft = null
        settings = s
        val prompt = pendingPrompt
        if (prompt != null && s.isConfigured) {
            pendingPrompt = null
            homeDraft = ""
            homeIncognito = false
            startNewChat(prompt, pendingIncognito)
        } else {
            screen = settingsReturn
        }
    }

    // ---------- percakapan ----------
    // ---------------------------------------------------------------- lampiran
    val attachments = mutableStateListOf<Attachment>()
    var attachError by mutableStateOf<String?>(null)
    private var cameraFile: File? = null

    fun attachFromUri(uri: Uri, deleteAfter: File? = null) {
        if (attachments.size >= Attachments.MAX_ITEMS) {
            deleteAfter?.delete()
            attachError = "Maksimal ${Attachments.MAX_ITEMS} lampiran per pesan"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val a = Attachments.stage(getApplication(), uri)
                withContext(Dispatchers.Main) {
                    if (attachments.size < Attachments.MAX_ITEMS) {
                        attachments.add(a)
                    } else {
                        File(a.path).delete()
                        attachError = "Maksimal ${Attachments.MAX_ITEMS} lampiran per pesan"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { attachError = e.message ?: "Lampiran gagal diproses" }
            } finally {
                deleteAfter?.delete()
            }
        }
    }

    /** Beberapa berkas sekaligus (pemilih galeri/berkas multi-pilih): diproses berurutan agar urutan pilihan terjaga (penting untuk PDF). */
    fun attachFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var skipped = 0
            var lastErr: String? = null
            for (uri in uris) {
                val full = withContext(Dispatchers.Main) { attachments.size >= Attachments.MAX_ITEMS }
                if (full) {
                    skipped++
                    continue
                }
                try {
                    val a = Attachments.stage(getApplication(), uri)
                    withContext(Dispatchers.Main) {
                        if (attachments.size < Attachments.MAX_ITEMS) attachments.add(a)
                        else {
                            File(a.path).delete()
                            skipped++
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: "Lampiran gagal diproses"
                    lastErr = msg
                    withContext(Dispatchers.Main) { attachError = msg }
                }
            }
            if (skipped > 0 && lastErr == null) {
                withContext(Dispatchers.Main) { attachError = "Maksimal ${Attachments.MAX_ITEMS} lampiran per pesan; $skipped berkas dilewati" }
            }
        }
    }

    fun newCameraUri(): Uri {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        uiPrefs.edit().putString("pending_camera_file", f.absolutePath).apply()
        return FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
    }

    fun cameraDone(ok: Boolean) {
        val f = cameraFile ?: uiPrefs.getString("pending_camera_file", null)?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return
        cameraFile = null
        uiPrefs.edit().remove("pending_camera_file").apply()
        if (ok && f.exists() && f.length() > 0) attachFromUri(Uri.fromFile(f), deleteAfter = f) else f.delete()
    }

    fun removeAttachment(a: Attachment) {
        attachments.remove(a)
        viewModelScope.launch(Dispatchers.IO) { File(a.path).delete() }
    }

    fun submitFromHome(text: String, incog: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        if (!settings.isConfigured) {
            pendingPrompt = t
            pendingIncognito = incog
            openSettings()
            return
        }
        homeDraft = ""
        homeIncognito = false
        startNewChat(t, incog)
    }

    private fun startNewChat(prompt: String, incog: Boolean = incognito) {
        viewModelScope.launch {
            persistJob?.cancel()
            job?.cancelAndJoin()   // tunggu job lama selesai menyimpan dirinya sebelum mengganti projectDir/incognito
            incognito = incog
            items.clear()
            resetLive()
            counter = 0L
            buildWarned = false
            chatEpoch++
            history.clear()
            projectName = null
            projectDir = null
            screen = Screen.CHAT
            send(prompt)
        }
    }

    /** Dari layar obrolan: tugas lanjutan pada proyek yang sama (boleh hanya lampiran tanpa teks). */
    fun send(text: String) {
        val t = text.trim()
        val staged = attachments.toList()
        if ((t.isEmpty() && staged.isEmpty()) || running) return
        if (!settings.isConfigured) {
            openSettings()
            return
        }
        chatDraft = ""
        val chatIncognito = incognito
        val dir = projectDir ?: newProjectDir(chatIncognito).also { projectDir = it; projectName = it.name }
        attachments.clear()
        val prompt = t.ifEmpty { "Periksa lampiran saya dan kerjakan yang sesuai." }
        val builder: BuildService? = settings.github?.let { GitHubBuildService(it) }
        val workspaceSvc = settings.workspace?.let { HttpGoogleWorkspaceService(it) }
        val withWeb = webEnabled
        val searchKeys = settings.searchKeys
        val permissionMode = settings.permissionMode
        val research = researchOn && webEnabled
        finalSeen = false
        runNote = ""
        running = true
        AgentService.start(getApplication<Application>())   // menjaga proses tetap hidup saat aplikasi di latar belakang
        job = viewModelScope.launch(Dispatchers.Default) {
            var journal: TaskJournal? = null
            var historyPrompt = prompt
            try {
                // Titik pulih: isi asli tiap file dicatat tepat sebelum Write/Edit/Delete mengubahnya (tanpa ini tugas tetap jalan).
                journal = try {
                    checkpointStore(chatIncognito).begin(dir.name, dir, prompt.take(80))
                } catch (e: Exception) {
                    null
                }
                // Satu lampiran bermasalah tidak boleh menggagalkan seluruh pengiriman.
                val skipped = ArrayList<String>()
                val earlierUploads = Attachments.manifest(dir)   // lampiran lama, dibaca sebelum yang baru disalin
                val committed = staged.mapNotNull {
                    try {
                        Attachments.commit(it, dir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        skipped.add("${it.name}: ${e.message ?: "gagal diproses"}")
                        null
                    } catch (e: OutOfMemoryError) {
                        skipped.add("${it.name}: terlalu besar untuk diproses di perangkat ini")
                        null
                    }
                }
                val noteBlock = Attachments.noteBlock(committed)
                historyPrompt = prompt + noteBlock
                val task = buildTask(historyPrompt) + earlierUploads + (if (research) RESEARCH_BLOCK else "")
                withContext(Dispatchers.Main) {
                    items.add(UserItem(nid(), t, committed.map { it.attachment }))
                    skipped.forEach { items.add(NoticeItem(nid(), "Lampiran dilewati — $it")) }
                    persist(dir, chatIncognito)
                }
                val images = Attachments.allImages(committed)
                LatencyTrace.begin()
                val llm = prepareClient(needVision = images.isNotEmpty() || Attachments.hasImages(dir))
                val agent = MythosAgent.create(
                    llm,
                    dir,
                    approver,
                    ask,
                    builder,
                    config = com.mythos.app.agent.AgentConfig(permissionMode = permissionMode),
                    journal = journal,
                    withWeb = withWeb,
                    searchKeys = searchKeys,
                    device = AndroidDeviceActions(getApplication()),
                    workspace = workspaceSvc,
                )
                LatencyTrace.ready()
                agent.events(task, images).collect { e -> withContext(Dispatchers.Main) { handle(e, historyPrompt, builder == null) } }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) { items.add(NoticeItem(nid(), "Dihentikan.")) }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    runNote = e.message ?: ""
                    items.add(ErrorItem(nid(), "Galat: ${e.message}"))
                }
            } finally {
                val j = journal
                withContext(NonCancellable + Dispatchers.Main) {
                    running = false
                    finalizeRunning()
                    timeline.settle()
                    liveActivity = null
                    subAgents = SubAgentStatus()
                    // Tugas yang gagal/dihentikan tetap dicatat agar "lanjutkan" pada giliran berikutnya punya konteks.
                    if (!finalSeen) {
                        val why = if (runNote.isNotEmpty()) ": ${runNote.take(300)}" else " (dihentikan pengguna)"
                        history.add(historyPrompt to "Tugas tidak selesai$why. Berkas yang sudah dibuat tetap ada di proyek.")
                    }
                    // Ringkasan perubahan + titik pulih (juga untuk tugas yang dihentikan/gagal di tengah jalan).
                    val changes = if (j == null) {
                        emptyList<FileChange>()
                    } else {
                        withContext(Dispatchers.IO) {
                            try {
                                j.finish()
                            } catch (e: Exception) {
                                emptyList<FileChange>()
                            }
                        }
                    }
                    if (j != null && changes.isNotEmpty()) items.add(ChangesItem(nid(), j.id, changes))
                    persist(dir, chatIncognito)
                    AgentService.stop(getApplication<Application>())
                }
            }
        }
    }

    /** Diam-diam (tanpa tampilan): periksa model yang tersedia lalu bentuk klien dengan cadangan; pergantian model terjadi di latar belakang. */
    /** Kunci yang ditolak penyedia (401) -> waktu ditolak. Giliran berikutnya melewatinya selama [REJECT_MEMORY_MS] supaya tiap pesan tidak kehilangan beberapa detik untuk percobaan yang pasti gagal. */
    private val rejectedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun skipRejected(specs: List<com.mythos.app.agent.KeySpec>): List<com.mythos.app.agent.KeySpec> {
        val now = android.os.SystemClock.elapsedRealtime()
        val ok = specs.filter { s -> val at = rejectedKeys[s.apiKey]; at == null || now - at > REJECT_MEMORY_MS }
        return ok.ifEmpty { specs }   // semua ditolak: tetap coba, supaya galatnya sampai ke pengguna
    }

    private fun failover(specs: List<com.mythos.app.agent.KeySpec>) = FailoverClient(
        KeyPool(skipRejected(specs)), noun = "model",
        onReject = { rejectedKeys[it] = android.os.SystemClock.elapsedRealtime() }
    )

    private suspend fun prepareClient(needVision: Boolean = false): LlmClient {
        val s = settings
        val locked = s.provider.lockedModel
        if (locked != null) {
            // Model dikunci (mis. OpenRouter -> openrouter/free): tanpa daftar/pemindaian, langsung satu entri.
            val c = s.toProviderConfig()
            if (needVision) warnIfNoVision(supportsVision(c.kind, locked))
            val only = com.mythos.app.agent.KeySpec(s.provider.label, c.kind, c.baseUrl, c.apiKey, locked, c.tokenParam, c.temperature, locked = true)
            return failover(ExtraKeys.expand(listOf(only), extraKeys[s.provider].orEmpty()))
        }
        val base = s.toProviderConfig(modelOverride = "")
        val found: List<ModelStatus> = try {
            ModelResolver.shared.firstReady(base, s.model)
        } catch (e: LlmException) {
            if (s.model.isBlank()) {
                throw LlmException("Tidak bisa mengambil daftar model (${e.message}). Isi nama model manual di Pengaturan.", false)
            }
            listOf(ModelStatus(s.model.trim(), ModelState.READY, "tidak diperiksa", s.toProviderConfig()))
        }
        val specs = ExtraKeys.expand(found.toSpecs(s.provider.label), extraKeys[s.provider].orEmpty())
        if (specs.isEmpty()) {
            throw LlmException(
                "Tidak ada model yang bisa dipakai: " + found.take(5).joinToString("; ") { "${it.model}: ${it.detail}" }, false
            )
        }
        if (!needVision) return failover(specs)
        val ordered = specs.sortedByDescending { supportsVision(it.kind, it.model) }
        warnIfNoVision(ordered.any { supportsVision(it.kind, it.model) })
        return failover(ordered)
    }

    private suspend fun warnIfNoVision(any: Boolean) {
        if (!any) withContext(Dispatchers.Main) {
            items.add(NoticeItem(nid(), "Tidak ada model yang bisa melihat gambar pada daftar saat ini; gambar tidak akan dibaca agen."))
        }
    }

    /**
     * Dipakai layar Pengaturan begitu kunci dimasukkan: cara yang SAMA dengan saat tugas dimulai (terbaik dulu, satu per satu, berhenti di
     * model pertama yang merespons) dan memakai cache yang sama, jadi tugas pertama sesudahnya langsung jalan tanpa mengetes ulang.
     */
    suspend fun scanModels(s: UserSettings, refresh: Boolean = false): List<ModelStatus> =
        ModelResolver.shared.firstReady(s.toProviderConfig(modelOverride = ""), s.model, refresh = refresh)

    fun stop() {
        persistJob?.cancel()
        pendingApproval?.result?.complete(false)
        pendingQuestion?.result?.complete("")
        job?.cancel()
    }

    fun respondApproval(ok: Boolean) {
        pendingApproval?.result?.complete(ok)
        pendingApproval = null
    }

    fun respondQuestion(answer: String) {
        pendingQuestion?.result?.complete(answer)
        pendingQuestion = null
    }

    // ---------- riwayat ----------
    fun openChat(id: String, keepAttachments: List<Attachment> = emptyList(), initialDraft: String? = null) {
        if (running) {
            // Jangan diam-diam membunuh agen yang sedang bekerja; chat yang sama cukup ditampilkan lagi.
            if (projectDir?.name == id) {
                if (keepAttachments.isNotEmpty()) attachments.addAll(keepAttachments)
                if (!initialDraft.isNullOrBlank() && chatDraft.isBlank()) chatDraft = initialDraft
                screen = Screen.CHAT
            } else {
                if (keepAttachments.isNotEmpty()) attachments.addAll(keepAttachments)
                toast = "Tugas masih berjalan. Hentikan dulu sebelum membuka chat lain."
            }
            return
        }
        if (keepAttachments.isEmpty()) clearStagedAttachments()
        viewModelScope.launch {
            persistJob?.cancel()
            job?.cancelAndJoin()
            val saved = withContext(Dispatchers.IO) { chatStore.load(id) } ?: return@launch
            incognito = false
            items.clear()
            resetLive()
            buildWarned = saved.items.any { it is NoticeItem && it.text.contains("Build GitHub Actions") }
            chatEpoch++
            items.addAll(saved.items)
            history.clear()
            history.addAll(saved.history)
            counter = (saved.items.maxOfOrNull { it.id } ?: -1L) + 1L
            val dir = File(saved.meta.projectPath)
            projectDir = dir
            projectName = dir.name
            if (keepAttachments.isNotEmpty()) attachments.addAll(keepAttachments)
            if (!initialDraft.isNullOrBlank()) {
                chatDraft = if (chatDraft.isBlank()) initialDraft else chatDraft
                homeDraft = ""
            }
            screen = Screen.CHAT
            // Tugas yang terputus (proses dimatikan sebelum selesai): selesaikan catatannya agar perubahannya bisa dilihat/dibatalkan.
            val recovered: List<Pair<String, List<FileChange>>> = withContext(Dispatchers.IO) {
                try {
                    checkpointStore(false).recoverInterrupted(dir.name, dir)
                } catch (e: Exception) {
                    emptyList<Pair<String, List<FileChange>>>()
                }
            }
            if (recovered.isNotEmpty()) {
                items.add(NoticeItem(nid(), "Tugas sebelumnya terhenti sebelum selesai (aplikasi ditutup). Perubahan yang sempat dibuat masih bisa dilihat dan dibatalkan."))
                recovered.forEach { (cid, changes) -> items.add(ChangesItem(nid(), cid, changes)) }
                persist(dir, false)
            }
        }
    }

    fun deleteChat(meta: ChatMeta, withFiles: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                persistLock.withLock {
                    deletedIds.add(meta.id)
                }
            }
            if (projectDir?.name == meta.id) {
                persistJob?.cancel()
                projectDir = null
                projectName = null
                job?.cancelAndJoin()
                items.clear()
                resetLive()
                history.clear()
            }
            withContext(Dispatchers.IO) {
                persistLock.withLock {
                    chatStore.delete(meta.id)
                }
                if (withFiles) deleteProjectFiles(File(meta.projectPath))
                // titik pulih tak berguna tanpa riwayatnya
                CheckpointStore(File(getApplication<Application>().filesDir, "checkpoints")).deleteProject(meta.id)
            }
            refreshChats()
        }
    }

    private fun refreshChats() {
        viewModelScope.launch(Dispatchers.IO) {
            val l = chatStore.list()
            withContext(Dispatchers.Main) {
                chats.clear()
                chats.addAll(l)
            }
        }
    }

    private fun persist(dir: File? = projectDir, incog: Boolean = incognito) {
        if (incog) return
        val targetDir = dir ?: return
        val first = items.firstOrNull { it is UserItem } as? UserItem ?: return
        val snapshot = items.toList()
        val hist = history.toList()
        val title = first.text.lineSequence().first().take(48)
            .ifBlank { first.attachments.firstOrNull()?.name ?: "Percakapan baru" }   // pesan hanya lampiran -> jangan kosong
        val meta = ChatMeta(targetDir.name, title, targetDir.path, System.currentTimeMillis())
        val seq = ++persistSeq
        viewModelScope.launch(Dispatchers.IO) {
            persistLock.withLock {
                if (seq >= savedSeq && targetDir.name !in deletedIds) {   // snapshot yang lebih baru sudah tersimpan atau proyek dihapus -> lewati
                    savedSeq = seq
                    chatStore.save(SavedChat(meta, snapshot, hist))
                }
            }
            val l = chatStore.list()
            withContext(Dispatchers.Main) {
                chats.clear()
                chats.addAll(l)
            }
        }
    }

    fun requestUndo(item: ChangesItem) {
        if (!running && !item.undone) pendingUndo = item
    }

    fun dismissUndo() {
        pendingUndo = null
    }

    /** Mengembalikan file ke keadaan sebelum tugas itu; file yang sudah diubah lagi sesudahnya dilewati (tidak ditimpa). */
    fun confirmUndo() {
        val item = pendingUndo ?: return
        pendingUndo = null
        val dir = projectDir ?: return
        if (running) return
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                try {
                    checkpointStore().open(dir.name, dir, item.checkpointId).undo()
                } catch (e: Exception) {
                    UndoReport(emptyList(), listOf("" to (e.message ?: "gagal")))
                }
            }
            val idx = items.indexOfFirst { it is ChangesItem && it.checkpointId == item.checkpointId }
            if (idx >= 0) items[idx] = (items[idx] as ChangesItem).copy(undone = true)
            val msg = buildString {
                append("Tugas dibatalkan: ${report.restored.size} file dipulihkan")
                if (report.skipped.isNotEmpty()) {
                    append(", ${report.skipped.size} dilewati (")
                    append(report.skipped.joinToString("; ") { (p, why) -> if (p.isEmpty()) why else "$p: $why" })
                    append(")")
                }
                append(".")
            }
            items.add(NoticeItem(nid(), msg))
            // agen harus tahu bahwa isi file berubah di luar pengetahuannya
            history.add("(pengguna membatalkan tugas sebelumnya)" to "Perubahan tugas sebelumnya dibatalkan oleh pengguna. $msg Baca ulang file sebelum mengubahnya.")
            persist()
        }
    }

    /** Berkas hasil agen di proyek aktif (untuk tombol Buka/Unduh di chat); null bila tak ada atau path keluar dari proyek. */
    fun projectFile(path: String): File? {
        val dir = projectDir ?: return null
        return try { ProjectSandbox(dir).resolve(path).takeIf { it.isFile } } catch (e: Exception) { null }
    }

    /** Dipanggil saat pengguna mengetuk "Kirim email" pada kartu: membuka aplikasi email dengan draf + lampiran. Null = berhasil dibuka. */
    fun openEmailDraft(d: EmailDraftItem): String? {
        val files = d.attachments.map { projectFile(it) ?: return "Lampiran tidak ditemukan di proyek ini: ${it.substringAfterLast('/')}" }
        return AndroidDeviceActions(getApplication()).composeEmail(d.to, d.cc, d.subject, d.body, files)
    }

    suspend fun loadDiff(item: ChangesItem, path: String): String {
        val dir = projectDir ?: return ""
        return withContext(Dispatchers.IO) {
            try {
                checkpointStore().open(dir.name, dir, item.checkpointId).diff(path)
            } catch (e: Exception) {
                "(gagal memuat diff: ${e.message})"
            }
        }
    }

    /** Simpan bertahap selama agen bekerja (paling sering tiap 2 detik) agar progres tidak hilang bila proses dimatikan. */
    private fun persistSoon() {
        if (persistJob?.isActive == true) return
        persistJob = viewModelScope.launch {
            delay(2_000)
            persist()
        }
    }

    /** Alat yang masih berputar saat run berakhir/dibatalkan ditandai terputus. */
    private fun finalizeRunning() {
        for (i in items.indices) {
            val it = items[i]
            if (it is ToolItem && it.status == ToolStatus.RUNNING) {
                items[i] = it.copy(status = ToolStatus.ERROR, preview = "dihentikan")
            }
        }
    }

    /** Hanya menghapus di dalam folder proyek yang dikelola Mythos. */
    @Suppress("DEPRECATION")
    private fun deleteProjectFiles(dir: File) {
        val app = getApplication<Application>()
        val roots = listOfNotNull(
            runCatching { File(app.filesDir, "projects").canonicalFile }.getOrNull(),
            runCatching { File(app.cacheDir, "incognito").canonicalFile }.getOrNull(),
            runCatching { File(Environment.getExternalStorageDirectory(), "Mythos/projects").canonicalFile }.getOrNull(),
        )
        val c = runCatching { dir.canonicalFile }.getOrNull() ?: return
        if (roots.any { c.path.startsWith(it.path + File.separator) }) c.deleteRecursively()
    }

    // ---------- ekspor ----------
    fun export(onReady: (File?) -> Unit) {
        val dir = projectDir
        if (dir == null) {
            onReady(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var failed = false
            val out = try {
                if (dir.isDirectory) zipProject(dir) else null
            } catch (e: Exception) {
                failed = true
                null
            }
            withContext(Dispatchers.Main) {
                if (failed) toast = "Gagal membuat ZIP proyek (penyimpanan penuh atau berkas hilang)." else onReady(out)
            }
        }
    }

    private fun zipProject(dir: File): File {
        val out = File(getApplication<Application>().cacheDir, "exports/${dir.name}.zip")
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }

    // ---------- internal ----------
    private val approver = Approver { tool, summary ->
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { pendingApproval = ApprovalRequest(tool, summary, d) }
        try {
            d.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { pendingApproval = null }
        }
    }

    private val ask: suspend (String) -> String = { q ->
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { pendingQuestion = QuestionRequest(q, d) }
        try {
            d.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { pendingQuestion = null }
        }
    }

    private fun handle(e: AgentEvent, task: String, noBuilder: Boolean = false) {
        when (e) {
            is AgentEvent.Streaming -> {
                if (e.text.isNotBlank()) LatencyTrace.visibleText()
                timeline.streaming(e.text, e.tool, e.target, e.status)
            }
            is AgentEvent.Thought -> timeline.thought(e.text)
            is AgentEvent.PlanChanged -> {
                timeline.settle()
                val lastUser = items.indexOfLast { it is UserItem }
                val idx = items.indexOfLast { it is PlanItem }
                if (idx > lastUser) items[idx] = PlanItem(items[idx].id, e.steps) else items.add(PlanItem(nid(), e.steps))
            }
            is AgentEvent.ToolStart -> if (e.tool == "Task") {
                timeline.settle()
                subAgents = subAgents.started(subAgentLabel(e.summary))
            } else {
                timeline.settle()
                if (noBuilder && !buildWarned && (e.tool == "Write" || e.tool == "Edit")) {
                    buildWarned = true
                    items.add(NoticeItem(nid(), "Build GitHub Actions belum diatur (Pengaturan > Build): agen hanya bisa menulis kode, tanpa membangun/menguji."))
                }
                LatencyTrace.tool()
                items.add(ToolItem(nid(), e.step, e.tool, e.summary, ToolStatus.RUNNING, note = e.status))
            }
            is AgentEvent.ToolEnd -> if (e.tool == "Task") {
                subAgents = subAgents.finished(subAgentLabel(e.summary))
            } else {
                val idx = (if (e.summary.isNotEmpty()) items.indexOfLast { it is ToolItem && it.step == e.step && it.tool == e.tool && it.summary == e.summary && it.status == ToolStatus.RUNNING } else -1)
                    .takeIf { it >= 0 }
                    ?: items.indexOfLast { it is ToolItem && it.step == e.step && it.tool == e.tool && it.status == ToolStatus.RUNNING }
                    .takeIf { it >= 0 }
                    ?: items.indexOfLast { it is ToolItem && it.step == e.step && it.status == ToolStatus.RUNNING }
                if (idx >= 0) {
                    val old = items[idx] as ToolItem
                    items[idx] = old.copy(
                        status = if (e.ok) ToolStatus.OK else ToolStatus.ERROR,
                        preview = e.preview,
                        millis = e.millis
                    )
                }
            }
            is AgentEvent.Notice -> {
                timeline.settle()
                items.add(NoticeItem(nid(), e.text))
            }
            is AgentEvent.EmailDraft -> {
                timeline.settle()
                items.add(EmailDraftItem(nid(), e.to, e.cc, e.subject, e.body, e.attachments))
            }
            is AgentEvent.Final -> {
                // label verifikasi hanya relevan bila giliran ini memakai tool (bukan obrolan biasa)
                // hanya tampil bila giliran ini benar-benar mengubah file (Write/Edit/Delete); giliran baca-saja tidak punya perubahan untuk diperiksa
                val changedFiles = items.takeLastWhile { it !is UserItem }.any { it is ToolItem && it.tool in WRITE_TOOLS }
                timeline.finalAnswer(e.summary, e.verified, changedFiles)
                LatencyTrace.visibleText()
                val trace = LatencyTrace.finish()
                items.lastOrNull { it is FinalItem }?.let { LatencyTrace.keep(it.id, trace) }
                history.add(task to e.summary.take(6_000))
                finalSeen = true
            }
            is AgentEvent.Failed -> {
                timeline.settle()
                runNote = e.reason
                items.add(ErrorItem(nid(), e.reason))
            }
        }
        liveActivity = timeline.activity
        if (e !is AgentEvent.Final) persistSoon()
    }

    private fun buildTask(text: String): String {
        if (history.isEmpty()) return text
        val sb = StringBuilder("Percakapan sebelumnya (paling lama di atas):\n")
        history.takeLast(8).forEach { (t, r) ->
            sb.append("Pengguna: ").append(t.take(1_500)).append("\nMythos: ").append(r).append('\n')
        }
        sb.append("\nPesan baru dari pengguna:\n").append(text)
        return sb.toString()
    }

    /** Chat incognito: folder proyek di cache (dibersihkan tiap proses baru), bukan di penyimpanan permanen. */
    private fun newProjectDir(incog: Boolean = incognito): File {
        val app = getApplication<Application>()
        val parent = if (incog) File(app.cacheDir, "incognito") else File(app.filesDir, "projects")
        parent.mkdirs()
        val base = newProjectName()
        var candidate = File(parent, base)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(parent, "$base-${suffix++}")
        }
        return candidate
    }

    private fun newProjectName(): String =
        "proyek-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        /** Berapa lama kunci yang ditolak (401) dilewati sebelum dicoba lagi: 30 menit. */
        const val REJECT_MEMORY_MS = 30 * 60 * 1000L

        val WRITE_TOOLS = setOf("Write", "Edit", "Delete", "ImagesToPdf", "MakePdf", "EditImage", "PdfEdit")

        /** Ditambahkan ke pesan pengguna saat Riset aktif; bukan bagian riwayat yang ditampilkan. */
        const val RESEARCH_BLOCK = "\n\n<research-mode>The user turned on Research mode. Do NOT answer from memory. " +
            "First call WebSearch with at least 3 differently worded queries (max_results 8) - emit the tool_use blocks right away, never announce them in text first - then WebFetch the 3-5 most relevant and authoritative pages " +
            "and cross-check the facts between them; note where sources disagree. Finish with a structured report in the user's language: " +
            "a short summary, key findings, caveats/uncertainty, and a \"Sumber\" list with the URLs you actually read. Never invent URLs or quotes. " +
            "Do not put project contents or secrets in queries.</research-mode>"
    }
}
