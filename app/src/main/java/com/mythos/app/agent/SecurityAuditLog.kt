package com.mythos.app.agent

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Pencatat audit keamanan eksekusi alat (Security Audit Log).
 * Disimpan di luar folder pohon proyek utama (`<parent>/.mythos_audit/<proyek>.jsonl`) seperti CheckpointStore
 * agar tidak mengotori ruang kerja agen, tidak ikut terkirim ke GitHub, dan selalu menyensor kredensial/rahasia.
 */
class SecurityAuditLog(projectRoot: File) {

    data class Entry(
        val timestamp: Long,
        val step: Int,
        val tool: String,
        val risk: String,
        val taintedContext: Boolean,
        val userApproved: Boolean,
        val ok: Boolean,
        val summary: String,
    )

    private val logFile: File = run {
        val canonical = try { projectRoot.canonicalFile } catch (e: IOException) { projectRoot.absoluteFile }
        val baseDir = File(canonical.parentFile ?: canonical, ".mythos_audit")
        val safeName = canonical.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "default" }
        File(baseDir, "$safeName.jsonl")
    }

    @Synchronized
    fun record(
        step: Int,
        tool: String,
        risk: Risk,
        taintedContext: Boolean,
        userApproved: Boolean,
        ok: Boolean,
        rawSummary: String,
    ) {
        try {
            logFile.parentFile?.mkdirs()
            val cleanSummary = SensitiveDataGuard.redactSensitiveCredentials(
                redactSecrets(rawSummary).first
            ).take(400)
            val json = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("step", step)
                .put("tool", tool)
                .put("risk", risk.name)
                .put("tainted", taintedContext)
                .put("approved", userApproved)
                .put("ok", ok)
                .put("summary", cleanSummary)
            logFile.appendText(json.toString() + "\n")
        } catch (_: IOException) {
            // Pencatatan audit tidak boleh menggagalkan jalannya tugas pengguna
        }
    }

    @Synchronized
    fun readRecent(limit: Int = 50): List<Entry> {
        if (!logFile.isFile) return emptyList()
        return try {
            logFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(limit.coerceIn(1, 200))
                .mapNotNull { line ->
                    try {
                        val o = JSONObject(line)
                        Entry(
                            timestamp = o.optLong("ts"),
                            step = o.optInt("step"),
                            tool = o.optString("tool"),
                            risk = o.optString("risk"),
                            taintedContext = o.optBoolean("tainted", false),
                            userApproved = o.optBoolean("approved", false),
                            ok = o.optBoolean("ok", false),
                            summary = o.optString("summary"),
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (_: IOException) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        runCatching { if (logFile.exists()) logFile.delete() }
    }
}
