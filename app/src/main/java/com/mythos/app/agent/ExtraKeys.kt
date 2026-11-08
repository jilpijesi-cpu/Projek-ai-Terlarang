package com.mythos.app.agent

/**
 * Kunci TAMBAHAN untuk penyedia mana pun.
 * Dipakai di [com.mythos.app.ui.AgentViewModel.prepareClient]: tiap model dicoba dengan kunci utama lebih dulu, lalu kunci tambahan,
 * baru pindah ke model berikutnya. Kunci yang ditolak atau kena batas laju ditandai oleh [KeyPool]/[FailoverClient] seperti biasa.
 */
object ExtraKeys {
    /** Format kunci tiap penyedia berbeda-beda (sk-..., gsk_..., AIza..., AQ.xxx, nvapi-...): cukup periksa bentuk umumnya. */
    private val KEY_RE = Regex("""[A-Za-z0-9._\-]{16,}""")
    private val SPLIT_RE = Regex("""[\s,;]+""")

    data class AddResult(val added: List<String>, val duplicates: Int, val rejected: Int)

    /** Pecah teks tempelan jadi kunci baru, duplikat (terhadap [existing] dan sesama tempelan), dan yang ditolak. */
    fun parse(text: String, existing: Collection<String>): AddResult {
        val known = existing.filter { it.isNotBlank() }.toMutableSet()
        val added = ArrayList<String>()
        var dup = 0
        var rej = 0
        text.split(SPLIT_RE).map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.forEach { t ->
            when {
                !KEY_RE.matches(t) -> rej++
                !known.add(t) -> dup++
                else -> added.add(t)
            }
        }
        return AddResult(added, dup, rej)
    }

    /** Tampilan aman: awal dan akhir saja. */
    fun mask(key: String): String = if (key.length <= 12) "••••" else key.take(4) + "…" + key.takeLast(4)

    /**
     * Gandakan tiap entri model dengan kunci tambahan. Urutan: model di luar, kunci di dalam, jadi kunci lain untuk model yang sama
     * dicoba sebelum pindah ke model lain. Tanpa kunci tambahan, [specs] dikembalikan apa adanya.
     */
    fun expand(specs: List<KeySpec>, extra: List<String>): List<KeySpec> {
        if (extra.isEmpty()) return specs
        return specs.flatMap { sp ->
            val keys = (listOf(sp.apiKey) + extra).filter { it.isNotBlank() }.distinct()
            keys.mapIndexed { i, k -> if (k == sp.apiKey) sp else sp.copy(apiKey = k, label = "${sp.label} #${i + 1}") }
        }
    }
}
