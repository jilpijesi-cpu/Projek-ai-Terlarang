package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

private val EMAIL_RE = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
/** Deretan 16 angka (NIK), boleh dipisah spasi/titik/strip, yang bukan bagian dari angka lebih panjang. */
private val FULL_NIK_RE = Regex("(?<!\\d)(?:\\d[ .-]?){15}\\d(?!\\d)")

private const val MAX_ATTACH_BYTES = 20L * 1024 * 1024

/** Daftar string dari JSON: array, atau satu string yang dipisah koma/titik koma/baris baru. */
internal fun JSONObject.stringList(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val v = opt(key)
    val raw = if (v is JSONArray) (0 until v.length()).map { v.optString(it) } else listOf(v.toString())
    return raw.flatMap { it.split(',', ';', '\n') }.map { it.trim() }.filter { it.isNotEmpty() }
}

/** "2026-11-10 09:00" (waktu lokal pengguna) -> epoch ms; null bila format/tanggal tidak sah. */
internal fun parseLocalDateTime(s: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }.parse(s.trim())?.time
} catch (e: Exception) { null }

/** "08:30" -> (8, 30); null bila tidak sah. */
internal fun parseClock(s: String): Pair<Int, Int>? {
    val m = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(s.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
}

/** Draf email dari argumen alat yang sudah lolos pemeriksaan, untuk kartu di chat. */
internal fun emailDraftEvent(args: JSONObject) = AgentEvent.EmailDraft(
    args.stringList("to"), args.stringList("cc"), args.reqStr("subject").trim(), args.reqStr("body"), args.stringList("attachments")
)

/**
 * Menyiapkan draf email dan menampilkannya sebagai KARTU di chat (penerima, subjek, isi, lampiran PDF). Alat ini TIDAK membuka aplikasi
 * email dan TIDAK PERNAH mengirim: aplikasi email baru dibuka saat pengguna mengetuk "Kirim email" pada kartu, dan di sana pengguna
 * masih harus menekan Kirim sendiri. Karena kartu itu sendiri adalah peninjauannya, alat ini tidak memakai dialog persetujuan.
 */
class ComposeEmail : Tool {
    override val name = "ComposeEmail"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """ComposeEmail {"to": ["bicara@bi.go.id"], "cc": ["lapor@flip.id"], "subject": "...", "body": "teks polos", "attachments": ["uploads/lampiran.pdf"]} — show a finished email draft as a CARD in the chat (recipients, subject, body, and the project files as PDF attachment cards). It does not open the email app and NEVER sends: the user reads the card, taps "Kirim email" on it, and then presses Send in the email app, so say "draf ada di kartu, belum terkirim" and never claim it was sent. Do not paste the email text again in your reply; the card already shows it, so add at most one sentence. One call = one email: when the user wants separate emails for separate recipients, make separate calls and never merge them. Attach an ID card, selfie or other personal document only when the recipient asked for it and the user agreed. Use plain text in the body, no markdown."""

    override fun describe(args: JSONObject): String = buildString {
        val to = args.stringList("to"); val cc = args.stringList("cc"); val att = args.stringList("attachments")
        append("Siapkan kartu draf email (TIDAK terkirim otomatis)\nKepada: ").append(to.joinToString().ifEmpty { "?" })
        if (cc.isNotEmpty()) append("\nCc: ").append(cc.joinToString())
        append("\nSubjek: ").append(oneLine(args.str("subject").orEmpty(), 160))
        append("\nIsi: ").append(oneLine(args.str("body").orEmpty(), 300))
        append("\nLampiran: ").append(if (att.isEmpty()) "tidak ada" else att.joinToString())
    }

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        env.device ?: return ToolResult(false, "Aplikasi email tidak tersedia di sini. Berikan email dalam blok ```salin supaya pengguna menyalinnya sendiri.")
        val to = args.stringList("to")
        if (to.isEmpty()) throw ArgError("argumen 'to' wajib diisi")
        val cc = args.stringList("cc")
        for (a in to + cc) if (!EMAIL_RE.matches(a)) throw ArgError("alamat email tidak valid: $a")
        val subject = args.reqStr("subject").trim()
        val body = args.reqStr("body")
        if (subject.isEmpty()) throw ArgError("argumen 'subject' tidak boleh kosong")
        if (body.isBlank()) throw ArgError("argumen 'body' tidak boleh kosong")
        if (FULL_NIK_RE.containsMatchIn(body) || FULL_NIK_RE.containsMatchIn(subject)) {
            return ToolResult(false, "Draf TIDAK dibuka: badan atau subjek email memuat angka 16 digit (tampak seperti NIK lengkap). NIK sudah ada di PDF lampiran, jadi jangan diulang. Tulis tanpa nomornya atau samarkan (empat angka pertama dan terakhir saja), lalu panggil ComposeEmail lagi.")
        }
        val files = args.stringList("attachments").map { env.sandbox.resolve(it) }
        for (f in files) if (!f.isFile) return ToolResult(false, "Lampiran tidak ditemukan: ${env.sandbox.relative(f)}")
        if (files.sumOf { it.length() } > MAX_ATTACH_BYTES) return ToolResult(false, "Total lampiran lebih dari 20 MB; kecilkan atau bagi ke beberapa email.")
        return ToolResult(true, "Kartu draf email ke ${to.joinToString()}${if (cc.isNotEmpty()) " (cc ${cc.joinToString()})" else ""} dengan ${files.size} lampiran sudah tampil di chat. BELUM TERKIRIM dan aplikasi email BELUM dibuka: pengguna mengetuk \"Kirim email\" pada kartu, lalu menekan Kirim sendiri di aplikasi email. Jangan menyalin isi email ke jawaban dan jangan menyatakan email sudah terkirim.")
    }
}

/** Pengingat tenggat lewat jam (alarm) atau kalender; layar aplikasi tujuan terbuka dan pengguna yang menyimpan. */
class SetReminder : Tool {
    override val name = "SetReminder"
    override val risk = Risk.WRITE
    override val effect = Effect.EXEC
    override val doc = """SetReminder {"kind": "alarm", "time": "08:00", "label": "Cek balasan Flip"} or {"kind": "calendar", "title": "Tenggat jawaban Flip", "start": "2026-11-10 09:00", "duration_minutes": 30, "notes": "Tiket 730470"} — open the clock or calendar app with a reminder filled in; the user confirms it there, so it is NOT saved until they do. Use it for deadlines the user or a counterparty gave (for example "paling lambat Selasa"). Times are the user's local time; take today's date from the environment context and compute the date yourself; never guess a date you cannot derive."""

    override fun describe(args: JSONObject): String =
        if (args.str("kind") == "calendar") "Buka kalender: ${oneLine(args.str("title").orEmpty(), 80)} pada ${args.str("start").orEmpty()}"
        else "Buka jam: alarm ${args.str("time").orEmpty()} ${oneLine(args.str("label").orEmpty(), 80)}"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val dev = env.device ?: return ToolResult(false, "Jam/kalender tidak tersedia di sini. Sampaikan tenggatnya kepada pengguna dalam teks.")
        return when (val kind = args.reqStr("kind").lowercase()) {
            "alarm" -> {
                val (h, m) = parseClock(args.reqStr("time")) ?: throw ArgError("'time' harus berformat HH:mm, misalnya 08:30")
                val label = args.str("label")?.trim().orEmpty().ifEmpty { "Pengingat" }
                val err = dev.setAlarm(h, m, label)
                if (err == null) ToolResult(true, "Aplikasi jam dibuka dengan alarm %02d:%02d (%s). Belum tentu tersimpan: pengguna harus menyetujuinya di sana.".format(h, m, label)) else ToolResult(false, err)
            }
            "calendar" -> {
                val title = args.reqStr("title").trim()
                if (title.isEmpty()) throw ArgError("'title' tidak boleh kosong")
                val start = parseLocalDateTime(args.reqStr("start")) ?: throw ArgError("'start' harus berformat yyyy-MM-dd HH:mm dan tanggalnya sah")
                val minutes = args.intOr("duration_minutes", 30).coerceIn(5, 24 * 60)
                val err = dev.addCalendarEvent(title, start, start + minutes * 60_000L, args.str("notes").orEmpty())
                if (err == null) ToolResult(true, "Aplikasi kalender dibuka dengan acara \"$title\" pada ${args.str("start")}. Belum tentu tersimpan: pengguna harus menyimpannya di sana.") else ToolResult(false, err)
            }
            else -> throw ArgError("'kind' harus 'alarm' atau 'calendar', bukan '$kind'")
        }
    }
}

/**
 * Pemantau tenggat: notifikasi di ponsel pada waktu tertentu (mis. "batas Flip hari ini"), tanpa membuka aplikasi lain.
 * Berbeda dari SetReminder (alarm/kalender yang disimpan pengguna sendiri), ini dijadwalkan langsung oleh aplikasi.
 */
class SetDeadlineNotice : Tool {
    override val name = "SetDeadlineNotice"
    override val risk = Risk.WRITE
    override val effect = Effect.EXEC
    override val doc = """SetDeadlineNotice {"title": "Batas jawaban Flip", "at": "2026-11-10 09:00", "notes": "Tiket 730470: minta saldo dikembalikan", "remind_before_minutes": 1440} — schedule a phone NOTIFICATION that appears at "at" (user's local time, yyyy-MM-dd HH:mm, must be in the future). Optional "remind_before_minutes" (5 to 10080) adds an earlier notice, for example 1440 = one day before. It runs inside the app, survives closing the app and a reboot, and needs no confirmation screen. Use it for deadlines of the user or the other side, such as "batas Flip hari ini". Take today's date from the environment context and compute the date yourself; never guess a date. Say exactly what was scheduled."""

    override fun describe(args: JSONObject): String =
        "Jadwalkan notifikasi: ${oneLine(args.str("title").orEmpty(), 80)} pada ${args.str("at").orEmpty()}"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val dev = env.device ?: return ToolResult(false, "Notifikasi terjadwal tidak tersedia di sini. Sampaikan tenggatnya kepada pengguna dalam teks.")
        val title = args.reqStr("title").trim()
        if (title.isEmpty()) throw ArgError("'title' tidak boleh kosong")
        val at = parseLocalDateTime(args.reqStr("at")) ?: throw ArgError("'at' harus berformat yyyy-MM-dd HH:mm dan tanggalnya sah")
        val now = System.currentTimeMillis()
        if (at <= now) return ToolResult(false, "Waktu ${args.str("at")} sudah lewat. Hitung ulang tanggalnya dari tanggal hari ini, atau tanyakan kepada pengguna.")
        val notes = args.str("notes").orEmpty().trim().take(300)
        val before = args.intOr("remind_before_minutes", 0)
        if (before != 0 && before !in 5..10_080) throw ArgError("'remind_before_minutes' harus antara 5 dan 10080")
        val key = (title + "|" + args.str("at")).lowercase().replace(Regex("[^a-z0-9|]+"), "-").take(80)
        val scheduled = ArrayList<String>()
        if (before > 0 && at - before * 60_000L > now) {
            val early = at - before * 60_000L
            dev.scheduleNotice("$key|awal", "Segera: $title", notes.ifEmpty { "Tenggat ${args.str("at")}" }, early)?.let { return ToolResult(false, it) }
            scheduled.add("pengingat awal ${before} menit sebelumnya")
        }
        dev.scheduleNotice(key, title, notes.ifEmpty { "Tenggat ${args.str("at")}" }, at)?.let { return ToolResult(false, it) }
        scheduled.add("notifikasi pada ${args.str("at")}")
        return ToolResult(true, "Terjadwal di ponsel: ${scheduled.joinToString(", ")} untuk \"$title\". Notifikasi muncul meski aplikasi ditutup; jika izin notifikasi Android dimatikan, tidak akan tampil.")
    }
}
