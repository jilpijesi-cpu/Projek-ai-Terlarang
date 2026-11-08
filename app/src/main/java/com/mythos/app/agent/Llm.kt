package com.mythos.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class ProviderKind { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

/**
 * temperature = null -> parameter tidak dikirim (perlu untuk model "reasoning" yang menolaknya).
 * tokenParam: "max_tokens" (umum) atau "max_completion_tokens" (model OpenAI terbaru).
 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double? = 0.2,
    val maxOutputTokens: Int = 4096,
    val tokenParam: String = "max_tokens",
    val timeoutMs: Int = 120_000,
)

private val GEMINI_BIG_OUTPUT = Regex("gemini-(2\\.5|[3-9]|(flash|pro)-latest)")
private val OPENAI_BIG_OUTPUT = Regex("gpt-4\\.1|gpt-4o|gpt-5|(^|[^a-z])o[134]($|[-_.])")

/**
 * Batas token keluaran per permintaan. Terlalu kecil membuat Write file besar terpotong (dan di Gemini 2.5 token "thinking"
 * ikut terhitung); terlalu besar ditolak (400) oleh model lama dan ikut dihitung ke kuota laju penyedia gratis. Karena itu
 * hanya dinaikkan untuk keluarga model yang batasnya diketahui lega; sisanya tetap 4096 dan mengandalkan pemecahan file.
 */
internal fun defaultMaxOutputTokens(kind: ProviderKind, baseUrl: String, model: String): Int {
    val m = model.lowercase()
    val host = baseUrl.lowercase()
    return when (kind) {
        ProviderKind.GEMINI -> if (GEMINI_BIG_OUTPUT.containsMatchIn(m)) 16_384 else 8_192
        ProviderKind.ANTHROPIC ->
            if (m.contains("claude-3-opus") || m.contains("claude-3-haiku") || m.contains("claude-2") || m.contains("claude-instant")) 4_096 else 8_192
        ProviderKind.OPENAI_COMPATIBLE -> when {
            host.contains("api.openai.com") && OPENAI_BIG_OUTPUT.containsMatchIn(m) -> 16_384
            host.contains("api.deepseek.com") -> 8_192
            else -> 4_096
        }
    }
}

object Presets {
    private fun cfg(kind: ProviderKind, baseUrl: String, apiKey: String, model: String) =
        ProviderConfig(kind, baseUrl, apiKey, model, maxOutputTokens = defaultMaxOutputTokens(kind, baseUrl, model))

    fun openAi(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", apiKey, model)

    fun deepSeek(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", apiKey, model)

    fun groq(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", apiKey, model)

    /** Hugging Face Inference Providers (router serverless, kompatibel OpenAI). Token "hf_..." dengan izin memanggil Inference Providers. */
    fun huggingFace(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://router.huggingface.co/v1", apiKey, model)

    /** GitHub Models. Token (PAT) dengan izin "models:read". */
    fun githubModels(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://models.github.ai/inference", apiKey, model)

    /** OpenRouter. Model pilihan utama aplikasi: "openrouter/free" (router model gratis). */
    fun openRouter(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", apiKey, model.ifBlank { "openrouter/free" })

    /** Ollama lokal (Termux / PC di jaringan yang sama). Ganti host bila bukan di perangkat ini. */
    fun ollama(model: String, host: String = "http://127.0.0.1:11434") =
        cfg(ProviderKind.OPENAI_COMPATIBLE, host.trimEnd('/') + "/v1", "", model)

    fun anthropic(apiKey: String, model: String) =
        cfg(ProviderKind.ANTHROPIC, "https://api.anthropic.com", apiKey, model)

    fun gemini(apiKey: String, model: String) =
        cfg(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", apiKey, model)
}

interface LlmClient {
    suspend fun complete(system: String, messages: List<Msg>): String

    /**
     * Seperti [complete], tetapi melaporkan teks yang sudah tiba lewat [onText] selagi model menulis.
     * [onText] menerima TEKS KUMULATIF (bukan potongan) dan dipanggil dari thread I/O; teks kosong berarti percobaan diulang dari awal
     * (coba ulang / pergantian kunci) sehingga tampilan harus dikosongkan. Bawaan: tanpa streaming (satu panggilan di akhir),
     * jadi klien yang hanya mengimplementasi [complete] tetap berfungsi.
     */
    suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        val out = complete(system, messages)
        onText(out)
        return out
    }
}

class LlmException(message: String, val retryable: Boolean, val code: Int = -1) : Exception(message)

fun createRawClient(cfg: ProviderConfig): LlmClient = VisionGate(
    cfg,
    when (cfg.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(cfg)
        ProviderKind.ANTHROPIC -> AnthropicClient(cfg)
        ProviderKind.GEMINI -> GeminiClient(cfg)
    }
)

private val VISION_OPENAI_LIKE = Regex(
    "(gpt-4o|gpt-4\\.1|gpt-4\\.5|gpt-5|gpt-4-turbo|gpt-4-vision|vision|(^|[^a-z])o[134]($|[-_.])|llava|minicpm-v|bakllava|moondream|gemma-?3(?!-1b)|qwen[^ ]*-?vl|pixtral|llama-?4|llama-?3\\.2-(11|90)b|gemini|claude|molmo|internvl|openrouter/free)"
)

/** Perkiraan dari nama model: apakah model bisa menerima gambar. Dipakai untuk mengurutkan cadangan dan menyaring lampiran. */
fun supportsVision(kind: ProviderKind, model: String): Boolean {
    val m = model.lowercase()
    return when (kind) {
        ProviderKind.ANTHROPIC -> !(m.startsWith("claude-1") || m.contains("claude-2") || m.contains("claude-instant"))
        ProviderKind.GEMINI -> !m.contains("embedding") && !m.contains("gemma-2") && !m.contains("gemma-3-1b") && !m.contains("gemma-3n")
        ProviderKind.OPENAI_COMPATIBLE -> VISION_OPENAI_LIKE.containsMatchIn(m)
    }
}

/** Model tanpa kemampuan gambar tidak diberi gambar (akan error 400); pesan diberi catatan agar agen tahu. */
class VisionGate(private val cfg: ProviderConfig, private val inner: LlmClient) : LlmClient {
    override suspend fun complete(system: String, messages: List<Msg>): String {
        if (messages.none { it.images.isNotEmpty() } || supportsVision(cfg.kind, cfg.model)) return inner.complete(system, messages)
        return inner.complete(system, stripImages(messages))
    }

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        if (messages.none { it.images.isNotEmpty() } || supportsVision(cfg.kind, cfg.model)) return inner.stream(system, messages, onText)
        return inner.stream(system, stripImages(messages), onText)
    }
}

fun createClient(cfg: ProviderConfig): LlmClient = RetryingClient(createRawClient(cfg))

/** Coba ulang otomatis untuk 429 / 5xx / gangguan jaringan dengan jeda eksponensial. */
class RetryingClient(private val inner: LlmClient, private val maxAttempts: Int = 4) : LlmClient {
    override suspend fun complete(system: String, messages: List<Msg>): String {
        var attempt = 0
        var wait = 1_500L
        while (true) {
            try {
                return inner.complete(system, messages)
            } catch (e: LlmException) {
                attempt++
                if (!e.retryable || isZeroQuota(e) || attempt >= maxAttempts) throw e
                LatencyTrace.retry(e.message.orEmpty(), wait)
                delay(wait)
                wait = minOf(wait * 2, 20_000L)
            }
        }
    }

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        var attempt = 0
        var wait = 1_500L
        while (true) {
            try {
                return inner.stream(system, messages, onText)
            } catch (e: LlmException) {
                attempt++
                if (!e.retryable || isZeroQuota(e) || attempt >= maxAttempts) throw e
                LatencyTrace.retry(e.message.orEmpty(), wait)
                onText("")   // percobaan diulang dari awal: kosongkan tampilan sebelumnya
                delay(wait)
                wait = minOf(wait * 2, 20_000L)
            }
        }
    }
}

internal fun List<Msg>.mergeSameRole(): List<Msg> {
    val out = ArrayList<Msg>()
    for (m in this) {
        val last = out.lastOrNull()
        if (last != null && last.role == m.role) out[out.size - 1] = Msg(m.role, last.text + "\n\n" + m.text, last.images + m.images)
        else out.add(m)
    }
    return out
}

internal fun parseJson(raw: String): JSONObject =
    try { JSONObject(raw) } catch (e: JSONException) {
        throw LlmException("Respons bukan JSON valid: ${raw.take(200)}", false)
    }

/** Galat jaringan; "Cleartext ... not permitted" adalah kesalahan konfigurasi tetap, jadi tidak dicoba ulang. */
internal fun networkError(e: IOException): LlmException =
    LlmException("Jaringan: ${e.message}", !(e.message.orEmpty().contains("cleartext", ignoreCase = true)))

/** Membuka koneksi; alamat yang salah (tanpa http://, spasi, skema asing) menjadi LlmException yang jelas, bukan crash. */
internal fun openConn(url: String): HttpURLConnection = try {
    URL(url).openConnection() as HttpURLConnection
} catch (e: IOException) {
    throw LlmException("Alamat tidak valid: ${e.message}", false)
} catch (e: ClassCastException) {
    throw LlmException("Alamat harus diawali http:// atau https://", false)
}

/**
 * Menjalankan I/O blocking pada [conn] dan memutus koneksi seketika bila korutin dibatalkan (tombol Stop, timeout alat),
 * supaya pembatalan tidak harus menunggu read timeout.
 */
internal suspend fun <T> blockingIo(conn: HttpURLConnection, block: () -> T): T = coroutineScope {
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            conn.disconnect()
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}

/** Galat di dalam badan respons (mis. OpenRouter/Gemini membalas 200 dengan objek "error") dipetakan agar dikenali KeyPool. */
internal fun errorException(err: JSONObject): LlmException {
    val msg = err.str("message").orEmpty().ifBlank { err.toString() }
    val raw = err.optInt("code", -1)
    val low = (msg + " " + err.str("code").orEmpty() + " " + err.str("type").orEmpty()).lowercase()
    val code = when {
        raw in 100..599 -> raw
        "rate limit" in low || "rate_limit" in low || "quota" in low || "too many requests" in low -> 429
        "api key" in low || "api_key" in low || "unauthorized" in low || "authentication" in low -> 401
        "not_found" in low || "model not found" in low || "does not exist" in low -> 404
        "context_length" in low || "too large" in low || "invalid_request" in low -> 400
        "overloaded" in low || "server_error" in low -> 503
        else -> -1
    }
    return LlmException("HTTP ${if (code > 0) code else 200}: ${msg.take(400)}", code == 429 || code >= 500 || code == -1, code)
}

internal object Http {
    suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val conn = openConn(url)
            try {
                blockingIo(conn) {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 20_000
                    conn.readTimeout = timeoutMs
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code !in 200..299) {
                        throw LlmException("HTTP $code: ${text.take(500)}", code == 429 || code >= 500, code)
                    }
                    text
                }
            } catch (e: IOException) {
                ensureActive()   // dibatalkan pengguna -> CancellationException, bukan "Jaringan: Socket closed"
                throw networkError(e)
            } finally {
                conn.disconnect()
            }
        }

    /**
     * POST yang membaca respons Server-Sent Events baris demi baris (streaming). [onEvent] dipanggil di thread I/O untuk tiap peristiwa
     * lengkap dan mengembalikan true bila aliran boleh dihentikan. Bila server membalas JSON biasa (mengabaikan "stream"), seluruh
     * badan diberikan ke [onWhole]. Galat HTTP/jaringan diperlakukan sama seperti [postJson].
     */
    suspend fun postSse(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        timeoutMs: Int,
        onEvent: (String?, String) -> Boolean,
        onWhole: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val conn = openConn(url)
            try {
                blockingIo(conn) {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 20_000
                    conn.readTimeout = timeoutMs   // jeda antarpotongan, bukan total waktu
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.setRequestProperty("Accept-Encoding", "identity")   // gzip menahan potongan sampai buffer penuh
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                        throw LlmException("HTTP $code: ${text.take(500)}", code == 429 || code >= 500, code)
                    }
                    val sse = (conn.contentType ?: "").lowercase().contains("event-stream")
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        if (sse) readSse(r, onEvent) else onWhole(r.readText())
                    }
                }
            } catch (e: IOException) {
                ensureActive()   // dibatalkan pengguna -> CancellationException, bukan "Jaringan: Socket closed"
                throw networkError(e)
            } finally {
                conn.disconnect()
            }
        }
    }
}

internal fun openAiBody(cfg: ProviderConfig, system: String, messages: List<Msg>): JSONObject {
    val arr = JSONArray()
    arr.put(JSONObject().put("role", "system").put("content", system))
    messages.mergeSameRole().forEach {
        val role = if (it.role == Role.USER) "user" else "assistant"
        if (it.images.isEmpty() || it.role != Role.USER) {
            arr.put(JSONObject().put("role", role).put("content", it.text))
        } else {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", it.text))
            it.images.forEach { im ->
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${im.mime};base64,${im.base64}"))
                )
            }
            arr.put(JSONObject().put("role", role).put("content", parts))
        }
    }
    val body = JSONObject().put("model", cfg.model).put("messages", arr).put(cfg.tokenParam, cfg.maxOutputTokens)
    cfg.temperature?.let { body.put("temperature", it) }
    return body
}

internal fun anthropicBody(cfg: ProviderConfig, system: String, messages: List<Msg>): JSONObject {
    val arr = JSONArray()
    messages.mergeSameRole().forEach {
        val role = if (it.role == Role.USER) "user" else "assistant"
        if (it.images.isEmpty() || it.role != Role.USER) {
            arr.put(JSONObject().put("role", role).put("content", it.text))
        } else {
            val blocks = JSONArray()
            it.images.forEach { im ->
                blocks.put(
                    JSONObject().put("type", "image")
                        .put("source", JSONObject().put("type", "base64").put("media_type", im.mime).put("data", im.base64))
                )
            }
            blocks.put(JSONObject().put("type", "text").put("text", it.text))
            arr.put(JSONObject().put("role", role).put("content", blocks))
        }
    }
    val body = JSONObject().put("model", cfg.model).put("max_tokens", cfg.maxOutputTokens)
        .put("system", system).put("messages", arr)
    cfg.temperature?.let { body.put("temperature", it) }
    return body
}

internal fun geminiBody(cfg: ProviderConfig, system: String, messages: List<Msg>): JSONObject {
    val contents = JSONArray()
    messages.mergeSameRole().forEach {
        val parts = JSONArray()
        if (it.role == Role.USER) {
            it.images.forEach { im ->
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", im.mime).put("data", im.base64)))
            }
        }
        parts.put(JSONObject().put("text", it.text))
        contents.put(JSONObject().put("role", if (it.role == Role.USER) "user" else "model").put("parts", parts))
    }
    val gen = JSONObject().put("maxOutputTokens", cfg.maxOutputTokens)
    cfg.temperature?.let { gen.put("temperature", it) }
    return JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        .put("contents", contents)
        .put("generationConfig", gen)
}

class OpenAiCompatibleClient(private val cfg: ProviderConfig) : LlmClient {
    override suspend fun complete(system: String, messages: List<Msg>): String {
        val body = openAiBody(cfg, system, messages)
        val headers = HashMap<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val raw = Http.postJson(cfg.baseUrl.trimEnd('/') + "/chat/completions", headers, body, cfg.timeoutMs)
        return openAiWhole(raw)
    }

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        val body = openAiBody(cfg, system, messages).put("stream", true)
        val headers = HashMap<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val acc = TextStream(onText)
        Http.postSse(
            cfg.baseUrl.trimEnd('/') + "/chat/completions", headers, body, cfg.timeoutMs,
            onEvent = { _, data ->
                if (data.trim() == "[DONE]") {
                    true
                } else {
                    openAiDelta(data)?.let { acc.add(it) }
                    false
                }
            },
            onWhole = { raw -> acc.add(openAiWhole(raw)) },
        )
        acc.flush()
        val out = acc.text
        if (out.isBlank()) throw LlmException("Respons kosong dari model", true)
        return out
    }
}

class AnthropicClient(private val cfg: ProviderConfig) : LlmClient {
    override suspend fun complete(system: String, messages: List<Msg>): String {
        val body = anthropicBody(cfg, system, messages)
        val headers = mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
        val raw = Http.postJson(cfg.baseUrl.trimEnd('/') + "/v1/messages", headers, body, cfg.timeoutMs)
        return anthropicWhole(raw)
    }

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        val body = anthropicBody(cfg, system, messages).put("stream", true)
        val headers = mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
        val acc = TextStream(onText)
        Http.postSse(
            cfg.baseUrl.trimEnd('/') + "/v1/messages", headers, body, cfg.timeoutMs,
            onEvent = { ev, data ->
                anthropicDelta(ev, data)?.let { acc.add(it) }
                ev == "message_stop"
            },
            onWhole = { raw -> acc.add(anthropicWhole(raw)) },
        )
        acc.flush()
        val out = acc.text
        if (out.isBlank()) throw LlmException("Respons kosong dari model", true)
        return out
    }
}

class GeminiClient(private val cfg: ProviderConfig) : LlmClient {
    private val modelId: String get() = cfg.model.trim().removePrefix("models/")

    override suspend fun complete(system: String, messages: List<Msg>): String {
        val body = geminiBody(cfg, system, messages)
        val url = cfg.baseUrl.trimEnd('/') + "/v1beta/models/" + modelId + ":generateContent"
        val raw = Http.postJson(url, mapOf("x-goog-api-key" to cfg.apiKey), body, cfg.timeoutMs)
        return geminiWhole(raw)
    }

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        val body = geminiBody(cfg, system, messages)
        val url = cfg.baseUrl.trimEnd('/') + "/v1beta/models/" + modelId + ":streamGenerateContent?alt=sse"
        val acc = TextStream(onText)
        Http.postSse(
            url, mapOf("x-goog-api-key" to cfg.apiKey), body, cfg.timeoutMs,
            onEvent = { _, data ->
                geminiDelta(data)?.let { acc.add(it) }
                false
            },
            onWhole = { raw -> acc.add(geminiWhole(raw)) },
        )
        acc.flush()
        val out = acc.text
        if (out.isBlank()) throw LlmException("Respons kosong dari model", true)
        return out
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Streaming (Server-Sent Events). Semua fungsi di bawah murni (tanpa jaringan) agar bisa diuji di JVM.
// ---------------------------------------------------------------------------------------------------------------------

internal fun stripImages(messages: List<Msg>): List<Msg> = messages.map {
    if (it.images.isEmpty()) it
    else Msg(it.role, it.text + "\n\n[CATATAN SISTEM: ${it.images.size} gambar terlampir TIDAK bisa dilihat model ini dan tidak dikirim. " +
        "Jangan berpura-pura melihatnya; jelaskan keterbatasan ini kepada pengguna bila gambar penting.]")
}

/**
 * Pembaca Server-Sent Events: menerima baris demi baris dan memanggil [onEvent] (nama event atau null, data) untuk tiap peristiwa lengkap.
 * Baris komentar (": keep-alive") diabaikan; beberapa baris "data:" digabung dengan "\n".
 */
internal class SseParser(private val onEvent: (String?, String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun line(line: String) {
        if (line.isEmpty()) {
            flush()
            return
        }
        if (line[0] == ':') return
        val i = line.indexOf(':')
        val field = if (i < 0) line else line.substring(0, i)
        var value = if (i < 0) "" else line.substring(i + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }

    /** Akhir aliran tanpa baris kosong penutup: kirim peristiwa yang masih tertahan. */
    fun finish() = flush()

    private fun flush() {
        if (hasData) onEvent(event, data.toString())
        event = null
        data.setLength(0)
        hasData = false
    }
}

/** Membaca aliran SSE sampai habis atau sampai [onEvent] mengembalikan true. */
internal fun readSse(r: java.io.BufferedReader, onEvent: (String?, String) -> Boolean) {
    var stop = false
    val parser = SseParser { ev, d -> if (!stop && onEvent(ev, d)) stop = true }
    while (!stop) {
        val line = r.readLine() ?: break
        parser.line(line)
    }
    if (!stop) parser.finish()
}

/**
 * Mengumpulkan potongan teks dan meneruskan teks KUMULATIF paling sering tiap [minGapMs] (potongan pertama langsung).
 * Potongan terakhir selalu diteruskan lewat [flush], jadi tidak ada teks yang hilang.
 */
internal class TextStream(
    private val onText: (String) -> Unit,
    private val minGapMs: Long = 40L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val sb = StringBuilder()
    private var lastEmit = Long.MIN_VALUE / 2
    private var dirty = false

    val text: String get() = sb.toString()

    fun add(delta: String) {
        if (delta.isEmpty()) return
        LatencyTrace.modelToken()
        sb.append(delta)
        dirty = true
        val now = clock()
        if (now - lastEmit >= minGapMs) emit(now)
    }

    fun flush() {
        if (dirty) emit(clock())
    }

    private fun emit(now: Long) {
        lastEmit = now
        dirty = false
        onText(sb.toString())
    }
}

/** Potongan teks dari satu peristiwa SSE OpenAI-compatible; null bila tidak ada. Galat di dalam aliran dilempar sebagai LlmException. */
internal fun openAiDelta(data: String): String? {
    val t = data.trim()
    if (t.isEmpty() || t == "[DONE]") return null
    val o = try { JSONObject(t) } catch (e: JSONException) { return null }   // baris terpotong/bukan JSON: abaikan
    o.optJSONObject("error")?.let { throw errorException(it) }
    val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return null
    val node = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
    val c = node.opt("content")
    return if (c is String && c.isNotEmpty()) c else null
}

/** Potongan teks dari satu peristiwa SSE Anthropic (hanya text_delta); galat dilempar sebagai LlmException. */
internal fun anthropicDelta(event: String?, data: String): String? {
    val t = data.trim()
    if (t.isEmpty()) return null
    val o = try { JSONObject(t) } catch (e: JSONException) { return null }
    when (o.str("type") ?: event ?: "") {
        "content_block_delta" -> {
            val d = o.optJSONObject("delta") ?: return null
            val s = d.str("text").orEmpty()
            return if (d.str("type") == "text_delta" && s.isNotEmpty()) s else null
        }
        "error" -> throw errorException(o.optJSONObject("error") ?: o)
    }
    return null
}

/** Potongan teks dari satu peristiwa SSE Gemini (gabungan semua bagian teks; ringkasan pikiran dilewati). */
internal fun geminiDelta(data: String): String? {
    val t = data.trim()
    if (t.isEmpty()) return null
    val o = try { JSONObject(t) } catch (e: JSONException) { return null }
    o.optJSONObject("error")?.let { throw errorException(it) }
    val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
    val sb = StringBuilder()
    for (i in 0 until parts.length()) {
        val p = parts.optJSONObject(i) ?: continue
        if (p.optBoolean("thought", false)) continue
        sb.append(p.str("text").orEmpty())
    }
    val s = sb.toString()
    return if (s.isEmpty()) null else s
}

/** Cadangan bila server membalas JSON biasa pada permintaan streaming: urai seperti [OpenAiCompatibleClient.complete]. */
internal fun openAiWhole(raw: String): String {
    val json = parseJson(raw)
    val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.str("content")
    if (content.isNullOrBlank()) {
        json.optJSONObject("error")?.let { throw errorException(it) }
        throw LlmException("Respons kosong dari model", true)
    }
    return content
}

internal fun anthropicWhole(raw: String): String {
    val json = parseJson(raw)
    val blocks = json.optJSONArray("content")
        ?: throw (json.optJSONObject("error")?.let { errorException(it) } ?: LlmException("Respons tanpa konten", true))
    val sb = StringBuilder()
    for (i in 0 until blocks.length()) {
        val b = blocks.optJSONObject(i) ?: continue
        if (b.str("type") == "text") sb.append(b.str("text").orEmpty())
    }
    if (sb.isBlank()) throw LlmException("Respons kosong dari model", true)
    return sb.toString()
}

internal fun geminiWhole(raw: String): String {
    val t = raw.trim()
    if (t.startsWith("[")) {   // streamGenerateContent tanpa alt=sse membalas larik potongan
        val arr = try { JSONArray(t) } catch (e: JSONException) { throw LlmException("Respons bukan JSON valid: ${t.take(200)}", false) }
        val acc = StringBuilder()
        for (i in 0 until arr.length()) {
            val chunk = arr.optJSONObject(i) ?: continue
            geminiDelta(chunk.toString())?.let { acc.append(it) }
        }
        if (acc.isBlank()) throw LlmException("Respons kosong dari model", true)
        return acc.toString()
    }
    val json = parseJson(raw)
    val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        ?: throw (json.optJSONObject("error")?.let { errorException(it) } ?: LlmException("Respons kosong/diblokir oleh model", true))
    val sb = StringBuilder()
    for (i in 0 until parts.length()) {
        val p = parts.optJSONObject(i) ?: continue
        if (p.optBoolean("thought", false)) continue
        sb.append(p.str("text").orEmpty())
    }
    if (sb.isBlank()) throw LlmException("Respons kosong dari model", true)
    return sb.toString()
}
