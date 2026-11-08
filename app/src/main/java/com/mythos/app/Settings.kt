package com.mythos.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mythos.app.agent.GitHubConfig
import com.mythos.app.agent.GoogleAccount
import com.mythos.app.agent.GoogleWorkspaceConfig
import com.mythos.app.agent.Presets
import com.mythos.app.agent.ProviderConfig
import com.mythos.app.agent.WorkspaceServiceKind
import org.json.JSONObject
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * lockedModel != null = model dikunci oleh penyedia (tanpa daftar model, tanpa pemindaian, kolom model disembunyikan).
 */
enum class ProviderChoice(val label: String, val needsKey: Boolean, val lockedModel: String? = null) {
    OPENAI("OpenAI", true),
    ANTHROPIC("Claude", true),
    GEMINI("Gemini", true),
    DEEPSEEK("DeepSeek", true),
    GROQ("Groq", true),
    HUGGINGFACE("Hugging Face", true),
    GITHUB_MODELS("GitHub Models", true),
    OPENROUTER("OpenRouter", true, lockedModel = "openrouter/free"),
    OLLAMA("Ollama", false),
}

/**
 * Daftar preset model populer per penyedia AI untuk memudahkan pergantian model secara dinamis
 * tanpa harus mengetik manual atau menunggu pemindaian katalog penuh.
 */
object PopularModels {
    fun forProvider(p: ProviderChoice): List<String> = when (p) {
        ProviderChoice.ANTHROPIC -> listOf(
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-latest",
            "claude-3-5-sonnet-latest",
            "claude-3-5-haiku-latest",
        )
        ProviderChoice.OPENAI -> listOf(
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4o",
            "o3-mini",
        )
        ProviderChoice.GEMINI -> listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        )
        ProviderChoice.DEEPSEEK -> listOf(
            "deepseek-chat",
            "deepseek-reasoner",
        )
        ProviderChoice.GROQ -> listOf(
            "llama-3.3-70b-versatile",
            "deepseek-r1-distill-llama-70b",
            "qwen-qwq-32b",
        )
        ProviderChoice.HUGGINGFACE -> listOf(
            "Qwen/Qwen2.5-Coder-32B-Instruct",
            "meta-llama/Llama-3.3-70B-Instruct",
            "deepseek-ai/DeepSeek-R1",
        )
        ProviderChoice.GITHUB_MODELS -> listOf(
            "openai/gpt-4.1",
            "openai/gpt-4o",
            "meta/llama-3.3-70b-instruct",
            "deepseek/deepseek-r1",
        )
        ProviderChoice.OPENROUTER -> listOf("openrouter/free")
        ProviderChoice.OLLAMA -> listOf(
            "qwen2.5-coder:7b",
            "llama3.2:3b",
            "deepseek-r1:7b",
        )
    }
}

/**
 * Sesi PKCE sementara saat pengguna membuka otorisasi OAuth 2.0 di peramban.
 * Disimpan terenkripsi dengan [SecretBox] agar `code_verifier` tidak pernah bocor.
 */
data class PkceOAuthSession(
    val codeVerifier: String,
    val clientId: String,
    val redirectUri: String,
    val gmailRequested: Boolean = true,
    val calendarRequested: Boolean = true,
    val driveRequested: Boolean = true,
    val loginHint: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun toString(): String =
        "PkceOAuthSession(clientId=$clientId, redirectUri=$redirectUri, verifier=***, gmail=$gmailRequested, calendar=$calendarRequested, drive=$driveRequested)"

    fun toJson(): String = JSONObject()
        .put("codeVerifier", codeVerifier)
        .put("clientId", clientId)
        .put("redirectUri", redirectUri)
        .put("gmailRequested", gmailRequested)
        .put("calendarRequested", calendarRequested)
        .put("driveRequested", driveRequested)
        .put("loginHint", loginHint)
        .put("createdAt", createdAt)
        .toString()

    companion object {
        fun fromJson(raw: String): PkceOAuthSession? = runCatching {
            val o = JSONObject(raw)
            val v = o.optString("codeVerifier").trim()
            if (v.isEmpty()) return null
            PkceOAuthSession(
                codeVerifier = v,
                clientId = o.optString("clientId").trim(),
                redirectUri = o.optString("redirectUri").trim().ifEmpty { "http://localhost" },
                gmailRequested = o.optBoolean("gmailRequested", true),
                calendarRequested = o.optBoolean("calendarRequested", true),
                driveRequested = o.optBoolean("driveRequested", true),
                loginHint = o.optString("loginHint").trim(),
                createdAt = o.optLong("createdAt", 0L),
            )
        }.getOrNull()
    }
}

data class UserSettings(
    val provider: ProviderChoice = ProviderChoice.ANTHROPIC,
    val model: String = "",
    val apiKey: String = "",
    val ollamaHost: String = "http://127.0.0.1:11434",
    val githubToken: String = "",
    val githubRepo: String = "",
    /** Kunci API pencarian opsional (paket gratis Brave Search / Tavily). */
    val braveKey: String = "",
    val tavilyKey: String = "",
    /** Mode izin agen: DEFAULT (tanya sebelum menulis), ACCEPT_EDITS (tulis otomatis), PLAN (hanya baca dan rencana). */
    val permissionMode: com.mythos.app.agent.PermissionMode = com.mythos.app.agent.PermissionMode.ACCEPT_EDITS,
    /** Kredensial OAuth 2.0 Google Workspace (Gmail, Calendar, Drive) — disimpan terenkripsi dengan SecretBox. */
    val googleAccessToken: String = "",
    val googleRefreshToken: String = "",
    val googleClientId: String = GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID,
    /** Daftar akun Google yang tersimpan (mendukung multi-akun) beserta pengaturan layanan terpisah. */
    val googleAccounts: List<GoogleAccount> = emptyList(),
    val activeGoogleAccountId: String = "",
    /** Sakelar global untuk menghubungkan/memutuskan Gmail, Calendar, dan Drive secara terpisah. */
    val gmailEnabled: Boolean = true,
    val calendarEnabled: Boolean = true,
    val driveEnabled: Boolean = true,
) {
    val searchKeys: com.mythos.app.agent.SearchKeys get() = com.mythos.app.agent.SearchKeys(braveKey, tavilyKey)

    /** Konfigurasi build GitHub Actions; null bila token/repo belum diisi atau repo tidak valid. */
    val github: GitHubConfig? get() = GitHubConfig.parse(githubToken, githubRepo)

    /** Semua akun Google yang tersedia (termasuk migrasi otomatis dari token tunggal lama bila ada). */
    val allGoogleAccounts: List<GoogleAccount>
        get() {
            if (googleAccounts.isNotEmpty()) return googleAccounts
            if (googleAccessToken.isNotBlank() || googleRefreshToken.isNotBlank()) {
                return listOf(
                    GoogleAccount(
                        id = "default-google-account",
                        email = "Akun Google Utama",
                        displayName = "Utama",
                        accessToken = googleAccessToken,
                        refreshToken = googleRefreshToken,
                        clientId = googleClientId.ifBlank { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID },
                        gmailConnected = gmailEnabled,
                        calendarConnected = calendarEnabled,
                        driveConnected = driveEnabled,
                    )
                )
            }
            return emptyList()
        }

    /** Akun Google yang sedang aktif saat ini. */
    val activeGoogleAccount: GoogleAccount?
        get() {
            val all = allGoogleAccounts
            if (all.isEmpty()) return null
            return all.firstOrNull { it.id == activeGoogleAccountId } ?: all.first()
        }

    /** Konfigurasi OAuth 2.0 Google Workspace untuk akun aktif; null bila belum ada akun atau semua layanan diputuskan. */
    val workspace: GoogleWorkspaceConfig?
        get() {
            val acct = activeGoogleAccount ?: return null
            return acct.toWorkspaceConfig(
                globalGmailEnabled = gmailEnabled,
                globalCalendarEnabled = calendarEnabled,
                globalDriveEnabled = driveEnabled,
            )?.takeIf { it.isConfigured }
        }

    /** Tambah atau perbarui akun Google dan jadikan akun aktif. */
    fun withAddedOrUpdatedGoogleAccount(account: GoogleAccount): UserSettings {
        val existing = allGoogleAccounts.toMutableList()
        val idx = existing.indexOfFirst {
            it.id == account.id || (it.email.equals(account.email, ignoreCase = true) && account.email.contains("@"))
        }
        val normalized = if (idx >= 0) {
            val old = existing[idx]
            account.copy(
                id = old.id,
                refreshToken = account.refreshToken.ifBlank { old.refreshToken },
            ).also { existing[idx] = it }
        } else {
            existing.add(account)
            account
        }
        return copy(
            googleAccounts = existing,
            activeGoogleAccountId = normalized.id,
            googleAccessToken = normalized.accessToken,
            googleRefreshToken = normalized.refreshToken,
            googleClientId = normalized.clientId.ifBlank { googleClientId },
        )
    }

    /** Ganti akun Google yang aktif berdasarkan [accountId]. */
    fun withActiveGoogleAccount(accountId: String): UserSettings {
        val all = allGoogleAccounts
        val target = all.firstOrNull { it.id == accountId } ?: return this
        return copy(
            googleAccounts = all,
            activeGoogleAccountId = target.id,
            googleAccessToken = target.accessToken,
            googleRefreshToken = target.refreshToken,
            googleClientId = target.clientId.ifBlank { googleClientId },
        )
    }

    /** Hapus akun Google berdasarkan [accountId] dan pilih akun berikutnya bila ada. */
    fun withRemovedGoogleAccount(accountId: String): UserSettings {
        val remaining = allGoogleAccounts.filterNot { it.id == accountId }
        val nextActive = remaining.firstOrNull { it.id == activeGoogleAccountId } ?: remaining.firstOrNull()
        return copy(
            googleAccounts = remaining,
            activeGoogleAccountId = nextActive?.id.orEmpty(),
            googleAccessToken = nextActive?.accessToken.orEmpty(),
            googleRefreshToken = nextActive?.refreshToken.orEmpty(),
        )
    }

    /** Hubungkan atau putuskan layanan tertentu (Gmail, Calendar, atau Drive) secara terpisah pada akun aktif. */
    fun withServiceConnection(kind: WorkspaceServiceKind, connected: Boolean, accountId: String? = null): UserSettings {
        val targetId = accountId ?: activeGoogleAccount?.id
        val updatedAccounts = allGoogleAccounts.map { acct ->
            if (targetId == null || acct.id == targetId) acct.withServiceConnected(kind, connected) else acct
        }
        return when (kind) {
            WorkspaceServiceKind.GMAIL -> copy(googleAccounts = updatedAccounts, gmailEnabled = connected)
            WorkspaceServiceKind.CALENDAR -> copy(googleAccounts = updatedAccounts, calendarEnabled = connected)
            WorkspaceServiceKind.DRIVE -> copy(googleAccounts = updatedAccounts, driveEnabled = connected)
        }
    }

    val isConfigured: Boolean
        get() = !provider.needsKey || apiKey.isNotBlank()   // model kosong = dipilih otomatis

    /** Kunci/token tidak boleh ikut tercetak bila objek ini masuk ke log atau pesan galat. */
    override fun toString(): String =
        "UserSettings(provider=$provider, model=$model, apiKey=${if (apiKey.isBlank()) "" else "***"}, ollamaHost=$ollamaHost, " +
            "githubToken=${if (githubToken.isBlank()) "" else "***"}, githubRepo=$githubRepo, " +
            "braveKey=${if (braveKey.isBlank()) "" else "***"}, tavilyKey=${if (tavilyKey.isBlank()) "" else "***"}, " +
            "googleAccessToken=${if (googleAccessToken.isBlank()) "" else "***"}, googleRefreshToken=${if (googleRefreshToken.isBlank()) "" else "***"}, " +
            "googleAccounts=${googleAccounts.size}, activeGoogleAccountId=$activeGoogleAccountId, " +
            "gmailEnabled=$gmailEnabled, calendarEnabled=$calendarEnabled, driveEnabled=$driveEnabled, " +
            "permissionMode=$permissionMode)"

    val displayModel: String
        get() = when {
            provider.lockedModel != null -> provider.lockedModel.orEmpty()
            model.isBlank() -> "Otomatis"
            else -> model
        }

    val displayProvider: String
        get() = provider.label

    fun toProviderConfig(modelOverride: String? = null): ProviderConfig {
        val key = apiKey.trim()
        // Penyedia dengan model terkunci (OpenRouter) selalu memakai modelnya, apa pun isian/override.
        val m = (provider.lockedModel ?: modelOverride ?: model).trim()
        return when (provider) {
            ProviderChoice.OPENAI -> Presets.openAi(key, m)
            ProviderChoice.ANTHROPIC -> Presets.anthropic(key, m)
            ProviderChoice.GEMINI -> Presets.gemini(key, m)
            ProviderChoice.DEEPSEEK -> Presets.deepSeek(key, m)
            ProviderChoice.GROQ -> Presets.groq(key, m)
            ProviderChoice.HUGGINGFACE -> Presets.huggingFace(key, m)
            ProviderChoice.GITHUB_MODELS -> Presets.githubModels(key, m)
            ProviderChoice.OPENROUTER -> Presets.openRouter(key, m)
            ProviderChoice.OLLAMA -> Presets.ollama(m, ollamaHost.trim())
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mythos_settings", Context.MODE_PRIVATE)

    /** Kunci tambahan per penyedia (disimpan terenkripsi, satu kunci per baris). */
    fun loadExtraKeys(p: ProviderChoice): List<String> {
        val enc = prefs.getString("extra_keys_${p.name}", "").orEmpty()
        if (enc.isEmpty()) return emptyList()
        val dec = SecretBox.decrypt(enc)
        if (dec == null) unreadableSecret = true
        return dec.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** false bila gagal mengenkripsi; daftar lama dibiarkan. */
    fun saveExtraKeys(p: ProviderChoice, keys: List<String>): Boolean {
        val joined = keys.joinToString("\n")
        val enc = if (joined.isEmpty()) "" else try {
            SecretBox.encrypt(joined)
        } catch (e: Exception) {
            return false
        }
        prefs.edit().putString("extra_keys_${p.name}", enc).apply()
        return true
    }

    /** Simpan dan baca sesi PKCE OAuth 2.0 secara terenkripsi. */
    fun savePkceSession(session: PkceOAuthSession?): Boolean {
        if (session == null) {
            prefs.edit().remove("google_pkce_session_enc").apply()
            return true
        }
        val enc = encryptOrNull(session.toJson()) ?: return false
        prefs.edit().putString("google_pkce_session_enc", enc).apply()
        return true
    }

    fun loadPkceSession(): PkceOAuthSession? {
        val enc = prefs.getString("google_pkce_session_enc", "").orEmpty()
        if (enc.isEmpty()) return null
        val dec = SecretBox.decrypt(enc).orEmpty()
        return PkceOAuthSession.fromJson(dec)
    }

    fun activeProvider(): ProviderChoice =
        runCatching { ProviderChoice.valueOf(prefs.getString("active", "") ?: "") }.getOrDefault(ProviderChoice.ANTHROPIC)

    /**
     * true bila pemuatan terakhir menemukan kunci/token tersimpan yang tidak bisa didekripsi (mis. data dipulihkan ke
     * perangkat lain: kunci Keystore tidak ikut berpindah). Pengguna perlu diberi tahu, bukan dibiarkan melihat kolom kosong.
     */
    @Volatile var unreadableSecret: Boolean = false
        private set

    fun load(p: ProviderChoice = activeProvider()): UserSettings {
        val enc = prefs.getString("key_${p.name}", "").orEmpty()
        val ghEnc = prefs.getString("github_token", "").orEmpty()
        val key = if (enc.isEmpty()) "" else SecretBox.decrypt(enc).orEmpty()
        val gh = if (ghEnc.isEmpty()) "" else SecretBox.decrypt(ghEnc).orEmpty()
        val braveEnc = prefs.getString("search_brave", "").orEmpty()
        val tavilyEnc = prefs.getString("search_tavily", "").orEmpty()
        val brave = if (braveEnc.isEmpty()) "" else SecretBox.decrypt(braveEnc).orEmpty()
        val tavily = if (tavilyEnc.isEmpty()) "" else SecretBox.decrypt(tavilyEnc).orEmpty()
        val gAccEnc = prefs.getString("google_access_token", "").orEmpty()
        val gRefEnc = prefs.getString("google_refresh_token", "").orEmpty()
        val gAcc = if (gAccEnc.isEmpty()) "" else SecretBox.decrypt(gAccEnc).orEmpty()
        val gRef = if (gRefEnc.isEmpty()) "" else SecretBox.decrypt(gRefEnc).orEmpty()
        val gListEnc = prefs.getString("google_accounts_enc", "").orEmpty()
        val gListDec = if (gListEnc.isEmpty()) "" else SecretBox.decrypt(gListEnc).orEmpty()
        unreadableSecret = (enc.isNotEmpty() && key.isEmpty()) || (ghEnc.isNotEmpty() && gh.isEmpty()) ||
            (braveEnc.isNotEmpty() && brave.isEmpty()) || (tavilyEnc.isNotEmpty() && tavily.isEmpty()) ||
            (gAccEnc.isNotEmpty() && gAcc.isEmpty()) || (gRefEnc.isNotEmpty() && gRef.isEmpty()) ||
            (gListEnc.isNotEmpty() && gListDec.isEmpty())

        val clientId = prefs.getString("google_client_id", null)?.takeIf { it.isNotBlank() }
            ?: GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID
        val gmailOn = prefs.getBoolean("workspace_gmail_enabled", true)
        val calOn = prefs.getBoolean("workspace_calendar_enabled", true)
        val driveOn = prefs.getBoolean("workspace_drive_enabled", true)

        var accounts = GoogleAccount.listFromJson(gListDec)
        if (accounts.isEmpty() && (gAcc.isNotBlank() || gRef.isNotBlank())) {
            accounts = listOf(
                GoogleAccount(
                    id = "default-google-account",
                    email = "Akun Google Utama",
                    displayName = "Utama",
                    accessToken = gAcc,
                    refreshToken = gRef,
                    clientId = clientId,
                    gmailConnected = gmailOn,
                    calendarConnected = calOn,
                    driveConnected = driveOn,
                )
            )
        }
        val activeAcctId = prefs.getString("active_google_account_id", "").orEmpty()
            .takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.firstOrNull()?.id.orEmpty()
        val activeAcct = accounts.firstOrNull { it.id == activeAcctId } ?: accounts.firstOrNull()

        return UserSettings(
            provider = p,
            model = prefs.getString("model_${p.name}", "").orEmpty(),
            apiKey = key,
            ollamaHost = prefs.getString("ollama_host", null) ?: "http://127.0.0.1:11434",
            githubToken = gh,
            githubRepo = prefs.getString("github_repo", "").orEmpty(),
            braveKey = brave,
            tavilyKey = tavily,
            permissionMode = runCatching { com.mythos.app.agent.PermissionMode.valueOf(prefs.getString("permission_mode", "") ?: "") }
                .getOrDefault(com.mythos.app.agent.PermissionMode.ACCEPT_EDITS),
            googleAccessToken = activeAcct?.accessToken ?: gAcc,
            googleRefreshToken = activeAcct?.refreshToken ?: gRef,
            googleClientId = activeAcct?.clientId?.ifBlank { clientId } ?: clientId,
            googleAccounts = accounts,
            activeGoogleAccountId = activeAcctId,
            gmailEnabled = gmailOn,
            calendarEnabled = calOn,
            driveEnabled = driveOn,
        )
    }

    private fun encryptOrNull(plain: String): String? {
        val t = plain.trim()
        if (t.isEmpty()) return ""
        return try {
            SecretBox.encrypt(t)
        } catch (e: Exception) {
            null
        }
    }

    /** Simpan HANYA kunci utama penyedia [p] (tanpa menyentuh penyedia aktif/model/pengaturan lain); kosong = hapus. false bila gagal mengenkripsi. */
    fun saveMainKey(p: ProviderChoice, key: String): Boolean {
        val enc = encryptOrNull(key) ?: return false
        prefs.edit().putString("key_${p.name}", enc).apply()
        return true
    }

    /** Simpan model aktif untuk penyedia [p] secara instan (pergantian model dinamis). */
    fun saveModel(p: ProviderChoice, model: String) {
        prefs.edit().putString("model_${p.name}", model.trim()).apply()
    }

    /** false bila ada kunci/token yang gagal dienkripsi; nilai lamanya dibiarkan (tidak ditimpa kosong secara diam-diam). */
    fun save(s: UserSettings): Boolean {
        val accountsToSave = s.allGoogleAccounts
        val activeAcct = s.activeGoogleAccount
        val effAccess = activeAcct?.accessToken ?: s.googleAccessToken
        val effRefresh = activeAcct?.refreshToken ?: s.googleRefreshToken

        val encKey = encryptOrNull(s.apiKey)
        val encGh = encryptOrNull(s.githubToken)
        val encBrave = encryptOrNull(s.braveKey)
        val encTavily = encryptOrNull(s.tavilyKey)
        val encGAcc = encryptOrNull(effAccess)
        val encGRef = encryptOrNull(effRefresh)
        val encGList = if (accountsToSave.isEmpty()) "" else encryptOrNull(GoogleAccount.listToJson(accountsToSave))

        val editor = prefs.edit()
            .putString("active", s.provider.name)
            .putString("model_${s.provider.name}", s.model.trim())
            .putString("ollama_host", s.ollamaHost.trim())
            .putString("github_repo", s.githubRepo.trim())
            .putString("permission_mode", s.permissionMode.name)
            .putString("google_client_id", s.googleClientId.trim().ifEmpty { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID })
            .putString("active_google_account_id", activeAcct?.id ?: s.activeGoogleAccountId)
            .putBoolean("workspace_gmail_enabled", s.gmailEnabled)
            .putBoolean("workspace_calendar_enabled", s.calendarEnabled)
            .putBoolean("workspace_drive_enabled", s.driveEnabled)
        if (encKey != null) editor.putString("key_${s.provider.name}", encKey)
        if (encGh != null) editor.putString("github_token", encGh)
        if (encBrave != null) editor.putString("search_brave", encBrave)
        if (encTavily != null) editor.putString("search_tavily", encTavily)
        if (encGAcc != null) editor.putString("google_access_token", encGAcc)
        if (encGRef != null) editor.putString("google_refresh_token", encGRef)
        if (encGList != null) editor.putString("google_accounts_enc", encGList)
        editor.apply()
        val ok = encKey != null && encGh != null && encBrave != null && encTavily != null &&
            encGAcc != null && encGRef != null && encGList != null
        if (ok) unreadableSecret = false
        return ok
    }
}

/** Enkripsi API key dengan AES-GCM; kunci disimpan di Android Keystore (tidak bisa diekspor). */
object SecretBox {
    private const val ALIAS = "mythos_settings_key"

    @Volatile private var cached: SecretKey? = null

    /** Hanya mengambil kunci yang SUDAH ada di Keystore; tidak pernah membuat kunci baru saat dekripsi agar kunci lama tidak tertimpa. */
    @Synchronized
    private fun existingKey(): SecretKey? {
        cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = runCatching { ks.getKey(ALIAS, null) as? SecretKey }.getOrNull()
        if (existing != null) cached = existing
        return existing
    }

    /** Dicache: lookup Keystore itu lambat (apalagi yang pertama) dan dipanggil dari thread utama saat aplikasi dibuka. */
    @Synchronized
    private fun getOrCreateKey(forceRecreate: Boolean = false): SecretKey {
        if (!forceRecreate) cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (forceRecreate) {
            cached = null
            runCatching { if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS) }
        } else {
            try {
                val existing = ks.getKey(ALIAS, null) as? SecretKey
                if (existing != null) {
                    cached = existing
                    return existing
                }
            } catch (e: UnrecoverableKeyException) {
                runCatching { ks.deleteEntry(ALIAS) }
            }
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        val created = gen.generateKey()
        cached = created
        return created
    }

    @Synchronized
    fun encrypt(plain: String): String {
        return try {
            encryptWith(getOrCreateKey(false), plain)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Hanya kunci yang BENAR-BENAR tidak valid yang dibuat ulang. Galat lain (sementara) dilempar ke pemanggil
            // supaya kunci lama, yang masih dipakai semua rahasia tersimpan, tidak terhapus.
            encryptWith(getOrCreateKey(true), plain)
        } catch (e: UnrecoverableKeyException) {
            encryptWith(getOrCreateKey(true), plain)
        }
    }

    private fun encryptWith(secretKey: SecretKey, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey)
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    @Synchronized
    fun decrypt(b64: String): String? = try {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val k = existingKey()
        if (all.size <= 12 || k == null) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        cached = null
        null
    } catch (e: UnrecoverableKeyException) {
        cached = null
        null
    } catch (e: Exception) {
        null
    }
}
