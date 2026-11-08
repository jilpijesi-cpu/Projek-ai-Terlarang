package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/*
 * Titik pulih per tugas. Alat Write/Edit/Delete satu-satunya yang mengubah file proyek, jadi cukup mencatat isi ASLI sebuah
 * file tepat sebelum ia pertama kali diubah dalam satu tugas (copy-on-write): tidak perlu menyalin seluruh proyek. Setelah
 * tugas selesai, isi akhirnya disalin juga, sehingga diff stabil dan pembatalan tahu apakah file sudah diubah lagi sesudahnya.
 *
 * Tata letak (di luar folder proyek, jadi tak terlihat oleh agen, tak ikut ZIP ekspor, dan tak didorong ke GitHub):
 *   <base>/<proyek>/<id>/manifest.json   daftar file + hasil perhitungan
 *   <base>/<proyek>/<id>/before/<path>   isi asli (hanya file yang sudah ada sebelumnya)
 *   <base>/<proyek>/<id>/after/<path>    isi akhir (hanya file yang ada setelah tugas)
 */

enum class ChangeKind { ADDED, MODIFIED, DELETED }

/** Perubahan bersih satu file selama satu tugas. [added]/[removed] = jumlah baris; [binary] = tanpa hitungan baris. */
data class FileChange(val path: String, val kind: ChangeKind, val added: Int, val removed: Int, val binary: Boolean = false)

/** Hasil pembatalan: file yang dipulihkan, dan yang dilewati beserta alasannya. */
class UndoReport(val restored: List<String>, val skipped: List<Pair<String, String>>)

private const val MAX_COPY_BYTES = 10_000_000L   // file lebih besar tidak dicadangkan
private const val MAX_DIFF_BYTES = 2_000_000L    // file teks lebih besar tidak dihitung barisnya

private fun relOf(root: File, f: File): String? = try {
    f.relativeTo(root).path.replace(File.separatorChar, '/').takeIf { it.isNotEmpty() && !it.startsWith("..") }
} catch (e: IllegalArgumentException) {
    null
}

private fun copyLimited(src: File, dst: File): Boolean {
    if (!src.isFile || src.length() > MAX_COPY_BYTES) return false
    return try {
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        true
    } catch (e: IOException) {
        false
    }
}

private fun sameBytes(a: File, b: File): Boolean {
    if (!a.isFile || !b.isFile || a.length() != b.length()) return false
    return try {
        a.inputStream().use { sa ->
            b.inputStream().use { sb ->
                val ba = ByteArray(8192)
                val bb = ByteArray(8192)
                while (true) {
                    val na = sa.read(ba)
                    if (na < 0) return sb.read() < 0
                    var off = 0
                    while (off < na) {
                        val nb = sb.read(bb, off, na - off)
                        if (nb <= 0) return false
                        off += nb
                    }
                    for (i in 0 until na) if (ba[i] != bb[i]) return false
                }
                true
            }
        }
    } catch (e: IOException) {
        false
    }
}

/** Baris file teks, atau null bila biner / terlalu besar / tak terbaca. */
private fun textLines(f: File): List<String>? {
    if (!f.isFile || f.length() > MAX_DIFF_BYTES) return null
    return try {
        if (isBinary(f)) null else contentLines(f.readText())
    } catch (e: IOException) {
        null
    }
}

private fun writeJson(dir: File, name: String, json: JSONObject) {
    try {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.toString())
        val dest = File(dir, name)
        if (!tmp.renameTo(dest)) {
            dest.writeText(json.toString())
            tmp.delete()
        }
    } catch (e: IOException) {
        // pencatatan tidak boleh menggagalkan tugas pengguna
    }
}

/** Diff baris sederhana (LCS) dengan pemangkasan awalan/akhiran yang sama; tanpa dependensi. */
object LineDiff {
    class Line(val kind: Char, val text: String)   // ' ' tetap, '+' ditambah, '-' dihapus

    private const val MAX_CELLS = 4_000_000L

    fun diff(a: List<String>, b: List<String>): List<Line> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val out = ArrayList<Line>()
        for (i in 0 until start) out.add(Line(' ', a[i]))
        val n = endA - start
        val m = endB - start
        if (n == 0) {
            for (j in start until endB) out.add(Line('+', b[j]))
        } else if (m == 0) {
            for (i in start until endA) out.add(Line('-', a[i]))
        } else if (n.toLong() * m > MAX_CELLS) {
            // terlalu besar untuk LCS: tampilkan sebagai "ganti semua" (tetap diff yang benar, hanya kasar)
            for (i in start until endA) out.add(Line('-', a[i]))
            for (j in start until endB) out.add(Line('+', b[j]))
        } else {
            val w = m + 1
            val t = ShortArray((n + 1) * w)   // t[i*w+j] = panjang LCS dari akhiran a[start+i..] dan b[start+j..] (<= 2000 muat di Short)
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    t[i * w + j] = if (a[start + i] == b[start + j]) {
                        (t[(i + 1) * w + j + 1] + 1).toShort()
                    } else {
                        maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
                    }
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (a[start + i] == b[start + j]) {
                    out.add(Line(' ', a[start + i]))
                    i++
                    j++
                } else if (t[(i + 1) * w + j] >= t[i * w + j + 1]) {
                    out.add(Line('-', a[start + i]))
                    i++
                } else {
                    out.add(Line('+', b[start + j]))
                    j++
                }
            }
            while (i < n) {
                out.add(Line('-', a[start + i]))
                i++
            }
            while (j < m) {
                out.add(Line('+', b[start + j]))
                j++
            }
        }
        for (i in endA until a.size) out.add(Line(' ', a[i]))
        return out
    }

    /** (baris ditambah, baris dihapus). */
    fun counts(a: List<String>, b: List<String>): Pair<Int, Int> {
        var added = 0
        var removed = 0
        for (l in diff(a, b)) {
            if (l.kind == '+') added++ else if (l.kind == '-') removed++
        }
        return added to removed
    }

    /**
     * Diff gaya unified: tiap baris diawali ' ', '+' atau '-', header hunk diawali "@@". Hanya baris berubah + [context] baris
     * di sekitarnya; dipotong setelah [maxLines] baris.
     */
    fun unified(a: List<String>, b: List<String>, context: Int = 3, maxLines: Int = 400): String {
        val d = diff(a, b)
        val show = BooleanArray(d.size)
        for (i in d.indices) {
            if (d[i].kind != ' ') {
                for (k in maxOf(0, i - context)..minOf(d.size - 1, i + context)) show[k] = true
            }
        }
        val sb = StringBuilder()
        var oldNo = 1
        var newNo = 1
        var emitted = 0
        var i = 0
        while (i < d.size) {
            if (!show[i]) {
                if (d[i].kind != '+') oldNo++
                if (d[i].kind != '-') newNo++
                i++
                continue
            }
            sb.append("@@ -").append(oldNo).append(" +").append(newNo).append(" @@\n")
            while (i < d.size && show[i]) {
                if (emitted >= maxLines) {
                    sb.append("… (dipotong; ").append(d.size - i).append(" baris lagi)\n")
                    return sb.toString().trimEnd()
                }
                val l = d[i]
                sb.append(l.kind).append(l.text).append('\n')
                emitted++
                if (l.kind != '+') oldNo++
                if (l.kind != '-') newNo++
                i++
            }
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Catatan satu tugas yang sedang berjalan. [beforeChange] dipanggil SEBELUM Write/Edit/Delete menyentuh sebuah file;
 * hanya pemanggilan pertama per file yang menyalin isi aslinya. [finish] dipanggil sekali di akhir tugas.
 */
class TaskJournal(rootDir: File, val dir: File, private val label: String) {
    // Sandbox memberi path KANONIK. Di Android filesDir adalah symlink (/data/user/0/... -> /data/data/...), jadi root yang
    // tidak dikanonikkan membuat relativeTo() gagal dan jurnal diam-diam tidak mencatat apa pun.
    private val root: File = try { rootDir.canonicalFile } catch (e: IOException) { rootDir.absoluteFile }
    private val rows = LinkedHashMap<String, JSONObject>()
    private val createdDirs = LinkedHashSet<String>()
    private val createdAt = System.currentTimeMillis()

    val id: String get() = dir.name

    companion object {
        /**
         * Membuka kembali catatan tugas yang terputus (manifest ada tetapi finish() tak pernah dipanggil karena proses dimatikan),
         * supaya perubahannya tetap bisa dirangkum, dilihat, dan dibatalkan. null bila tak ada catatan atau sudah selesai.
         */
        fun resume(rootDir: File, dir: File): TaskJournal? {
            val m = try {
                JSONObject(File(dir, "manifest.json").readText())
            } catch (e: Exception) {
                return null
            }
            if (m.optBoolean("done", false)) return null
            val j = TaskJournal(rootDir, dir, m.optString("label"))
            val files = m.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val o = files.optJSONObject(i) ?: continue
                j.rows[o.optString("p")] = o
            }
            val created = m.optJSONArray("createdDirs") ?: JSONArray()
            for (i in 0 until created.length()) j.createdDirs.add(created.optString(i))
            return j
        }
    }

    @Synchronized
    fun beforeChange(f: File) {
        val rel = relOf(root, f) ?: return
        if (rows.containsKey(rel)) return
        val existed = f.isFile
        var saved = false
        if (existed) {
            saved = copyLimited(f, File(File(dir, "before"), rel))
        } else {
            // folder induk yang BELUM ada dibuat oleh agen: catat agar pembatalan bisa merapikannya
            var p = f.parentFile
            while (p != null && p != root && !p.exists()) {
                val r = relOf(root, p) ?: break
                createdDirs.add(r)
                p = p.parentFile
            }
        }
        rows[rel] = JSONObject().put("p", rel).put("existed", existed).put("saved", saved)
        writeManifest(false)   // sementara: bertahan bila proses dimatikan di tengah tugas
    }

    private fun writeManifest(done: Boolean) {
        val files = JSONArray()
        for (row in rows.values) files.put(row)
        val m = JSONObject().put("v", 1).put("label", label).put("created", createdAt)
            .put("done", done).put("undone", false)
            .put("createdDirs", JSONArray(createdDirs.toList())).put("files", files)
        writeJson(dir, "manifest.json", m)
    }

    /** Menyalin isi akhir, menghitung perubahan bersih, dan menulis manifest. Bila tak ada perubahan bersih, catatan dihapus. */
    @Synchronized
    fun finish(): List<FileChange> {
        val changes = ArrayList<FileChange>()
        val iter = rows.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val rel = entry.key
            val row = entry.value
            val existed = row.getBoolean("existed")
            val saved = row.getBoolean("saved")
            val cur = File(root, rel)
            val exists = cur.isFile
            val before = File(File(dir, "before"), rel)
            val kind: ChangeKind? = when {
                !existed && exists -> ChangeKind.ADDED
                existed && !exists -> ChangeKind.DELETED
                existed && exists && !(saved && sameBytes(before, cur)) -> ChangeKind.MODIFIED
                else -> null
            }
            if (kind == null) {
                iter.remove()   // diubah lalu dikembalikan / dibuat lalu dihapus: tak ada perubahan bersih
                continue
            }
            val afterSaved = exists && copyLimited(cur, File(File(dir, "after"), rel))
            val a = if (existed && saved) textLines(before) else null
            val b = if (exists) textLines(cur) else null
            val change = when (kind) {
                ChangeKind.ADDED ->
                    if (b != null) FileChange(rel, kind, b.size, 0) else FileChange(rel, kind, 0, 0, binary = true)
                ChangeKind.DELETED ->
                    if (a != null) FileChange(rel, kind, 0, a.size) else FileChange(rel, kind, 0, 0, binary = true)
                ChangeKind.MODIFIED ->
                    if (a != null && b != null) {
                        val c = LineDiff.counts(a, b)
                        FileChange(rel, kind, c.first, c.second)
                    } else {
                        FileChange(rel, kind, 0, 0, binary = true)
                    }
            }
            row.put("k", kind.name).put("a", change.added).put("r", change.removed).put("bin", change.binary).put("after", afterSaved)
            changes.add(change)
        }
        if (changes.isEmpty()) {
            dir.deleteRecursively()
            return changes
        }
        writeManifest(true)
        return changes
    }
}

/** Titik pulih yang sudah selesai: daftar perubahan, diff per file, dan pembatalan. */
class Checkpoint(private val root: File, val dir: File) {
    private fun load(): JSONObject? = try {
        val f = File(dir, "manifest.json")
        if (f.isFile) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    val undone: Boolean get() = load()?.optBoolean("undone", false) ?: false

    private fun rows(m: JSONObject): List<JSONObject> {
        val files = m.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            if (o.optString("k", "").isNotEmpty()) out.add(o)
        }
        return out
    }

    private fun kindOf(o: JSONObject): ChangeKind? = ChangeKind.values().firstOrNull { it.name == o.optString("k", "") }

    fun changes(): List<FileChange> {
        val m = load() ?: return emptyList()
        val out = ArrayList<FileChange>()
        for (o in rows(m)) {
            val k = kindOf(o) ?: continue
            out.add(FileChange(o.optString("p"), k, o.optInt("a"), o.optInt("r"), o.optBoolean("bin")))
        }
        return out
    }

    /** Diff (before -> after) satu file, atau pesan bila tak tersedia (biner / terlalu besar / tak dicadangkan). */
    fun diff(path: String, maxLines: Int = 400): String {
        val m = load() ?: return "(catatan titik pulih tidak ditemukan)"
        val row = rows(m).firstOrNull { it.optString("p") == path } ?: return "(tidak ada catatan untuk file ini)"
        val kind = kindOf(row) ?: return "(catatan rusak)"
        val existed = row.optBoolean("existed")
        val a: List<String>? = when {
            !existed -> emptyList()
            row.optBoolean("saved") -> textLines(File(File(dir, "before"), path))
            else -> null
        }
        val b: List<String>? = when {
            kind == ChangeKind.DELETED -> emptyList()
            row.optBoolean("after") -> textLines(File(File(dir, "after"), path))
            else -> null
        }
        if (a == null || b == null) return "(biner, terlalu besar, atau tidak dicadangkan: tampilan baris tidak tersedia)"
        return LineDiff.unified(a, b, maxLines = maxLines).ifEmpty { "(tidak ada perbedaan baris)" }
    }

    /**
     * Mengembalikan setiap file ke keadaan sebelum tugas. File yang sudah diubah lagi sesudah tugas itu (isinya tak sama dengan
     * isi akhir tercatat) DILEWATI agar pekerjaan sesudahnya tidak hilang. Hanya bisa dilakukan sekali.
     */
    fun undo(): UndoReport {
        val m = load() ?: return UndoReport(emptyList(), listOf("" to "catatan titik pulih tidak ditemukan"))
        if (m.optBoolean("undone", false)) return UndoReport(emptyList(), listOf("" to "tugas ini sudah dibatalkan"))
        val restored = ArrayList<String>()
        val skipped = ArrayList<Pair<String, String>>()
        val rootPath = try { root.canonicalPath } catch (e: IOException) { root.path }
        for (o in rows(m)) {
            val rel = o.optString("p")
            val kind = kindOf(o) ?: continue
            val cur = File(root, rel)
            val before = File(File(dir, "before"), rel)
            val after = File(File(dir, "after"), rel)
            val saved = o.optBoolean("saved")
            val afterSaved = o.optBoolean("after")
            try {
                if (!cur.canonicalPath.startsWith(rootPath + File.separator)) {
                    skipped.add(rel to "di luar folder proyek")
                    continue
                }
                when (kind) {
                    ChangeKind.ADDED -> when {
                        !cur.isFile -> restored.add(rel)   // sudah tidak ada
                        !afterSaved || !sameBytes(after, cur) -> skipped.add(rel to "diubah lagi sesudah tugas ini")
                        cur.delete() -> restored.add(rel)
                        else -> skipped.add(rel to "gagal dihapus")
                    }
                    ChangeKind.MODIFIED -> when {
                        !saved -> skipped.add(rel to "isi asli terlalu besar untuk dicadangkan")
                        !cur.isFile || !afterSaved || !sameBytes(after, cur) -> skipped.add(rel to "diubah lagi sesudah tugas ini")
                        else -> {
                            before.copyTo(cur, overwrite = true)
                            restored.add(rel)
                        }
                    }
                    ChangeKind.DELETED -> when {
                        cur.exists() -> skipped.add(rel to "dibuat ulang sesudah tugas ini")
                        !saved -> skipped.add(rel to "isi asli terlalu besar untuk dicadangkan")
                        else -> {
                            cur.parentFile?.mkdirs()
                            before.copyTo(cur, overwrite = false)
                            restored.add(rel)
                        }
                    }
                }
            } catch (e: IOException) {
                skipped.add(rel to "gagal: ${e.message}")
            }
        }
        // folder yang dulu dibuat agen dan kini kosong dirapikan (terdalam lebih dulu)
        val dirs = m.optJSONArray("createdDirs") ?: JSONArray()
        val names = ArrayList<String>()
        for (i in 0 until dirs.length()) names.add(dirs.optString(i))
        for (d in names.sortedByDescending { it.length }) {
            val folder = File(root, d)
            if (folder.isDirectory && folder.list()?.isEmpty() == true) folder.delete()
        }
        m.put("undone", true)
        writeJson(dir, "manifest.json", m)
        return UndoReport(restored, skipped)
    }
}

class CheckpointStore(private val base: File) {
    private fun projectDir(project: String) = File(base, project.replace('/', '_').replace('\\', '_'))

    fun begin(project: String, root: File, label: String): TaskJournal {
        val pdir = projectDir(project)
        val stamp = String.format(Locale.US, "%013d", System.currentTimeMillis())
        var id = stamp
        var n = 0
        while (File(pdir, id).exists()) {
            n++
            id = "$stamp-$n"
        }
        prune(pdir)
        val dir = File(pdir, id)
        dir.mkdirs()
        return TaskJournal(root, dir, label)
    }

    fun open(project: String, root: File, id: String): Checkpoint = Checkpoint(root, File(projectDir(project), id))

    /** Menyelesaikan titik pulih yang tugasnya terputus; mengembalikan (id, perubahan) untuk yang punya perubahan bersih. */
    fun recoverInterrupted(project: String, root: File): List<Pair<String, List<FileChange>>> {
        val dirs = projectDir(project).listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Pair<String, List<FileChange>>>()
        for (d in dirs) {
            val j = TaskJournal.resume(root, d) ?: continue
            val changes = j.finish()
            if (changes.isNotEmpty()) out.add(d.name to changes)
        }
        return out
    }

    fun deleteProject(project: String) {
        projectDir(project).deleteRecursively()
    }

    /** Menyisakan MAX_KEEP-1 titik pulih terbaru sebelum yang baru dibuat. */
    private fun prune(pdir: File) {
        val dirs = pdir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        if (dirs.size >= MAX_KEEP) dirs.take(dirs.size - MAX_KEEP + 1).forEach { it.deleteRecursively() }
    }

    companion object {
        const val MAX_KEEP = 15
    }
}
