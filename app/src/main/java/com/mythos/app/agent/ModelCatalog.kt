package com.mythos.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** UNTESTED = sengaja belum diuji (cadangan pada mode cepat); baru dicoba bila model aktif gagal saat tugas berjalan. */
enum class ModelState { READY, UNTESTED, BUSY, FAILED }

/** config = parameter yang terbukti jalan (mis. temperature dibuang untuk model reasoning). */
data class ModelStatus(val model: String, val state: ModelState, val detail: String, val config: ProviderConfig)

internal data class HttpGet(val code: Int, val body: String)

internal suspend fun httpGet(url: String, headers: Map<String, String>): HttpGet = withContext(Dispatchers.IO) {
    val conn = openConn(url)
    try {
        blockingIo(conn) {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpGet(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        }
    } catch (e: IOException) {
        ensureActive()
        throw networkError(e)
    } finally {
        conn.disconnect()
    }
}

// ---------- daftar & penyaringan ----------

internal fun listRequest(cfg: ProviderConfig): Pair<String, Map<String, String>> {
    val base = cfg.baseUrl.trimEnd('/')
    return when (cfg.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> if (base.contains("models.github.ai")) {
            // GitHub Models: katalog ada di /catalog/models (bukan /inference/models) dan berupa larik JSON.
            "https://models.github.ai/catalog/models" to mapOf(
                "Authorization" to "Bearer ${cfg.apiKey}",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            )
        } else {
            "$base/models" to (if (cfg.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${cfg.apiKey}"))
        }
        ProviderKind.ANTHROPIC ->
            "$base/v1/models?limit=100" to mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
        ProviderKind.GEMINI ->
            "$base/v1beta/models?pageSize=200" to mapOf("x-goog-api-key" to cfg.apiKey)
    }
}

internal fun parseModelIds(kind: ProviderKind, body: String): List<String> {
    val arr = if (body.trimStart().startsWith("[")) {
        try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw LlmException("Daftar model bukan JSON valid: ${body.take(120)}", false)
        }
    } else {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw LlmException("Daftar model bukan JSON valid: ${body.take(120)}", false)
        }
        o.optJSONArray("data") ?: o.optJSONArray("models") ?: return emptyList()
    }
    val out = ArrayList<String>()
    for (i in 0 until arr.length()) {
        val item = arr.opt(i)
        if (item is String) {
            out.add(item.removePrefix("models/"))
        } else if (item is JSONObject) {
            if (kind == ProviderKind.GEMINI) {
                val methods = item.optJSONArray("supportedGenerationMethods")
                if (methods != null && (0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
            }
            val id = item.str("id") ?: item.str("name") ?: continue
            out.add(id.removePrefix("models/"))
        }
    }
    return out
}

private val NOT_CHAT = Regex(
    "(embed|whisper|tts|audio|transcri|dall-?e|image|imagen|veo|video|moderation|guard|rerank|realtime|speech|aqa|learnlm|robotics|computer-use|ocr|davinci|babbage|similarity|sora|lyria)",
    RegexOption.IGNORE_CASE
)

internal fun isChatModel(id: String): Boolean = id.isNotBlank() && !NOT_CHAT.containsMatchIn(id)

private val STRONG = listOf(
    Regex("opus") to 90, Regex("gpt-5") to 85, Regex("gemini.*pro") to 85, Regex("sonnet") to 80,
    Regex("gpt-4\\.1") to 70, Regex("gemini.*flash") to 70, Regex("deepseek-(chat|v3|reasoner|r1)") to 70,
    Regex("mistral-large") to 70, Regex("command-a") to 65, Regex("llama-?3\\.3-70b|llama-?3\\.1-405b|llama-?4") to 65,
    Regex("qwen.*(72b|235b|coder)") to 60, Regex("gpt-4o") to 60, Regex("haiku") to 40,
)
// "mini" harus berdiri sendiri: tanpa lookbehind, "ge-mini" (semua model Gemini) ikut terkena penalti.
private val WEAK = Regex("(?<![a-z])(mini|nano|lite|small|tiny)\\b|-(8b|7b|3b|1b)")
private val PARAMS = Regex("(\\d+)b")

/** Perkiraan kasar "model mana yang paling mumpuni"; hanya untuk urutan uji, keputusan akhir tetap dari hasil tes. */
internal fun modelScore(id: String): Int {
    val m = id.lowercase()
    var s = STRONG.filter { it.first.containsMatchIn(m) }.maxOfOrNull { it.second } ?: 30
    if (WEAK.containsMatchIn(m)) s -= 25
    if (m.contains("preview") || m.contains("exp")) s -= 10
    if (m.contains("latest")) s += 8
    if (Regex("-20\\d{6}").containsMatchIn(m)) s -= 5
    if (m.contains("gpt-3.5") || m.contains("claude-2") || m.contains("claude-instant")) s -= 50
    PARAMS.find(m)?.groupValues?.get(1)?.toIntOrNull()?.let { s += minOf(it / 10, 30) }
    return s
}

private fun KeySpec.with(s: ModelStatus) =
    KeySpec(label, kind, baseUrl, apiKey, s.model, s.config.tokenParam, s.config.temperature)

class SpecResolution(val specs: List<KeySpec>, val problem: String?)

/**
 * Batas tunggu per model saat pengujian. Penyedia cloud: 10 dtk (yang lambat dilewati). HTTP polos hanya diizinkan untuk Ollama lokal
 * (network_security_config), dan model lokal yang belum dimuat ke memori bisa butuh puluhan detik di permintaan pertama: beri 60 dtk.
 */
internal fun probeTimeoutFor(base: ProviderConfig): Int =
    if (base.baseUrl.startsWith("http://", ignoreCase = true)) 60_000 else 10_000

// ---------- uji panggilan alat ----------

internal const val TOOL_FORMAT_FAIL = "tidak menulis panggilan alat dengan format yang benar"

private val TOOL_PROBE_SYSTEM = "You are a coding agent with tools. To call a tool, write exactly one block " +
    "${Protocol.OPEN}{\"name\": \"<Tool>\", \"input\": {...}}${Protocol.CLOSE} and nothing else after it. " +
    "Available tool: Read {\"file_path\": \"<path>\"} reads a text file."
private const val TOOL_PROBE_USER = "Read the file notes.txt now."

/** Lolos bila balasan, diurai dengan Protocol.parse seperti saat tugas, memuat panggilan Read. */
internal fun toolProbeOk(raw: String): Boolean {
    val p = Protocol.parse(raw) { "probe" }
    return p is Parsed.Reply && p.calls.any { it.name.equals("Read", ignoreCase = true) }
}

// ---------- pemindai ----------

class ModelScanner internal constructor(
    private val fetch: suspend (String, Map<String, String>) -> HttpGet = { u, h -> httpGet(u, h) },
    private val factory: (ProviderConfig) -> LlmClient = ::createRawClient,
    private val parallel: Int = 4,
    /** true = model yang lolos uji "ok" masih harus lolos uji panggilan alat (lihat [toolProbe]) sebelum dipilih sebagai model aktif. */
    private val checkTools: Boolean = false,
) {
    /** Daftar id model yang tampaknya model obrolan (sudah tersaring oleh hak akses kunci ini). */
    suspend fun list(base: ProviderConfig): List<String> {
        val (url, headers) = listRequest(base)
        val r = fetch(url, headers)
        if (r.code !in 200..299) throw LlmException("HTTP ${r.code}: ${r.body.take(200)}", r.code == 429 || r.code >= 500, r.code)
        return parseModelIds(base.kind, r.body).filter(::isChatModel).distinct()
    }

    private class Attempt(val status: ModelStatus, val code: Int)

    private suspend fun attempt(cfg: ProviderConfig, original: ProviderConfig): Attempt = try {
        factory(cfg).complete("Reply with exactly: ok", listOf(Msg(Role.USER, "ok")))
        Attempt(ModelStatus(cfg.model, ModelState.READY, "siap", original.copy(temperature = cfg.temperature, tokenParam = cfg.tokenParam)), 200)
    } catch (e: LlmException) {
        // balasan kosong = model terjangkau (mis. model reasoning menghabiskan token kecil untuk berpikir)
        if (e.code == -1 && (e.message ?: "").startsWith("Respons kosong")) {
            Attempt(ModelStatus(cfg.model, ModelState.READY, "siap", original.copy(temperature = cfg.temperature, tokenParam = cfg.tokenParam)), 200)
        } else {
            val f = classify(e)
            val failed = f.disable || e.code == 400 || e.code == 402 || isZeroQuota(e)
            Attempt(ModelStatus(cfg.model, if (failed) ModelState.FAILED else ModelState.BUSY, f.reason, original), e.code)
        }
    }

    /** Uji satu model dengan permintaan sangat kecil. Bila ditolak 400, coba lagi tanpa temperature / dengan max_completion_tokens. */
    suspend fun probe(cfg: ProviderConfig, timeoutMs: Int = 25_000): ModelStatus {
        val tiny = cfg.copy(maxOutputTokens = 64, timeoutMs = timeoutMs)
        val first = attempt(tiny, cfg)
        if (first.code != 400) return first.status
        val alt = tiny.copy(
            temperature = null,
            tokenParam = if (cfg.kind == ProviderKind.OPENAI_COMPATIBLE) "max_completion_tokens" else tiny.tokenParam,
        )
        val second = attempt(alt, cfg)
        return if (second.status.state == ModelState.READY) second.status else first.status
    }

    /**
     * Uji kedua: apakah model menulis panggilan alat dalam format yang dipahami aplikasi? Model yang hanya menjawab "ok" belum tentu
     * bisa jadi agen (menjawab dengan prosa, atau menulis penanda alat sendiri). Satu permintaan kecil; balasannya diurai oleh
     * [Protocol.parse] yang sama dengan yang dipakai saat tugas. Galat jaringan/kuota/balasan kosong = tidak meyakinkan, dianggap lolos
     * (yang dinilai di sini hanya bentuk balasan, bukan ketersediaan).
     */
    internal suspend fun toolProbe(cfg: ProviderConfig, timeoutMs: Int): Boolean = try {
        val raw = factory(cfg.copy(maxOutputTokens = 512, timeoutMs = timeoutMs)).complete(TOOL_PROBE_SYSTEM, listOf(Msg(Role.USER, TOOL_PROBE_USER)))
        toolProbeOk(raw)
    } catch (e: LlmException) {
        true
    }

    /**
     * Cari model terbaik yang benar-benar jalan: daftar -> urut menurut perkiraan mutu (model pilihan pengguna dulu)
     * -> uji bergelombang sampai cukup model siap. Hasil diurutkan: siap, sibuk, gagal.
     */
    suspend fun scan(
        base: ProviderConfig,
        pinned: String = "",
        maxProbe: Int = 8,
        wantReady: Int = 3,
        exclude: Set<String> = emptySet(),
    ): List<ModelStatus> {
        val listed = list(base).sortedByDescending(::modelScore)
        val ordered = (listOfNotNull(pinned.trim().ifEmpty { null }) + listed).distinct().filter { it !in exclude }
        val results = ArrayList<ModelStatus>()
        val timeout = probeTimeoutFor(base)
        var idx = 0
        while (idx < ordered.size && results.size < maxProbe && results.count { it.state == ModelState.READY } < wantReady) {
            val batch = ordered.subList(idx, minOf(idx + parallel, ordered.size))
            idx += batch.size
            results += coroutineScope {
                batch.map { m ->
                    async {
                        probe(base.copy(model = m, maxOutputTokens = defaultMaxOutputTokens(base.kind, base.baseUrl, m)), timeout)
                    }
                }.awaitAll()
            }
        }
        return results.sortedBy { it.state.ordinal }
    }

    /**
     * Mode cepat (dipakai saat tugas dimulai): uji model SATU PER SATU menurut urutan (pilihan pengguna dulu, lalu perkiraan mutu).
     * Model pertama yang merespons langsung dipakai dan pengujian berhenti di situ. Model yang kuota/kreditnya habis, error, atau
     * tidak merespons dalam [probeTimeoutMs] dilewati ke model berikutnya.
     * Hasil: [model siap] + [[spare] model berikutnya, BELUM diuji] + [model yang sempat sibuk]; model yang gagal permanen dibuang.
     * Cadangan yang belum diuji baru dicoba oleh FailoverClient bila model aktif gagal di tengah tugas, jadi tidak ada waktu terbuang.
     * Bila tak satu pun merespons dalam [maxProbe] percobaan, cadangan tetap dikembalikan agar tugas masih bisa mencoba mereka.
     */
    suspend fun findFirst(
        base: ProviderConfig,
        pinned: String = "",
        exclude: Set<String> = emptySet(),
        maxProbe: Int = 6,
        spare: Int = 4,
        probeTimeoutMs: Int = probeTimeoutFor(base),
    ): List<ModelStatus> {
        val listed = list(base).sortedByDescending(::modelScore)
        val ordered = (listOfNotNull(pinned.trim().ifEmpty { null }) + listed).distinct().filter { it !in exclude }
        fun cfgFor(m: String) = base.copy(model = m, maxOutputTokens = defaultMaxOutputTokens(base.kind, base.baseUrl, m))
        val busy = ArrayList<ModelStatus>()
        var ready: ModelStatus? = null
        var next = 0
        while (next < ordered.size && next < maxProbe) {
            val st = probe(cfgFor(ordered[next]), probeTimeoutMs)
            next++
            if (st.state == ModelState.READY) {
                // Menjawab saja belum cukup: model yang tidak menulis panggilan alat dengan benar diturunkan ke belakang (tetap bisa jadi cadangan terakhir).
                if (checkTools && !toolProbe(st.config, probeTimeoutMs)) {
                    busy += ModelStatus(st.model, ModelState.BUSY, TOOL_FORMAT_FAIL, st.config)
                    continue
                }
                ready = st
                break
            }
            if (st.state == ModelState.BUSY) busy += st
        }
        val untested = ordered.drop(next).take(spare).map { m ->
            ModelStatus(m, ModelState.UNTESTED, "cadangan, belum diuji", cfgFor(m))
        }
        return listOfNotNull(ready) + untested + busy
    }

    /** Untuk kunci tertanam: pakai model yang dikonfigurasi bila sehat; bila tidak, cari satu model pengganti terbaik. */
    suspend fun resolveSpec(spec: KeySpec): SpecResolution {
        // Model dikunci: pakai persis begitu. Kegagalannya ditangani pool (istirahat/pindah kunci), bukan diganti model lain.
        if (spec.locked) return SpecResolution(listOf(spec), null)
        val base = spec.toConfig()
        val first = probe(base, probeTimeoutFor(base))
        if (first.state == ModelState.READY) return SpecResolution(listOf(spec.with(first)), null)
        val found = try {
            scan(base, pinned = "", maxProbe = 6, wantReady = 1, exclude = setOf(spec.model))
        } catch (e: LlmException) {
            emptyList()
        }
        val usable = (listOf(first) + found).filter { it.state != ModelState.FAILED }.sortedBy { it.state.ordinal }
        if (usable.isNotEmpty()) return SpecResolution(listOf(spec.with(usable.first())), null)
        val reason = found.firstOrNull()?.detail ?: first.detail
        return SpecResolution(emptyList(), "${spec.label}: $reason")
    }
}

// ---------- penyelesai (dengan cache) ----------

class ModelResolver(
    private val scanner: ModelScanner = ModelScanner(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 15 * 60_000L,
) {
    private val lists = HashMap<String, Pair<Long, List<ModelStatus>>>()

    suspend fun forProvider(base: ProviderConfig, pinned: String, refresh: Boolean = false): List<ModelStatus> {
        val key = "${base.kind}|${base.baseUrl}|${base.apiKey.hashCode()}|${pinned.trim()}"
        if (!refresh) {
            val hit = synchronized(this) { lists[key] }
            if (hit != null && clock() - hit.first < ttlMs) return hit.second
        }
        val res = scanner.scan(base, pinned)
        if (res.any { it.state == ModelState.READY }) synchronized(this) { lists[key] = clock() to res }
        return res
    }

    /** Mode cepat: model pertama yang merespons + cadangan belum diuji (lihat [ModelScanner.findFirst]). Di-cache terpisah dari [forProvider]. */
    suspend fun firstReady(base: ProviderConfig, pinned: String, refresh: Boolean = false): List<ModelStatus> {
        val key = "fast|${base.kind}|${base.baseUrl}|${base.apiKey.hashCode()}|${pinned.trim()}"
        if (!refresh) {
            val hit = synchronized(this) { lists[key] }
            if (hit != null && clock() - hit.first < ttlMs) return hit.second
        }
        val res = scanner.findFirst(base, pinned)
        if (res.any { it.state == ModelState.READY }) synchronized(this) { lists[key] = clock() to res }
        return res
    }

    companion object {
        val shared: ModelResolver by lazy { ModelResolver(ModelScanner(checkTools = true)) }
    }
}

/** ModelStatus yang bisa dipakai (siap/sibuk) -> daftar KeySpec untuk FailoverClient, dalam urutan prioritas. */
fun List<ModelStatus>.toSpecs(label: String): List<KeySpec> =
    filter { it.state != ModelState.FAILED }.map {
        KeySpec(label, it.config.kind, it.config.baseUrl, it.config.apiKey, it.model, it.config.tokenParam, it.config.temperature)
    }
