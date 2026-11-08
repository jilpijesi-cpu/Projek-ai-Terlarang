package com.mythos.app.ui

/*
 * Linimasa giliran asisten, murni Kotlin (tanpa Compose/Android) agar bisa diuji di JVM.
 *
 *  - TimelineReducer mengubah AgentEvent menjadi daftar item obrolan. Teks balasan yang SEDANG mengalir menjadi satu
 *    ThoughtItem(live = true) yang diperbarui di tempat; saat balasan selesai, item yang SAMA berubah menjadi narasi (ada alat) atau
 *    jawaban akhir (tanpa alat). id-nya tetap, jadi layar tidak berkedip dan animasi mengalirnya tidak diulang.
 *  - toBlocks / liveLabel / groupTitle: bentuk tampilan (narasi, kelompok langkah, baris aktivitas) seperti pada rekaman.
 */

internal const val THINKING_LABEL = "Proses berpikir"
internal const val STALL_TEXT = "Masih diproses…"

/** Hitung detik di samping label aktivitas? Rekaman acuan tidak menampilkannya, jadi mati. */
internal const val SHOW_ELAPSED = false

// ---------------------------------------------------------------- jadwal rincian langkah (intip otomatis)
// Selagi agen bekerja, daftar rincian langkah terbuka sendiri sebentar-sebentar. Atur kapan munculnya di sini.

/** Rincian terbuka sendiri? false (bawaan): hanya teks di baris aktivitas yang berganti-ganti (seperti rekaman acuan); panah tetap bisa diketuk. */
internal const val DETAIL_PEEK_ENABLED = false

/** Jeda antar kemunculan rincian (ms): rincian pertama muncul setelah selama ini sejak langkah pertama. */
internal const val DETAIL_PEEK_EVERY_MS = 12_000L

/** Berapa lama rincian tampil sebelum menutup sendiri (ms). */
internal const val DETAIL_PEEK_SHOW_MS = 4_000L

/** Jumlah langkah terakhir yang ditampilkan saat intip otomatis. */
internal const val DETAIL_PEEK_MAX = 4

/** Selagi menunggu model, teks baris aktivitas berganti tiap segini (ms). */
internal const val THINK_ROTATE_MS = 2_500L

/**
 * Teks bergantian selagi agen menunggu model (tanpa alat berjalan), disesuaikan dengan alat terakhir, supaya baris tidak diam di
 * "Proses berpikir". Urutan tetap, diputar dengan [tick].
 */
internal fun thinkingPhrase(last: ToolItem?, tick: Int): String {
    val file = last?.let { toolTitle(it.tool, it.summary).substringAfter(' ', "") }.orEmpty()
    val list = when (last?.tool) {
        "Read" -> listOf("Menganalisis isi ${file.ifEmpty { "file" }}", "Menelusuri alur kode", "Menimbang langkah berikutnya")
        "Grep", "Glob", "LS" -> listOf("Memeriksa hasil pencarian", "Menyaring file yang relevan", "Menimbang langkah berikutnya")
        "Write", "Edit" -> listOf("Memeriksa perubahan yang dibuat", "Memastikan kode saling cocok", "Menimbang langkah berikutnya")
        "GithubBuild" -> listOf("Membaca hasil build", "Mencari penyebab galat", "Menimbang perbaikan")
        "WebSearch", "WebFetch" -> listOf("Membaca hasil dari web", "Memilah informasi yang berguna", "Menimbang langkah berikutnya")
        else -> listOf(THINKING_LABEL, "Memahami permintaan", "Menyusun langkah kerja")
    }
    return list[tick.mod(list.size)]
}

/** Berapa lama tanpa perubahan yang terlihat sebelum "Masih diproses…" muncul (terukur ±10 detik pada rekaman). */
internal const val STALL_MS = 10_000L

// ---------------------------------------------------------------- tempo tampilan (diukur dari rekaman acuan, 10 bingkai/detik)

/**
 * Kecepatan dasar teks mengalir (huruf/detik). Rekaman: narasi 86 huruf tuntas ±0,7 dtk dan 190 huruf ±1,0 dtk, jadi aliran terasa
 * cepat tetapi tetap mengalir. Dengan [revealSpeed] (dipercepat sebanding antrean) hasilnya ±0,6 dtk dan ±1,2 dtk.
 */
internal const val REVEAL_CPS = 130f

/** Napas singkat setelah huruf terakhir narasi tampil, sebelum blok berikutnya (baris aktivitas) muncul. Pemicunya selesainya ketikan, bukan waktu. */
internal const val TEXT_PAUSE_MS = 300L

/**
 * Lama minimal kelompok langkah tampil, hanya agar labelnya sempat terbaca bila alat selesai seketika. BUKAN jadwal tetap: urutan
 * ditentukan kejadian (alat selesai -> narasi datang -> ketikan selesai -> blok berikutnya), jadi lama tiap bagian mengikuti isinya.
 */
internal const val STEPS_HOLD_MS = 500L

/** Jeda setelah blok penutup (ringkasan perubahan/galat). */
internal const val TAIL_HOLD_MS = 300L

/** Kecepatan sebenarnya: dasar [REVEAL_CPS], dipercepat sebanding antrean (maks. 2,5x) agar tampilan tak tertinggal. */
internal fun revealSpeed(backlog: Int): Float = REVEAL_CPS * (1f + backlog / 400f).coerceAtMost(2.5f)

/**
 * Perkiraan lama teks sepanjang [len] huruf tampil penuh menurut [revealSpeed]. Sisa antrean b menyusut b' = -CPS*(1 + b/400),
 * jadi t = ln((400 + b0)/400) / (CPS/400); di atas 600 huruf kecepatan tetap 2,5x CPS sampai sisanya 600.
 */
internal fun revealDurationMs(len: Int): Long {
    if (len <= 0) return 0L
    val fast = maxOf(0, len - 600)
    val rest = len - fast
    val tFast = fast / (REVEAL_CPS * 2.5f)
    val tRest = kotlin.math.ln((400f + rest) / 400f) / (REVEAL_CPS / 400f)
    return ((tFast + tRest) * 1000f).toLong()
}

private fun textLen(item: ChatItem): Int = when (item) {
    is ThoughtItem -> item.text.length
    is FinalItem -> item.text.length
    else -> 0
}

/** Berapa lama blok ini harus "dinikmati" sebelum blok sesudahnya boleh muncul. */
internal fun paceHoldMs(b: Block): Long = when (b) {
    is UserBlock -> 0L
    is StepsBlock -> STEPS_HOLD_MS
    is TailBlock -> TAIL_HOLD_MS
    is TextBlock -> minOf(15_000L, revealDurationMs(textLen(b.item)) + TEXT_PAUSE_MS)
}

/** Catatan kapan teks sebuah item benar-benar selesai terketik (diisi tampilan, dibaca antrean). Hanya dipakai di utas utama. */
internal object RevealClock {
    private val done = HashMap<Long, Long>()
    fun markDone(id: Long, nowMs: Long) { if (id !in done) done[id] = nowMs }
    fun clear(id: Long) { done.remove(id) }
    fun doneAt(id: Long): Long? = done[id]
    fun reset() { done.clear() }
}

/** Batas pengaman bila tampilan tak pernah melapor selesai (mis. blok tergulir keluar layar): perkiraan + segini. */
internal const val RELEASE_SAFETY_MS = 3000L

/**
 * Bolehkah blok sesudah [prev] muncul? Untuk teks: SETELAH efek mengetiknya benar-benar selesai ([doneAt]) + [TEXT_PAUSE_MS], jadi baris
 * aktivitas berikutnya muncul tepat saat ketikan berhenti, berapa pun kecepatan jaringan. Blok lain: lama tampil minimal ([paceHoldMs]).
 */
internal fun releaseReady(prev: Block, nowMs: Long, releasedAtMs: Long, doneAtMs: Long?): Boolean = when (prev) {
    is TextBlock -> {
        val late = nowMs - releasedAtMs > paceHoldMs(prev) + RELEASE_SAFETY_MS
        late || (doneAtMs != null && nowMs - doneAtMs >= TEXT_PAUSE_MS)
    }
    else -> nowMs - releasedAtMs >= paceHoldMs(prev)
}

// ---------------------------------------------------------------- judul baris

private val PATH_RE = Regex("\"(?:file_path|path|url|description)\"\\s*:\\s*\"([^\"]+)\"")
private val PATTERN_RE = Regex("\"(?:pattern|query|text)\"\\s*:\\s*\"([^\"]+)\"")

private fun webHost(u: String) = u.substringAfter("://").substringBefore("/").substringBefore("?").removePrefix("www.").ifEmpty { u }

private fun short(p: String) = p.trimEnd('/').substringAfterLast('/').ifEmpty { p }

private fun titleFor(tool: String, path: String?, pattern: String?): String = when (tool) {
    "Read" -> path?.let { "Membaca ${short(it)}" } ?: "Membaca file"
    "LS" -> "Melihat daftar file"
    "Glob" -> pattern?.let { "Mencari file “$it”" } ?: "Mencari file"
    "Grep" -> pattern?.let { "Mencari “$it”" } ?: "Mencari di proyek"
    "Write" -> path?.let { "Menulis ${short(it)}" } ?: "Menulis file"
    "Edit" -> path?.let { "Mengedit ${short(it)}" } ?: "Mengedit file"
    "Delete" -> path?.let { "Menghapus ${short(it)}" } ?: "Menghapus file"
    "GithubBuild" -> "Build di GitHub Actions"
    "ImagesToPdf" -> "Membuat PDF dari gambar"
    "MakePdf" -> "Membuat dokumen PDF"
    "EditImage" -> "Mengedit gambar"
    "PdfEdit" -> "Mengolah PDF"
    "ViewImages" -> "Melihat ulang foto"
    "WebSearch" -> pattern?.let { "Mencari di web “$it”" } ?: "Mencari di web"
    "WebFetch" -> path?.let { "Membuka ${webHost(it)}" } ?: "Membuka halaman web"
    "TodoWrite" -> "Memperbarui daftar tugas"
    "Task" -> path?.let { "Sub-agen: $it" } ?: "Menjalankan sub-agen"
    "AskUserQuestion" -> "Bertanya kepada Anda"
    "ExitPlanMode" -> "Mengajukan rencana"
    else -> tool
}

/** Judul satu baris untuk langkah alat, gaya "Membaca Main.kt" seperti baris ringkasan pada rekaman. */
internal fun toolTitle(tool: String, summary: String): String {
    val path = PATH_RE.find(summary)?.groupValues?.get(1)
        ?: (if (summary.startsWith("$tool ")) summary.removePrefix("$tool ").substringBefore(" (").trim() else "")
            .takeIf { it.isNotEmpty() && it != "null" && !it.startsWith("{") }
    return titleFor(tool, path, PATTERN_RE.find(summary)?.groupValues?.get(1))
}

/** Judul untuk alat yang sedang DITULIS model (JSON-nya masih mengalir); [target] = file/pola pertama bila sudah utuh. */
internal fun prepTitle(tool: String, target: String?): String = titleFor(tool, target, target)

/** Ringkasan setelah selesai, mis. "Membaca 4 file, mengubah 2 file, menjalankan 1 build". */
internal fun summarize(items: List<ChatItem>): String {
    val tools = items.filterIsInstance<ToolItem>()
    if (tools.isEmpty()) return THINKING_LABEL
    val parts = ArrayList<String>()
    fun n(vararg names: String) = tools.count { it.tool in names }
    n("Read").let { if (it > 0) parts.add("membaca $it file") }
    n("Write", "Edit").let { if (it > 0) parts.add("mengubah $it file") }
    n("Delete").let { if (it > 0) parts.add("menghapus $it file") }
    n("Grep", "Glob", "LS").let { if (it > 0) parts.add("mencari $it kali") }
    n("WebSearch", "WebFetch").let { if (it > 0) parts.add("menjelajah web $it kali") }
    n("Task").let { if (it > 0) parts.add("menjalankan $it sub-agen") }
    n("GithubBuild").let { if (it > 0) parts.add("menjalankan $it build") }
    if (parts.isEmpty()) parts.add("${tools.size} langkah")
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** Judul kelompok langkah yang sudah selesai: satu alat -> judul alat itu ("Membaca A.kt"); beberapa -> ringkasan. */
internal fun groupTitle(items: List<ChatItem>): String {
    val tools = items.filterIsInstance<ToolItem>()
    return if (tools.size == 1) toolTitle(tools[0].tool, tools[0].summary) else summarize(items)
}

/**
 * Teks baris langkah yang sudah selesai: label hidup TERAKHIR yang tampil (teks "bekas label" tetap di samping ikon jam, seperti rekaman).
 * "Proses berpikir" tidak informatif, jadi dilewati; bila tak ada label hidup (riwayat lama) dipakai judul kelompok.
 */
internal fun finishedLabel(lastLive: String?, items: List<ChatItem>): String =
    lastLive?.takeIf { it.isNotBlank() && it != THINKING_LABEL } ?: groupTitle(items)

internal enum class GroupIcon { CLOCK, TERMINAL }

/** Kelompok yang menjalankan build memakai ikon terminal (seperti "Running command" pada rekaman); selain itu ikon jam. */
internal fun groupIcon(items: List<ChatItem>): GroupIcon =
    if (items.any { it is ToolItem && it.tool == "GithubBuild" }) GroupIcon.TERMINAL else GroupIcon.CLOCK

// ---------------------------------------------------------------- blok tampilan

internal sealed interface Block
internal class UserBlock(val item: UserItem) : Block
internal class StepsBlock(val items: List<ChatItem>) : Block

/** Teks asisten: narasi di sela langkah ([ThoughtItem]) atau jawaban akhir ([FinalItem]); keduanya satu jalur tampilan. */
internal class TextBlock(val item: ChatItem) : Block
internal class TailBlock(val item: ChatItem) : Block

internal fun blockKey(b: Block): Any = when (b) {
    is UserBlock -> b.item.id
    is StepsBlock -> "steps-${b.items.first().id}"
    is TextBlock -> b.item.id
    is TailBlock -> b.item.id
}

internal fun isStep(i: ChatItem) = i is ToolItem || i is PlanItem || i is NoticeItem

/** UserItem | narasi | kelompok langkah | narasi | kelompok langkah ... | jawaban akhir | galat/ringkasan perubahan. */
internal fun toBlocks(chat: List<ChatItem>): List<Block> {
    val out = ArrayList<Block>()
    var steps = ArrayList<ChatItem>()
    fun flush() {
        if (steps.isNotEmpty()) {
            out.add(StepsBlock(steps))
            steps = ArrayList()
        }
    }
    for (c in chat) {
        when {
            c is UserItem -> { flush(); out.add(UserBlock(c)) }
            c is ThoughtItem || c is FinalItem -> { flush(); out.add(TextBlock(c)) }
            isStep(c) -> steps.add(c)
            else -> { flush(); out.add(TailBlock(c)) }
        }
    }
    flush()
    return out
}

/**
 * Perlu baris aktivitas tambahan di bawah? Ya bila agen bekerja tetapi blok terakhir bukan kelompok langkah yang bisa menjadi baris hidup:
 * sesudah pesan pengguna, sesudah narasi yang sudah selesai, atau saat model menulis JSON alat setelah narasinya. Tidak perlu saat teks
 * sedang mengalir (tanpa baris di bawahnya, seperti rekaman) maupun sesudah jawaban akhir. [textSettled] = efek mengetik narasi terakhir sudah
 * selesai (+ [TEXT_PAUSE_MS]); baris baru baru boleh muncul sesudahnya.
 */
internal fun needsExtraLive(blocks: List<Block>, running: Boolean, activity: LiveActivity?, textSettled: Boolean = true): Boolean {
    if (!running) return false
    return when (val last = blocks.lastOrNull()) {
        null -> true
        is UserBlock -> true
        is StepsBlock -> false
        is TailBlock -> false
        is TextBlock -> {
            val t = last.item
            if (t is ThoughtItem) (!t.live || activity != null) && textSettled else false
        }
    }
}

/** Narasi dijadikan satu baris: tanda Markdown dibuang, spasi/baris baru dipadatkan. Pemotongan "…" dilakukan tampilan (maxLines = 1). */
internal fun oneLineNarration(text: String): String =
    cleanLine(text).replace(Regex("[`*#]+"), "").replace(Regex("\\s+"), " ").trim().take(300)

/** Narasi yang menjelaskan langkah yang sedang ditulis model: hanya bila blok terakhir adalah TextBlock narasi untuk giliran ini. */
private fun narrationForLive(blocks: List<Block>): String? {
    val textBlock = blocks.lastOrNull() as? TextBlock ?: return null
    val t = textBlock.item as? ThoughtItem ?: return null
    return oneLineNarration(t.text).ifEmpty { null }
}

/**
 * Label baris hidup, berganti mengikuti apa yang SEDANG terjadi (seperti rekaman: "Membaca A.kt" -> "Membaca B.kt" -> "Proses berpikir"):
 *  - model sedang menulis alat -> penjelasan dari narasinya (satu baris), atau judul alat bila tidak ada narasi;
 *  - sebuah alat sedang berjalan -> judul alat itu sendiri, jadi label berganti tiap alat dalam satu kelompok;
 *  - selain itu (menunggu model) -> "Proses berpikir".
 */
internal fun liveLabel(blocks: List<Block>, activity: LiveActivity?): String {
    if (activity != null) return activity.status?.takeIf { it.isNotBlank() } ?: narrationForLive(blocks) ?: prepTitle(activity.tool, activity.target)
    val last = (blocks.lastOrNull() as? StepsBlock)?.items?.lastOrNull()
    if (last is ToolItem && last.status == ToolStatus.RUNNING) return last.note.ifBlank { toolTitle(last.tool, last.summary) }
    // catatan sistem ("Balasan terpotong...", "Konteks dipadatkan...") tampil bergantian di baris aktivitas, bukan sebagai daftar
    if (last is NoticeItem) oneLineNarration(last.text).takeIf { it.isNotEmpty() }?.let { return it }
    return THINKING_LABEL
}

/** Tanda kemajuan yang terlihat pengguna: berubah bila ada teks/langkah/label baru. Dipakai untuk "Masih diproses…". */
internal fun progressKey(chat: List<ChatItem>, activity: LiveActivity?): Int {
    val h = chat.fold(chat.size) { acc, item -> acc * 31 + item.hashCode() }
    return h * 31 + (activity?.hashCode() ?: 0)
}

// ---------------------------------------------------------------- status sub-agen (satu baris kecil, bukan baris langkah)

/** "Task audit keamanan" -> "audit keamanan". */
internal fun subAgentLabel(summary: String): String = summary.removePrefix("Task").trim().ifEmpty { "sub-agen" }

/**
 * Sub-agen (alat Task) yang sedang berjalan pada giliran ini. Ditampilkan sebagai satu baris kecil di bawah baris aktivitas
 * (tempat yang sama dengan "Masih diproses…"), teksnya bergantian antar sub-agen yang masih bekerja.
 */
data class SubAgentStatus(val running: List<String> = emptyList(), val done: Int = 0) {
    val total: Int get() = running.size + done

    fun started(label: String) = copy(running = running + label)

    fun finished(label: String): SubAgentStatus {
        if (running.isEmpty()) return this
        val i = running.indexOf(label).takeIf { it >= 0 } ?: 0
        return copy(running = running.filterIndexed { k, _ -> k != i }, done = done + 1)
    }

    /** Teks baris kecil; null bila tidak ada sub-agen yang berjalan. [tick] memutar antar sub-agen. */
    fun phrase(tick: Int): String? {
        if (running.isEmpty()) return null
        val base = "Sub-agen: " + running[tick.mod(running.size)]
        return if (total > 1) "$base · $done/$total selesai" else base
    }
}

// ---------------------------------------------------------------- reducer peristiwa -> item

internal class TimelineReducer(
    private val items: MutableList<ChatItem>,
    private val nextId: () -> Long,
) {
    /** id item teks yang sedang mengalir (ThoughtItem.live), atau null. */
    var liveId: Long? = null
        private set

    /** Alat yang sedang ditulis model (JSON-nya masih mengalir), atau null. */
    var activity: LiveActivity? = null
        private set

    private fun liveIndex(): Int {
        val id = liveId ?: return -1
        return items.indexOfFirst { it.id == id }
    }

    fun reset() {
        liveId = null
        activity = null
        RevealClock.reset()
    }

    /** Teks (KUMULATIF) dari balasan yang sedang tiba. Kosong = percobaan diulang / belum ada narasi: item sementara dibuang. */
    fun streaming(text: String, tool: String?, target: String?, status: String? = null) {
        activity = if (tool != null) LiveActivity(tool, target, status) else null
        val idx = liveIndex()
        if (text.isBlank()) {
            if (idx >= 0) items.removeAt(idx)
            liveId = null
            return
        }
        if (idx >= 0) {
            items[idx] = ThoughtItem(items[idx].id, text, live = true)
        } else {
            val id = nextId()
            items.add(ThoughtItem(id, text, live = true))
            liveId = id
        }
    }

    /** Narasi final satu langkah (balasan itu memanggil alat): item yang sedang mengalir menjadi narasi biasa di tempat yang sama. */
    fun thought(text: String) {
        activity = null
        val idx = liveIndex()
        if (idx >= 0) {
            items[idx] = ThoughtItem(items[idx].id, text, live = false)
            liveId = null
        } else {
            items.add(ThoughtItem(nextId(), text))
        }
    }

    /** Jawaban akhir: item yang sedang mengalir berubah menjadi FinalItem di tempat yang sama (id tetap). */
    fun finalAnswer(text: String, verified: Boolean, showStatus: Boolean) {
        activity = null
        val idx = liveIndex()
        if (idx >= 0) {
            items[idx] = FinalItem(items[idx].id, text, verified, showStatus)
            liveId = null
        } else {
            items.add(FinalItem(nextId(), text, verified, showStatus))
        }
    }

    /**
     * Dipanggil sebelum peristiwa lain (alat mulai, catatan, galat) dan saat giliran berakhir: teks yang masih "mengalir" dilepas
     * (dipertahankan sebagai narasi biasa bila tidak kosong) dan alat yang sedang ditulis dianggap selesai ditulis.
     */
    fun settle() {
        activity = null
        val idx = liveIndex()
        if (idx < 0) {
            liveId = null
            return
        }
        val t = items[idx]
        if (t is ThoughtItem) {
            if (t.text.isBlank()) items.removeAt(idx) else items[idx] = t.copy(live = false)
        }
        liveId = null
    }
}
