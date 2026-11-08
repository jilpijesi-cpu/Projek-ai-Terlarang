package com.mythos.app.agent

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pengukur waktu SATU giliran (kirim -> jawaban), supaya kelambatan bisa dibaca dari angka, bukan ditebak.
 * Tidak bergantung pada Android, jadi bisa diuji di JVM. Ringkasan ditampilkan kecil di bawah jawaban akhir
 * selama [SHOW] true (ubah ke false bila tak diperlukan lagi).
 *
 * Cara membaca ringkasan:
 *  - "persiapan"  : waktu menyiapkan klien/model sebelum panggilan pertama.
 *  - "token model pertama" : saat byte teks pertama tiba dari penyedia (sudah termasuk jeda coba-ulang).
 *  - "teks pertama" : saat teks pertama benar-benar tampil di layar.
 *  - "coba ulang" : berapa kali penyedia menolak/sibuk lalu diulang otomatis, total jeda tunggunya, dan alasan terakhir.
 */
object LatencyTrace {
    const val SHOW = true

    private val lock = Any()
    private var clock: () -> Long = { System.nanoTime() / 1_000_000L }
    private var t0 = 0L
    private var readyAt = -1L
    private var modelCalls = 0
    private var firstModelToken = -1L
    private var firstVisible = -1L
    private var tools = 0
    private var retries = 0
    private var retryWaitMs = 0L
    private var lastRetry = ""
    private val saved = ConcurrentHashMap<Long, String>()

    internal fun useClock(c: () -> Long) = synchronized(lock) { clock = c }

    fun begin() = synchronized(lock) {
        t0 = clock()
        readyAt = -1L
        modelCalls = 0
        firstModelToken = -1L
        firstVisible = -1L
        tools = 0
        retries = 0
        retryWaitMs = 0L
        lastRetry = ""
    }

    /** Klien/model sudah siap dipakai (sebelum panggilan model pertama). */
    fun ready() = synchronized(lock) { if (readyAt < 0) readyAt = clock() - t0 }

    fun modelCall() = synchronized(lock) { modelCalls++ }

    /** Potongan teks pertama dari penyedia tiba. Hanya yang pertama dicatat. */
    fun modelToken() = synchronized(lock) { if (firstModelToken < 0) firstModelToken = clock() - t0 }

    /** Teks pertama tampil di layar. Hanya yang pertama dicatat. */
    fun visibleText() = synchronized(lock) { if (firstVisible < 0) firstVisible = clock() - t0 }

    fun tool() = synchronized(lock) { tools++ }

    fun retry(reason: String, waitMs: Long) = synchronized(lock) {
        retries++
        retryWaitMs += waitMs
        lastRetry = reason.replace('\n', ' ').trim().take(60)
    }

    /** Menutup giliran dan mengembalikan ringkasan satu baris. */
    fun finish(): String = synchronized(lock) {
        val total = clock() - t0
        val parts = ArrayList<String>()
        if (readyAt >= 0) parts.add("persiapan ${sec(readyAt)}")
        if (firstModelToken >= 0) parts.add("token model pertama ${sec(firstModelToken)}")
        if (firstVisible >= 0) parts.add("teks pertama ${sec(firstVisible)}")
        parts.add("total ${sec(total)}")
        if (modelCalls > 0) parts.add("model ${modelCalls}x")
        if (tools > 0) parts.add("alat ${tools}x")
        if (retries > 0) {
            val why = if (lastRetry.isEmpty()) "" else ": $lastRetry"
            parts.add("coba ulang ${retries}x (${sec(retryWaitMs)} menunggu$why)")
        }
        "⏱ " + parts.joinToString(" · ")
    }

    fun keep(id: Long, summary: String) {
        saved[id] = summary
        while (saved.size > 50) saved.keys.firstOrNull()?.let { saved.remove(it) } ?: break
    }

    fun summaryOf(id: Long): String? = saved[id]

    private fun sec(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0).replace('.', ',') + " dtk"
}
