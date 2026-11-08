package com.mythos.app.agent

import com.mythos.app.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Penguji koneksi nyata untuk seluruh integrasi eksternal MYTHOS V6:
 * - Provider & Model AI (OpenAI, Claude, Gemini, DeepSeek, Groq, Hugging Face, GitHub Models, OpenRouter, Ollama)
 * - Google Workspace OAuth 2.0 per layanan (Gmail, Google Calendar, Google Drive)
 * - GitHub API & Actions Repository
 * - Mesin Pencari Web (Brave Search, Tavily)
 *
 * Seluruh pesan hasil uji disanitasi melalui [SensitiveDataGuard.redactSensitiveCredentials]
 * sehingga tidak pernah menampilkan atau mencatat token/secret dalam log.
 */
object IntegrationTester {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun testAiProvider(s: UserSettings): IntegrationCheckResult = withContext(Dispatchers.IO) {
        val label = "AI (${s.provider.label})"
        if (s.provider.needsKey && s.apiKey.isBlank()) {
            return@withContext IntegrationCheckResult(label, false, "Kunci API ${s.provider.label} belum diisi.", 0L)
        }
        val t0 = System.currentTimeMillis()
        try {
            val locked = s.provider.lockedModel
            if (locked != null) {
                val cfg = s.toProviderConfig()
                val (url, headers) = listRequest(cfg)
                val res = httpGet(url, headers)
                val elapsed = System.currentTimeMillis() - t0
                return@withContext if (res.code in 200..299) {
                    IntegrationCheckResult(label, true, "Terhubung • Model aktif: $locked", elapsed, locked)
                } else {
                    IntegrationCheckResult(label, false, "HTTP ${res.code}: ${SensitiveDataGuard.redactSensitiveCredentials(res.body.take(180))}", elapsed, locked)
                }
            }
            val base = s.toProviderConfig(modelOverride = "")
            val statuses = ModelResolver.shared.firstReady(base, s.model, refresh = true)
            val elapsed = System.currentTimeMillis() - t0
            val ready = statuses.firstOrNull { it.state == ModelState.READY }
            if (ready != null) {
                IntegrationCheckResult(
                    service = label,
                    ok = true,
                    detail = "Terhubung • Model siap: ${ready.model} (${statuses.size} kandidat ditemukan)",
                    latencyMs = elapsed,
                    accountIdentity = ready.model,
                )
            } else {
                val firstErr = statuses.firstOrNull()?.let { "${it.model}: ${it.detail}" } ?: "Tidak ada model siap"
                IntegrationCheckResult(label, false, SensitiveDataGuard.redactSensitiveCredentials(firstErr), elapsed)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            IntegrationCheckResult(label, false, SensitiveDataGuard.redactSensitiveCredentials(e.message ?: "Gagal menguji koneksi AI"), elapsed)
        }
    }

    suspend fun testGoogleService(
        s: UserSettings,
        kind: WorkspaceServiceKind,
        gmailApiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
        calendarApiBase: String = "https://www.googleapis.com/calendar/v3/calendars/primary",
        driveApiBase: String = "https://www.googleapis.com/drive/v3",
        oauthTokenUrl: String = "https://oauth2.googleapis.com/token",
    ): IntegrationCheckResult {
        val activeAcct = s.activeGoogleAccount
        if (activeAcct == null || !activeAcct.hasCredentials) {
            return IntegrationCheckResult(kind.label, false, "Belum ada akun Google yang dihubungkan.", 0L)
        }
        val cfg = activeAcct.toWorkspaceConfig(
            globalGmailEnabled = s.gmailEnabled,
            globalCalendarEnabled = s.calendarEnabled,
            globalDriveEnabled = s.driveEnabled,
        ) ?: return IntegrationCheckResult(kind.label, false, "Kredensial OAuth akun ${activeAcct.email} kosong.", 0L, activeAcct.email)

        val svc = HttpGoogleWorkspaceService(
            cfg = cfg,
            gmailApiBase = gmailApiBase,
            calendarApiBase = calendarApiBase,
            driveApiBase = driveApiBase,
            oauthTokenUrl = oauthTokenUrl,
        )
        return svc.testServiceConnection(kind)
    }

    suspend fun testAllGoogleServices(s: UserSettings): List<IntegrationCheckResult> = coroutineScope {
        WorkspaceServiceKind.entries.map { kind ->
            async { testGoogleService(s, kind) }
        }.awaitAll()
    }

    suspend fun testGitHub(
        s: UserSettings,
        apiBase: String = "https://api.github.com",
    ): IntegrationCheckResult = withContext(Dispatchers.IO) {
        val cfg = s.github ?: return@withContext IntegrationCheckResult(
            "GitHub Actions",
            false,
            if (s.githubRepo.isBlank() && s.githubToken.isBlank()) "Repo & token GitHub belum diisi."
            else if (!GitHubConfig.isValidRepo(s.githubRepo)) "Format repo tidak valid (gunakan pemilik/nama)."
            else "Token GitHub belum diisi.",
            0L,
        )
        val t0 = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("${apiBase.trimEnd('/')}/repos/${cfg.repo}")
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build()
            http.newCall(req).execute().use { r ->
                val elapsed = System.currentTimeMillis() - t0
                val body = r.body?.string().orEmpty()
                if (r.code in 200..299) {
                    val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                    val fullName = json.optString("full_name").ifBlank { cfg.repo }
                    val defaultBranch = json.optString("default_branch").ifBlank { "main" }
                    val canPush = json.optJSONObject("permissions")?.optBoolean("push", false) ?: true
                    val permNote = if (canPush) "akses baca/tulis aktif" else "hanya-baca (perlu izin Contents: Read & write)"
                    IntegrationCheckResult(
                        "GitHub Actions",
                        canPush,
                        "Terhubung ke $fullName (branch: $defaultBranch, $permNote)",
                        elapsed,
                        fullName,
                    )
                } else {
                    IntegrationCheckResult(
                        "GitHub Actions",
                        false,
                        "HTTP ${r.code}: ${SensitiveDataGuard.redactSensitiveCredentials(body.take(180))}",
                        elapsed,
                        cfg.repo,
                    )
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            IntegrationCheckResult("GitHub Actions", false, SensitiveDataGuard.redactSensitiveCredentials(e.message ?: "Gagal menghubungi GitHub"), elapsed, cfg.repo)
        }
    }

    suspend fun testWebSearch(
        s: UserSettings,
        braveUrl: String = "https://api.search.brave.com/res/v1/web/search",
        tavilyUrl: String = "https://api.tavily.com/search",
    ): List<IntegrationCheckResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<IntegrationCheckResult>()
        if (s.braveKey.isNotBlank()) {
            val t0 = System.currentTimeMillis()
            val res = try {
                val q = URLEncoder.encode("Indonesia", "UTF-8")
                val req = Request.Builder()
                    .url("$braveUrl?q=$q&count=1")
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", s.braveKey.trim())
                    .get()
                    .build()
                http.newCall(req).execute().use { r ->
                    val elapsed = System.currentTimeMillis() - t0
                    if (r.code in 200..299) {
                        IntegrationCheckResult("Brave Search API", true, "Terhubung & kunci valid", elapsed)
                    } else {
                        IntegrationCheckResult("Brave Search API", false, "HTTP ${r.code}: kunci ditolak atau kuota habis", elapsed)
                    }
                }
            } catch (e: Exception) {
                IntegrationCheckResult("Brave Search API", false, SensitiveDataGuard.redactSensitiveCredentials(e.message ?: "Gagal"), System.currentTimeMillis() - t0)
            }
            out.add(res)
        } else {
            out.add(IntegrationCheckResult("Brave Search API", false, "Opsional (belum diisi; memakai pencari publik)", 0L))
        }

        if (s.tavilyKey.isNotBlank()) {
            val t0 = System.currentTimeMillis()
            val res = try {
                val payload = JSONObject()
                    .put("api_key", s.tavilyKey.trim())
                    .put("query", "Indonesia")
                    .put("max_results", 1)
                val req = Request.Builder()
                    .url(tavilyUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    val elapsed = System.currentTimeMillis() - t0
                    if (r.code in 200..299) {
                        IntegrationCheckResult("Tavily Search API", true, "Terhubung & kunci valid", elapsed)
                    } else {
                        IntegrationCheckResult("Tavily Search API", false, "HTTP ${r.code}: kunci ditolak atau kuota habis", elapsed)
                    }
                }
            } catch (e: Exception) {
                IntegrationCheckResult("Tavily Search API", false, SensitiveDataGuard.redactSensitiveCredentials(e.message ?: "Gagal"), System.currentTimeMillis() - t0)
            }
            out.add(res)
        } else {
            out.add(IntegrationCheckResult("Tavily Search API", false, "Opsional (belum diisi; memakai pencari publik)", 0L))
        }
        out
    }

    suspend fun testAll(s: UserSettings): List<IntegrationCheckResult> = coroutineScope {
        val aiDef = async { testAiProvider(s) }
        val googleDef = async { testAllGoogleServices(s) }
        val ghDef = async { testGitHub(s) }
        val searchDef = async { testWebSearch(s) }
        buildList {
            add(aiDef.await())
            addAll(googleDef.await())
            add(ghDef.await())
            addAll(searchDef.await())
        }
    }
}
