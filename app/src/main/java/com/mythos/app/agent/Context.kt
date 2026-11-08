package com.mythos.app.agent

import org.json.JSONException
import org.json.JSONObject

/**
 * Pemadatan konteks deterministik (tanpa panggilan LLM tambahan):
 * pesan pertama dan pesan terbaru tetap utuh; <tool_result> lama dikosongkan (galat tetap dipertahankan),
 * dan argumen <tool_use> lama dipangkas (isi file panjang dibuang).
 */
internal object ContextManager {

    private val RESULT = Regex("<tool_result ([^>]*)>\\n?([\\s\\S]*?)\\n?</tool_result>")
    private val USE = Regex("<tool_use>([\\s\\S]*?)</tool_use>")

    /** Hasil pemadatan: [view] yang dikirim ke model dan [keep] = jumlah pesan terbaru yang benar-benar masih utuh. */
    class Packed(val view: List<Msg>, val keep: Int, val compacted: Boolean)

    fun compact(history: List<Msg>, budget: Int, keepRecent: Int): List<Msg> = compactWithInfo(history, budget, keepRecent).view

    fun compactWithInfo(history: List<Msg>, budget: Int, keepRecent: Int): Packed {
        if (history.sumOf { it.text.length } <= budget) return Packed(history, history.size, false)
        var keep = keepRecent
        var view = history
        var used = keepRecent
        while (keep >= 4) {
            view = digestOlder(history, keep)
            used = keep
            if (view.sumOf { it.text.length } <= budget) return Packed(view, used, true)
            keep -= 2
        }
        return Packed(view, used, view !== history)
    }

    private val LIMIT_REQ = Regex("limit\\s*(\\d+).*?requested\\s*(\\d+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * Anggaran konteks baru setelah penyedia menolak ukuran (HTTP 413). Bila pesan galat memuat "Limit L ... Requested R",
     * dikecilkan menurut rasio L/R (dengan sisa aman); bila tidak, separuhnya. Dihitung dari ukuran riwayat yang SEBENARNYA
     * terkirim ([usedChars]), karena anggaran bawaan bisa jauh lebih besar dari riwayatnya.
     */
    fun shrunkBudget(usedChars: Int, errorMessage: String?): Int {
        val m = LIMIT_REQ.find(errorMessage.orEmpty())
        val limit = m?.groupValues?.get(1)?.toDoubleOrNull()
        val req = m?.groupValues?.get(2)?.toDoubleOrNull()
        val ratio = if (limit != null && req != null && req > 0 && limit < req) limit / req * 0.7 else 0.5
        return (usedChars * ratio).toInt().coerceAtLeast(3_000)
    }

    /** Batas token yang disebut penyedia pada galat 413 ("Limit 8000 ... Requested 9620"), atau null bila tidak disebut. */
    fun parseLimit(errorMessage: String?): Int? =
        LIMIT_REQ.find(errorMessage.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** Perkiraan kasar: 1 token ~ 3,5 karakter. */
    private const val CHARS_PER_TOKEN = 3.5

    /**
     * True bila bagian tetap (system prompt + dokumentasi alat) saja sudah hampir/melebihi batas token penyedia.
     * Memperkecil riwayat tidak akan membantu, jadi percobaan ulang hanya membuang waktu.
     */
    fun fixedPartTooLarge(systemChars: Int, errorMessage: String?): Boolean {
        val limit = LIMIT_REQ.find(errorMessage.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull() ?: return false
        return systemChars / CHARS_PER_TOKEN >= limit * 0.85
    }

    fun describeFixedTooLarge(systemChars: Int, errorMessage: String?): String {
        val limit = LIMIT_REQ.find(errorMessage.orEmpty())?.groupValues?.get(1).orEmpty()
        val tokens = (systemChars / CHARS_PER_TOKEN / 100).toInt() * 100
        return "Instruksi dasar aplikasi (system prompt dan daftar alat, sekitar $tokens token) sudah hampir atau melebihi batas $limit token milik penyedia ini, " +
            "jadi memperkecil riwayat chat tidak membantu. Pilih model/penyedia dengan batas token lebih besar di Pengaturan."
    }

    /** Teks ringkas untuk layar saat 413 (tanpa JSON mentah/ID organisasi dari penyedia). Satuan "per menit" hanya disebut bila pesan aslinya menyebutnya. */
    fun describeTooLarge(errorMessage: String?): String {
        val msg = errorMessage.orEmpty()
        val m = LIMIT_REQ.find(msg) ?: return "Permintaan terlalu besar untuk penyedia."
        val perMinute = msg.contains("per minute", ignoreCase = true) || msg.contains("TPM")
        return "Permintaan terlalu besar untuk penyedia: batas ${m.groupValues[1]} token${if (perMinute) " per menit" else ""}, permintaan ${m.groupValues[2]} token."
    }

    /** Pemangkasan paksa: pesan terpanjang dipotong bagian tengahnya sampai total muat [budget]. Pesan tetap utuh bila sudah muat. */
    fun squeeze(view: List<Msg>, budget: Int): List<Msg> {
        var cur = view
        val touched = HashSet<Int>()
        var guard = 0
        while (guard++ < 50) {
            val total = cur.sumOf { it.text.length }
            if (total <= budget) break
            val i = cur.indices.filter { it !in touched && cur[it].text.length > 460 }
                .maxByOrNull { cur[it].text.length } ?: break
            val m = cur[i]
            val target = (m.text.length - (total - budget) - 120).coerceAtLeast(400)
            if (target >= m.text.length) break
            val cut = cutMiddle(m.text, target)
            if (cut.length >= m.text.length) {
                touched.add(i)
                continue
            }
            if (target <= 400) touched.add(i)
            cur = cur.toMutableList().also { it[i] = Msg(m.role, cut, m.images) }
        }
        return cur
    }

    private fun cutMiddle(t: String, max: Int): String {
        val head = max * 2 / 3
        return t.substring(0, head) + "\n[... ${t.length - max} karakter dipotong agar muat batas penyedia ...]\n" + t.takeLast(max - head)
    }

    private fun digestOlder(history: List<Msg>, keep: Int): List<Msg> {
        val cutoff = history.size - keep
        return history.mapIndexed { i, m ->
            if (i == 0 || i >= cutoff) m
            else if (m.role == Role.USER) Msg(m.role, digestResults(m.text))
            else Msg(m.role, digestAssistant(m.text))
        }
    }

    private fun digestResults(t: String): String {
        if (!t.contains("<tool_result")) return clip(t, 500)
        val noReminders = t.replace(Regex("<system-reminder>[\\s\\S]*?</system-reminder>"), "").trim()
        return RESULT.replace(noReminders) { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            if (attrs.contains("is_error=\"true\"")) "<tool_result $attrs>\n${clip(body, 500)}\n</tool_result>"
            else "<tool_result $attrs>[content cleared: ${body.length} chars; run the tool again if needed]</tool_result>"
        }
    }

    private fun digestAssistant(t: String): String {
        val withSlimCalls = USE.replace(t) { m -> Protocol.OPEN + slimCall(m.groupValues[1]) + Protocol.CLOSE }
        val firstUse = withSlimCalls.indexOf(Protocol.OPEN)
        if (firstUse <= 0) return clip(withSlimCalls, 600)
        return clip(withSlimCalls.substring(0, firstUse), 240) + "\n" + withSlimCalls.substring(firstUse)
    }

    private fun slimCall(body: String): String = try {
        val o = JSONObject(body.trim())
        val input = o.optJSONObject("input")
        if (input != null) {
            val slim = JSONObject()
            for (k in input.keys()) {
                val v = input.opt(k)
                slim.put(k, if (v is String && v.length > 200) "<${v.length} chars trimmed>" else v)
            }
            o.put("input", slim)
        }
        unescapeSlashes(o.toString())
    } catch (e: JSONException) {
        clip(body, 300)
    }
}
