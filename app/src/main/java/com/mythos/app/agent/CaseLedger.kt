package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Alat pencatat sengketa & transaksi terstruktur (`CaseLedger`).
 * Membantu agen menangani kasus uang tertahan di e-commerce/fintech/bank, menghitung tenggat hari kerja (SLA),
 * mencatat linimasa klaim bertanggal beserta bukti, mendeteksi ketidakkonsistenan pernyataan CS,
 * dan memperbarui berkas `KASUS.md` secara rapi.
 */
class CaseLedger : Tool {
    override val name = "CaseLedger"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE

    override val doc = """CaseLedger {"action": "upsert_case"|"add_claim"|"calculate_sla"|"summary", ...} — structured dispute & transaction ledger synced with KASUS.md:
  - "upsert_case": {"action": "upsert_case", "platform": "Shopee/Flip/Bank X", "ticket_id": "730470", "transaction_id": "INV-123", "amount_idr": 2500000, "status": "Dana tertahan / Menunggu SLA", "complainant": "Nama Pengadu", "official_channel": "bicara@bi.go.id"}
  - "add_claim": {"action": "add_claim", "date": "2026-11-08 10:15", "actor": "CS Flip / Bukti Mutasi", "claim": "CS menyatakan dana sudah diteruskan, namun mutasi rekening tujuan nihil", "evidence": "uploads/mutasi.pdf", "inconsistent_with": "Klaim CS tanggal 2026-11-06"}
  - "calculate_sla": {"action": "calculate_sla", "start_date": "2026-11-08", "business_days": 14} — compute the exact business-day deadline (skipping weekends).
  - "summary": {"action": "summary"} — read current structured case summary and detected inconsistencies."""

    override fun describe(args: JSONObject): String =
        "CaseLedger (${args.str("action") ?: "summary"}: ${oneLine(args.str("platform") ?: args.str("claim") ?: args.str("start_date") ?: "", 80)})"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val action = args.str("action")?.lowercase()?.trim().orEmpty().ifEmpty { "summary" }
        val jsonFile = env.sandbox.resolve(LEDGER_JSON)
        val kasusFile = env.sandbox.resolve("KASUS.md")

        return when (action) {
            "calculate_sla" -> {
                val startStr = args.reqStr("start_date").trim()
                val days = args.intOr("business_days", args.intOr("work_days", 14)).coerceIn(1, 180)
                val deadline = addBusinessDays(startStr, days)
                    ?: throw ArgError("'start_date' harus berformat yyyy-MM-dd, misalnya 2026-11-08")
                val state = loadLedger(jsonFile)
                state.put("deadline", deadline)
                saveLedgerAndSyncMarkdown(env, jsonFile, kasusFile, state)
                ToolResult(
                    true,
                    "Perhitungan SLA: $days hari kerja (Sabtu & Minggu dilewati) sejak $startStr jatuh pada **$deadline 17:00** (waktu lokal). " +
                        "Gunakan tanggal ini pada SetDeadlineNotice atau Google Calendar, dan catat bila ada hari libur nasional."
                )
            }
            "upsert_case" -> {
                val state = loadLedger(jsonFile)
                (args.str("platform") ?: args.str("provider"))?.takeIf { it.isNotBlank() }?.let { state.put("platform", it.trim()) }
                (args.str("ticket_id") ?: args.str("ticket_number"))?.takeIf { it.isNotBlank() }?.let { state.put("ticket_id", it.trim()) }
                args.str("transaction_id")?.takeIf { it.isNotBlank() }?.let { state.put("transaction_id", it.trim()) }
                if (args.has("amount_idr") && !args.isNull("amount_idr")) {
                    state.put("amount_idr", args.optLong("amount_idr", 0L))
                }
                args.str("status")?.takeIf { it.isNotBlank() }?.let { state.put("status", it.trim()) }
                args.str("complainant")?.takeIf { it.isNotBlank() }?.let { state.put("complainant", it.trim()) }
                (args.str("official_channel") ?: args.str("regulator"))?.takeIf { it.isNotBlank() }?.let { state.put("official_channel", it.trim()) }
                args.str("deadline")?.takeIf { it.isNotBlank() }?.let { state.put("deadline", it.trim()) }
                val changed = saveLedgerAndSyncMarkdown(env, jsonFile, kasusFile, state)
                ToolResult(
                    true,
                    "Data pokok sengketa diperbarui dan disinkronkan ke KASUS.md:\n" + renderSummary(state),
                    changed = changed,
                    readPaths = changed
                )
            }
            "add_claim" -> {
                val date = args.reqStr("date").trim()
                val actor = (args.str("actor") ?: args.str("party"))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ArgError("argumen 'actor' wajib diisi")
                val claim = args.reqStr("claim").trim()
                if (claim.isEmpty()) throw ArgError("'claim' tidak boleh kosong")
                val evidence = (args.str("evidence") ?: args.str("evidence_file"))?.trim().orEmpty()
                val inconsistent = (args.str("inconsistent_with") ?: args.str("contradiction"))?.trim().orEmpty()

                val state = loadLedger(jsonFile)
                val claims = state.optJSONArray("claims") ?: JSONArray().also { state.put("claims", it) }
                claims.put(
                    JSONObject()
                        .put("date", date)
                        .put("actor", actor)
                        .put("claim", claim)
                        .put("evidence", evidence)
                        .put("inconsistent_with", inconsistent)
                )
                val changed = saveLedgerAndSyncMarkdown(env, jsonFile, kasusFile, state)
                ToolResult(
                    true,
                    "Klaim/fakta tanggal $date ($actor) tercatat di KASUS.md (total ${claims.length()} entri linimasa).",
                    changed = changed,
                    readPaths = changed
                )
            }
            "summary" -> {
                val state = loadLedger(jsonFile)
                val md = if (kasusFile.isFile) kasusFile.readText().take(4_000) else "(KASUS.md belum dibuat)"
                ToolResult(true, renderSummary(state) + "\n\n--- Isi KASUS.md ---\n" + md)
            }
            else -> throw ArgError("'action' tidak dikenal: $action (gunakan upsert_case, add_claim, calculate_sla, atau summary)")
        }
    }

    companion object {
        const val LEDGER_JSON = "kasus_ledger.json"

        internal fun addBusinessDays(startYmd: String, businessDays: Int): String? {
            val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(startYmd.trim()) ?: return null
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
                isLenient = false
                clear()
                set(y, mo - 1, d, 9, 0, 0)
            }
            try {
                cal.timeInMillis
            } catch (_: IllegalArgumentException) {
                return null
            }
            var remaining = businessDays
            while (remaining > 0) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                    remaining--
                }
            }
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = cal.timeZone }.format(cal.time)
        }

        private fun loadLedger(f: File): JSONObject =
            if (f.isFile) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
            else JSONObject()

        private fun saveLedgerAndSyncMarkdown(
            env: ToolEnv,
            jsonFile: File,
            kasusFile: File,
            state: JSONObject,
        ): List<String> {
            val changed = ArrayList<String>()
            val jsonRel = env.sandbox.relative(jsonFile)
            env.journal?.beforeChange(jsonFile)
            val jsonText = state.toString(2)
            jsonFile.writeText(jsonText)
            env.session.known[jsonRel] = jsonText.hashCode()
            changed.add(jsonRel)

            // Sinkronkan bagian terstruktur ke KASUS.md tanpa menghapus catatan manual di luar penanda
            val kasusRel = env.sandbox.relative(kasusFile)
            env.journal?.beforeChange(kasusFile)
            val block = renderMarkdownBlock(state)
            val existing = if (kasusFile.isFile) kasusFile.readText() else ""
            val updated = if (existing.contains(BEGIN_MARKER) && existing.contains(END_MARKER)) {
                val s = existing.indexOf(BEGIN_MARKER)
                val e = existing.indexOf(END_MARKER) + END_MARKER.length
                existing.substring(0, s) + block + existing.substring(e)
            } else if (existing.isBlank()) {
                block + "\n"
            } else {
                block + "\n\n" + existing.trim() + "\n"
            }
            kasusFile.writeText(updated)
            env.session.known[kasusRel] = updated.hashCode()
            changed.add(kasusRel)
            return changed
        }

        private const val BEGIN_MARKER = "<!-- BEGIN_CASE_LEDGER -->"
        private const val END_MARKER = "<!-- END_CASE_LEDGER -->"

        internal fun renderSummary(state: JSONObject): String = buildString {
            val platform = state.optString("platform", "-")
            val ticket = state.optString("ticket_id", "-")
            val trx = state.optString("transaction_id", "-")
            val amount = state.optLong("amount_idr", 0L)
            val status = state.optString("status", "-")
            val complainant = state.optString("complainant", "-")
            val channel = state.optString("official_channel", "-")
            val deadline = state.optString("deadline", "-")
            append("Platform/Pihak: $platform | Tiket: $ticket | Transaksi: $trx\n")
            if (amount > 0) append("Nominal Tertahan/Sengketa: Rp %,d\n".format(Locale.US, amount).replace(',', '.'))
            append("Pengadu: $complainant | Status: $status | Kanal Resmi: $channel | Tenggat: $deadline")
            val claims = state.optJSONArray("claims") ?: JSONArray()
            val inconsistencies = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }
                .filter { it.optString("inconsistent_with").isNotBlank() }
            if (inconsistencies.isNotEmpty()) {
                append("\nKetidakkonsistenan terdeteksi: ${inconsistencies.size} butir")
            }
        }

        private fun renderMarkdownBlock(state: JSONObject): String = buildString {
            appendLine(BEGIN_MARKER)
            appendLine("# Ringkasan Kasus & Transaksi")
            appendLine("- **Platform / Penyelenggara**: ${state.optString("platform", "[belum diisi]")}")
            appendLine("- **Nomor Tiket / Pengaduan**: ${state.optString("ticket_id", "[belum diisi]")}")
            appendLine("- **ID Transaksi / Invoice**: ${state.optString("transaction_id", "[belum diisi]")}")
            val amt = state.optLong("amount_idr", 0L)
            if (amt > 0) {
                appendLine("- **Nominal Sengketa / Dana Tertahan**: Rp ${"%,d".format(Locale.US, amt).replace(',', '.')}")
            }
            appendLine("- **Nama Pengadu**: ${state.optString("complainant", "[belum diisi]")}")
            appendLine("- **Saluran Resmi Tujuan**: ${state.optString("official_channel", "[belum diverifikasi]")}")
            appendLine("- **Status & Tenggat SLA**: ${state.optString("status", "Dalam proses")} (Tenggat: ${state.optString("deadline", "-")})")
            val claims = state.optJSONArray("claims") ?: JSONArray()
            if (claims.length() > 0) {
                appendLine()
                appendLine("## Linimasa Klaim, Fakta & Bukti")
                for (i in 0 until claims.length()) {
                    val c = claims.optJSONObject(i) ?: continue
                    val ev = c.optString("evidence").let { if (it.isNotBlank()) " *(Bukti: `$it`)*" else "" }
                    val inc = c.optString("inconsistent_with").let {
                        if (it.isNotBlank()) " — **[Tidak konsisten dengan: $it]**" else ""
                    }
                    appendLine("${i + 1}. **[${c.optString("date")}] ${c.optString("actor")}**: ${c.optString("claim")}$ev$inc")
                }
            }
            append(END_MARKER)
        }
    }
}
