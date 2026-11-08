package com.mythos.app.agent

import kotlinx.coroutines.delay

data class KeySpec(
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val tokenParam: String = "max_tokens",
    val temperature: Double? = 0.2,
    /** true = model dikunci persis seperti tertulis (mis. openrouter/free): tidak diperiksa/diganti otomatis ke model lain. */
    val locked: Boolean = false,
) {
    fun toConfig() = ProviderConfig(kind, baseUrl, apiKey, model, temperature, defaultMaxOutputTokens(kind, baseUrl, model), tokenParam)
}

data class EntryStatus(val label: String, val model: String, val state: String, val healthy: Boolean)

class PoolEntry(spec: KeySpec) {
    /** Bisa berubah: bila penyedia menolak temperature/max_tokens, FailoverClient menyesuaikannya sekali dan mengingatnya. */
    @Volatile var spec: KeySpec = spec
        internal set
    var disabledReason: String? = null
    var cooldownUntil: Long = 0L
    var lastReason: String? = null
    var uses: Int = 0
    /** Batas ukuran permintaan (token) yang dipelajari dari galat 413 penyedia untuk model ini; null = belum diketahui. */
    @Volatile var maxRequestTokens: Int? = null
}

internal data class FailureInfo(val reason: String, val cooldownMs: Long, val disable: Boolean)

private val RETRY_IN = Regex("""retry (?:in|after)\s*(\d+(?:\.\d+)?)\s*s""")

/** 429 dengan "limit: 0" (mis. Google AI Studio): model ini tidak punya jatah gratis sama sekali, jadi menunggu dan mengulang percuma. */
private val ZERO_QUOTA = Regex("""limit:\s*0\b""")

internal fun isZeroQuota(e: LlmException): Boolean = e.code == 429 && ZERO_QUOTA.containsMatchIn((e.message ?: "").lowercase())

/** Menerjemahkan galat penyedia menjadi tindakan: nonaktifkan kunci, atau istirahatkan sementara. */
internal fun classify(e: LlmException): FailureInfo {
    val m = (e.message ?: "").lowercase()
    val hinted = RETRY_IN.find(m)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ((it + 2) * 1000).toLong() }
    val perDay = listOf("perday", "per day", "daily", "per_day").any { it in m }
    val perMinute = listOf("perminute", "per minute", "rate limit", "rate_limit", "too many requests", "per_minute").any { it in m }
    val quota = listOf("quota", "insufficient", "billing", "credit", "balance", "exhausted").any { it in m }
    val transient403 = listOf("region", "location", "country", "not available in your", "cloudflare", "captcha", "temporar", "try again", "overload", "unavailable", "rate limit", "quota", "billing", "credit").any { it in m }
    val keyBad403 = listOf("api key", "api_key", "invalid", "revoked", "disabled", "suspended", "deactivated", "permission denied", "unauthorized", "not authorized").any { it in m }
    return when {
        e.code == 401 -> FailureInfo("kunci ditolak (401)", 0, true)
        // 403 ambigu: kunci memang dicabut/tak berhak (permanen) ATAU hanya sementara (wilayah, WAF/Cloudflare, kuota, gangguan). Permanen hanya bila pesannya jelas soal kunci.
        e.code == 403 && transient403 -> FailureInfo("akses ditolak sementara (403)", 10 * 60_000L, false)
        e.code == 403 && keyBad403 -> FailureInfo("kunci ditolak (403)", 0, true)
        e.code == 403 -> FailureInfo("akses ditolak (403), dicoba lagi nanti", 10 * 60_000L, false)
        e.code == 404 -> FailureInfo("model/endpoint tidak ditemukan", 0, true)
        e.code == 402 -> FailureInfo("kredit habis", 6 * 3600_000L, false)
        isZeroQuota(e) -> FailureInfo("model ini tidak punya kuota gratis (limit: 0); pilih model lain atau aktifkan penagihan", 6 * 3600_000L, false)
        e.code == 429 && perDay -> FailureInfo("kuota harian habis", 6 * 3600_000L, false)
        e.code == 429 && hinted != null -> FailureInfo("batas laju", hinted, false)
        e.code == 429 && perMinute -> FailureInfo("batas laju", 60_000L, false)
        e.code == 429 && quota -> FailureInfo("kuota/kredit habis", 6 * 3600_000L, false)
        e.code == 429 -> FailureInfo("batas laju", 60_000L, false)
        // 400/413/422 biasanya karena bentuk/ukuran PERMINTAAN, bukan kunci: istirahat singkat saja agar tugas berikutnya tidak ikut terkunci.
        e.code == 400 || e.code == 413 || e.code == 422 -> FailureInfo("permintaan ditolak (cek nama model/parameter/ukuran)", 45_000L, false)
        e.code >= 500 || e.retryable -> FailureInfo("gangguan penyedia/jaringan", 20_000L, false)
        else -> FailureInfo("galat tak terduga", 2 * 60_000L, false)
    }
}

/** Perkiraan ukuran permintaan hanya kasar (1 token ~ 3,5 karakter), jadi entri baru dilewati bila permintaan jelas lebih besar dari batasnya. */
private const val SIZE_MARGIN = 1.15

/** Perkiraan jumlah token satu permintaan (system + riwayat + catatan mesin; gambar dihitung kasar). */
internal fun estimateTokens(system: String, messages: List<Msg>): Int =
    ((system.length + messages.sumOf { it.text.length }) / 3.5).toInt() + 200 + 800 * messages.sumOf { it.images.size }

/**
 * Kumpulan kunci dengan status kesehatan. Strategi: tetap di satu kunci selama sehat (konsisten),
 * pindah otomatis saat gagal, dan kembali ke kunci berprioritas lebih tinggi bila sudah pulih.
 * rotateEvery > 0 = bergilir ke kunci sehat berikutnya setiap N panggilan (menyebar beban).
 */
class KeyPool(
    specs: List<KeySpec>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rotateEvery: Int = 0,
) {
    val entries: List<PoolEntry> = specs.map { PoolEntry(it) }
    private var current: PoolEntry? = null
    private var callsOnCurrent = 0

    private fun usable(e: PoolEntry, now: Long) = e.disabledReason == null && now >= e.cooldownUntil

    private fun setCurrent(e: PoolEntry) {
        current = e
        callsOnCurrent = 0
    }

    /** Salin status (nonaktif/istirahat/pemakaian) dari pool lama untuk pasangan kunci+model yang sama. */
    @Synchronized
    fun adoptState(old: KeyPool) {
        for (e in entries) {
            val o = old.entries.firstOrNull { it.spec.apiKey == e.spec.apiKey && it.spec.model == e.spec.model }
                ?: old.entries.firstOrNull { it.spec.apiKey == e.spec.apiKey && old.entries.count { x -> x.spec.apiKey == e.spec.apiKey } == 1 && entries.count { x -> x.spec.apiKey == e.spec.apiKey } == 1 }
                ?: continue
            e.disabledReason = o.disabledReason
            e.cooldownUntil = o.cooldownUntil
            e.lastReason = o.lastReason
            e.uses = o.uses
            e.maxRequestTokens = o.maxRequestTokens
        }
    }

    /** Entri yang batas ukuran permintaannya (bila sudah diketahui) jelas lebih kecil dari [tokens] tidak dipilih: pasti ditolak lagi. */
    private fun fits(e: PoolEntry, tokens: Int): Boolean {
        val lim = e.maxRequestTokens ?: return true
        return tokens <= lim * SIZE_MARGIN
    }

    /** True bila SEMUA entri yang belum dinonaktifkan sudah diketahui terlalu kecil untuk [tokens]; hasilnya batas terbesar di antaranya. */
    @Synchronized
    internal fun biggestLimitIfAllTooSmall(tokens: Int): Int? {
        val active = entries.filter { it.disabledReason == null }
        if (active.isEmpty() || tokens <= 0) return null
        if (active.any { fits(it, tokens) }) return null
        return active.maxOf { it.maxRequestTokens ?: Int.MAX_VALUE }
    }

    @Synchronized
    fun pick(tokens: Int = 0): PoolEntry? {
        val now = clock()
        val cur = current
        if (rotateEvery > 0 && cur != null && callsOnCurrent >= rotateEvery) {
            val i = entries.indexOf(cur)
            for (k in 1..entries.size) {
                val e = entries[(i + k) % entries.size]
                if (usable(e, now) && fits(e, tokens)) {
                    setCurrent(e)
                    return e
                }
            }
        }
        // Mode rotasi: bertahan di kunci sekarang sampai gilirannya habis.
        if (rotateEvery > 0 && cur != null && usable(cur, now) && fits(cur, tokens)) return cur
        // Mode prioritas: selalu kunci sehat teratas (otomatis kembali ke prioritas tinggi saat pulih).
        val e = entries.firstOrNull { usable(it, now) && fits(it, tokens) } ?: return null
        if (e !== cur) setCurrent(e)
        return e
    }

    @Synchronized
    fun onSuccess(e: PoolEntry) {
        if (e === current) callsOnCurrent++
        e.uses++
        e.lastReason = null
    }

    @Synchronized
    internal fun onFailure(e: PoolEntry, f: FailureInfo) {
        e.lastReason = f.reason
        if (f.disable) e.disabledReason = f.reason else e.cooldownUntil = clock() + f.cooldownMs
        if (current === e) current = null
    }

    /** Menghapus hukuman istirahat: dipakai bila SEMUA entri menolak permintaan yang sama (salahnya permintaan, bukan kunci). */
    @Synchronized
    fun forgive(e: PoolEntry) {
        e.cooldownUntil = 0L
        e.lastReason = null
    }

    @Synchronized
    internal fun activeEntryCount(): Int = entries.count { it.disabledReason == null }

    @Synchronized
    fun shortestWaitMs(): Long? {
        val now = clock()
        return entries.filter { it.disabledReason == null }.minOfOrNull { (it.cooldownUntil - now).coerceAtLeast(0) }
    }

    @Synchronized
    fun reset() {
        entries.forEach {
            it.disabledReason = null
            it.cooldownUntil = 0
            it.lastReason = null
        }
        current = null
    }

    @Synchronized
    fun snapshot(): List<EntryStatus> {
        val now = clock()
        return entries.map { e ->
            val dis = e.disabledReason
            when {
                dis != null -> EntryStatus(e.spec.label, e.spec.model, "nonaktif: $dis", false)
                now < e.cooldownUntil ->
                    EntryStatus(e.spec.label, e.spec.model, "istirahat ${(e.cooldownUntil - now) / 1000}s: ${e.lastReason}", false)
                else -> EntryStatus(e.spec.label, e.spec.model, if (e.uses > 0) "siap · dipakai ${e.uses}×" else "siap", true)
            }
        }
    }

    /** Jumlah kunci yang saat ini siap dipakai (tidak nonaktif dan tidak sedang istirahat). */
    fun healthyCount(): Int = snapshot().count { it.healthy }

    fun summary(): String = snapshot().joinToString("; ") { "${it.label}: ${it.state}" }

}

/**
 * Fakta runtime yang BENAR untuk balasan ini, ditulis aplikasi (bukan model): model dan penyedia yang sungguh dipanggil.
 * Dengan ini model tidak perlu menebak jati dirinya (mis. mengaku "Grok buatan Anthropic").
 */
internal fun engineNote(s: KeySpec): String {
    val host = runCatching { java.net.URI(s.baseUrl).host }.getOrNull().orEmpty().ifBlank { s.baseUrl }
    // Router (mis. openrouter/free): penyedia memilih model sungguhan per permintaan, jadi id ini BUKAN model akhirnya.
    val router = s.locked || s.model.lowercase().startsWith("openrouter/")
    val what = if (router) "the router id \"${s.model}\" served through $host (key label: ${s.label}); the provider picks the actual underlying model for each request and it is NOT known to you"
    else "the model id \"${s.model}\" served through $host (key label: ${s.label})"
    return "\n\n# Runtime facts (written by the app, true for this reply)\n" +
        "- This reply is generated by $what.\n" +
        "- When asked which model or provider you use, state exactly this. Do not add who trained it, its version, size or abilities unless you are certain; otherwise say you do not know.\n"
}

/**
 * Model "reasoning" (mis. seri o OpenAI) menolak `temperature` dan `max_tokens` dengan HTTP 400. Bila galatnya memang soal parameter itu,
 * kembalikan spec yang sama tanpa temperature dan dengan `max_completion_tokens`; selain itu null (bukan urusan parameter, atau sudah disesuaikan).
 */
internal fun adaptedForReasoning(spec: KeySpec, e: LlmException): KeySpec? {
    if (e.code != 400 || spec.kind != ProviderKind.OPENAI_COMPATIBLE) return null
    val m = (e.message ?: "").lowercase()
    if (listOf("temperature", "max_tokens", "max_completion_tokens", "unsupported").none { it in m }) return null
    val alt = spec.copy(temperature = null, tokenParam = "max_completion_tokens")
    return if (alt == spec) null else alt
}

/** LlmClient yang memilih dan mengganti kunci sendiri. Protokol teks polos membuat pergantian mulus di tengah tugas. */
class FailoverClient(
    private val pool: KeyPool,
    private val factory: (ProviderConfig) -> LlmClient = ::createRawClient,
    private val onNotice: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxWaitMs: Long = 60_000L,
    private val noun: String = "kunci",
    /** Dipanggil SETIAP kali sebuah kunci dipilih untuk dicoba (termasuk saat pindah karena gagal); dipakai untuk menampilkan kunci aktif. */
    private val onUse: (PoolEntry) -> Unit = {},
    /** Dipanggil dengan kunci yang ditolak penyedia (HTTP 401), agar giliran berikutnya tidak membuang waktu mencobanya lagi. */
    private val onReject: (String) -> Unit = {},
) : LlmClient {
    @Volatile private var lastUsed: PoolEntry? = null

    override suspend fun complete(system: String, messages: List<Msg>): String =
        attempt(estimateTokens(system, messages), onReset = {}) { c, note -> c.complete(system + note, messages) }

    /** Streaming dengan pergantian kunci: teks parsial dari kunci yang gagal dibuang (onText("")) sebelum kunci berikutnya dicoba. */
    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        attempt(estimateTokens(system, messages), onReset = { onText("") }) { c, note -> c.stream(system + note, messages, onText) }

    private suspend fun attempt(estTokens: Int, onReset: () -> Unit, call: suspend (LlmClient, String) -> String): String {
        val maxAttempts = pool.entries.size * 2 + 2
        var attempts = 0
        var last: LlmException? = null
        val rejectedBy = HashSet<PoolEntry>()
        while (attempts < maxAttempts) {
            attempts++
            val entry = pool.pick(estTokens)
            if (entry == null) {
                // Semua model yang aktif sudah diketahui menolak ukuran sebesar ini: tidak perlu menunggu atau memanggil jaringan lagi.
                val biggest = pool.biggestLimitIfAllTooSmall(estTokens)
                if (biggest != null) {
                    throw LlmException(
                        "Permintaan terlalu besar untuk semua $noun yang tersedia (tokens per minute (TPM): Limit $biggest, Requested $estTokens, perkiraan aplikasi).",
                        false, 413
                    )
                }
                val wait = pool.shortestWaitMs()
                if (wait == null || wait > maxWaitMs) {
                    throw LlmException(redact("Semua $noun tidak tersedia. ${pool.summary()}"), false, last?.code ?: -1)
                }
                onNotice("Semua $noun sedang istirahat; menunggu ${wait / 1000 + 1}s…")
                LatencyTrace.retry("semua $noun istirahat", wait + 500)
                sleep(wait + 500)
                continue
            }
            val prev = lastUsed
            if (prev != null && prev !== entry) onNotice("Beralih ke $noun ${entry.spec.label} (${entry.spec.model}).")
            lastUsed = entry
            onUse(entry)
            try {
                val out = try {
                    call(factory(entry.spec.toConfig()), engineNote(entry.spec))
                } catch (e: LlmException) {
                    // Tanpa tombol "mode reasoning": bila 400 karena temperature/max_tokens, sesuaikan sekali, coba ulang, lalu ingat hasilnya.
                    val alt = adaptedForReasoning(entry.spec, e) ?: throw e
                    onReset()
                    val r = call(factory(alt.toConfig()), engineNote(alt))
                    entry.spec = alt
                    r
                }
                pool.onSuccess(entry)
                return out
            } catch (e: LlmException) {
                last = e
                if (e.code == 401) onReject(entry.spec.apiKey)
                val f = classify(e)
                pool.onFailure(entry, f)
                if (e.code == 413) {
                    // Pelajari batas model ini; entri tidak dihukum karena permintaan yang lebih kecil nanti tetap muat.
                    ContextManager.parseLimit(e.message)?.let { lim ->
                        entry.maxRequestTokens = minOf(entry.maxRequestTokens ?: Int.MAX_VALUE, lim)
                        pool.forgive(entry)
                    }
                }
                onReset()   // teks parsial dari percobaan yang gagal dibuang
                LatencyTrace.retry("${entry.spec.model}: ${f.reason}", 0L)
                onNotice("${entry.spec.label} (${entry.spec.model}) gagal: ${f.reason}; mencoba $noun lain.")
                if (e.code == 400 || e.code == 413 || e.code == 422) {
                    rejectedBy.add(entry)
                    // Semua entri yang siap pakai sudah menolak permintaan yang sama: jangan menunggu/mengulang, dan jangan menghukum kunci.
                    if (rejectedBy.size >= pool.activeEntryCount() || pool.pick(estTokens) == null) {
                        rejectedBy.forEach { pool.forgive(it) }
                        throw LlmException(redact("Permintaan ditolak oleh semua $noun (HTTP ${e.code}): ${e.message?.take(300)}"), false, e.code)
                    }
                }
            }
        }
        throw LlmException(redact("Semua percobaan gagal. Terakhir: ${last?.message}"), false, last?.code ?: -1)
    }

    /** Jangan pernah membocorkan kunci lewat pesan galat. */
    private fun redact(s: String): String = pool.entries.filter { it.spec.apiKey.isNotBlank() }.fold(s) { acc, e -> acc.replace(e.spec.apiKey, "***") }
}
