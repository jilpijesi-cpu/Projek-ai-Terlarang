package com.mythos.app.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

private const val MAX_LINES = 400
private const val MAX_TEXT_FILE_BYTES = 2_000_000L

// ---------- util ----------

internal fun clip(s: String, max: Int, headRatio: Double = 0.6): String {
    if (s.length <= max) return s
    val head = (max * headRatio).toInt()
    val tail = max - head
    return s.substring(0, head) + "\n…[${s.length - max} karakter dipotong]…\n" + s.substring(s.length - tail)
}

internal fun oneLine(s: String, max: Int): String {
    val t = s.replace('\n', ' ').replace('\r', ' ')
    return if (t.length > max) t.take(max) + "…" else t
}

internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
internal fun JSONObject.reqStr(key: String): String = str(key) ?: throw ArgError("argumen '$key' wajib diisi")
internal fun JSONObject.boolOr(key: String, default: Boolean): Boolean =
    if (has(key) && !isNull(key)) optBoolean(key, default) else default
internal fun JSONObject.intOr(key: String, default: Int): Int =
    if (has(key) && !isNull(key)) optInt(key, default) else default

private val IGNORED = setOf(".git", ".gradle", ".idea", "build", "node_modules", "__pycache__", ".venv", "venv", ".dart_tool", ".next", "dist", "target")
internal fun isIgnored(name: String) = name in IGNORED

internal fun isBinary(f: File): Boolean = try {
    f.inputStream().use { s ->
        val buf = ByteArray(512)
        val n = s.read(buf)
        (0 until maxOf(n, 0)).any { buf[it].toInt() == 0 }
    }
} catch (e: Exception) { true }

internal fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

/** Seperti String.lines(), tetapi tanpa baris kosong palsu setelah newline penutup file (cat -n tidak menampilkannya). */
internal fun contentLines(text: String): List<String> {
    val l = text.lines()
    return if (l.size > 1 && l.last().isEmpty()) l.dropLast(1) else l
}

/** Path [f] relatif terhadap [base] (bukan terhadap root proyek); dipakai untuk mencocokkan pola Glob/Grep terhadap `path`. */
internal fun relTo(base: File, f: File): String = if (f == base) "." else f.relativeTo(base).path

internal fun renderPlan(plan: List<PlanStep>): String = plan.withIndex().joinToString("\n") { (i, s) ->
    val mark = when (s.status) {
        StepStatus.TODO -> "[ ]"
        StepStatus.DOING -> "[~]"
        StepStatus.DONE -> "[x]"
        StepStatus.BLOCKED -> "[!]"
    }
    "$mark ${i + 1}. ${s.title}"
}

internal fun renderTree(dir: File, depth: Int, maxEntries: Int = 300): String {
    val out = StringBuilder()
    var count = 0
    fun walk(d: File, level: Int) {
        val children = d.listFiles()?.filter { !isIgnored(it.name) }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (c in children) {
            if (count >= maxEntries) return
            count++
            out.append("  ".repeat(level)).append(c.name).append(if (c.isDirectory) "/" else "").append('\n')
            if (c.isDirectory && level + 1 < depth) walk(c, level + 1)
        }
    }
    walk(dir, 0)
    if (count >= maxEntries) out.append("… (dipotong di $maxEntries entri)\n")
    return if (out.isEmpty()) "(kosong)" else out.toString().trimEnd()
}

// ---------- sandbox & sesi ----------

class ProjectSandbox(root: File) {
    val root: File = root.canonicalFile

    /** Semua path dipetakan ke dalam folder proyek; symlink & ".." yang keluar ditolak. */
    fun resolve(rel: String): File {
        if (rel.isBlank()) throw ArgError("path kosong")
        var r = rel.trim().replace('\\', '/')
        val rootSlash = root.path.replace('\\', '/')
        if (r == rootSlash) {
            r = "."
        } else if (r.startsWith("$rootSlash/")) {
            r = r.removePrefix("$rootSlash/").trimStart('/')
        } else {
            // Agen kadang memakai NAMA folder proyek sebagai awalan ("proyek-2026.../app/x"): itu bukan bagian path, dan bila dibiarkan
            // semua file bersarang satu tingkat terlalu dalam sehingga build GitHub tak menemukannya. Hanya dibuang bila bukan subfolder sungguhan.
            val name = root.name
            if (!File(root, name).isDirectory) {
                val t = r.removePrefix("./")
                if (t == name) r = "." else if (t.startsWith("$name/")) r = t.removePrefix("$name/")
            }
        }
        if (r.isEmpty()) r = "."
        val f = File(root, r).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) {
            throw SecurityException("path di luar folder proyek: $rel")
        }
        return f
    }

    fun relative(f: File): String = if (f == root) "." else f.relativeTo(root).path
}

class SessionState {
    /** path relatif -> hash isi terakhir yang diketahui agen (dasar guard "baca sebelum ubah"). */
    val known = HashMap<String, Int>()
    var plan: List<PlanStep> = emptyList()
}

class ToolEnv(
    val sandbox: ProjectSandbox,
    val session: SessionState,
    val ask: suspend (String) -> String,
    val build: BuildService?,
    /** Mencatat isi asli sebuah file sebelum diubah (titik pulih per tugas); null = tidak mencatat. */
    val journal: TaskJournal? = null,
    /** Aksi ponsel (email, jam, kalender); null = tidak tersedia. */
    val device: DeviceActions? = null,
    /** Layanan OAuth 2.0 Google Workspace (Gmail, Calendar, Drive); null = belum dikonfigurasi. */
    val workspace: GoogleWorkspaceService? = null,
)

interface Tool {
    val name: String
    val doc: String
    val risk: Risk
    val effect: Effect
    val timeoutMs: Long get() = 60_000L
    fun describe(args: JSONObject): String = "$name ${oneLine(unescapeSlashes(args.toString()), 160)}"
    suspend fun run(args: JSONObject, env: ToolEnv): ToolResult
}

internal fun JSONObject.filePath(): String =
    str("file_path") ?: str("path") ?: throw ArgError("parameter 'file_path' is required")

/** Format `cat -n`: nomor baris rata kanan (lebar 6), TAB, lalu teks. */
internal fun numbered(lines: List<String>, from: Int, maxLine: Int = 400): String =
    lines.withIndex().joinToString("\n") { (i, l) -> (from + i).toString().padStart(6) + "\t" + l.take(maxLine) }

private const val NOT_READ = "File has not been read yet. Read it first before writing to it."
private const val CHANGED = "File has been modified since it was last read, either by the user or by another tool. Read it again before attempting to write it."

private fun guard(env: ToolEnv, rel: String, current: String): String? {
    val known = env.session.known[rel] ?: return NOT_READ
    if (known != current.hashCode()) return CHANGED
    return null
}

// ---------- alat ----------

class TodoWrite : Tool {
    override val name = "TodoWrite"
    override val risk = Risk.SAFE
    override val effect = Effect.META
    override val doc = """TodoWrite {"todos": [{"content": "Fix the login bug", "status": "pending|in_progress|completed", "activeForm": "Fixing the login bug"}]} — replace the whole todo list (1-30 items) for the current task. Exactly one item should be in_progress; mark items completed immediately when done. Use for tasks with 3+ steps; never for trivial or conversational requests."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val arr = args.optJSONArray("todos") ?: throw ArgError("'todos' must be an array")
        if (arr.length() == 0 || arr.length() > 30) throw ArgError("'todos' must have 1-30 items")
        val steps = (0 until arr.length()).map { i ->
            when (val o = arr.opt(i)) {
                is JSONObject -> PlanStep(o.reqStr("content").trim(), parseStatus(o.str("status")))
                is String -> PlanStep(o.trim(), StepStatus.TODO)
                else -> throw ArgError("todo #${i + 1} is not valid")
            }
        }
        if (steps.any { it.title.isEmpty() }) throw ArgError("todo content must not be empty")
        env.session.plan = steps
        return ToolResult(true, "Todos have been modified successfully. Keep using the todo list to track progress and continue with the current task.")
    }

    private fun parseStatus(s: String?): StepStatus = when (s?.lowercase()) {
        "in_progress", "doing" -> StepStatus.DOING
        "completed", "done" -> StepStatus.DONE
        "blocked" -> StepStatus.BLOCKED
        else -> StepStatus.TODO
    }
}

class LS : Tool {
    override val name = "LS"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """LS {"path": ".", "depth": 2} — list a directory as a tree (depth 1-4, default 2). Prefer Glob/Grep when you know what you are looking for."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val dir = env.sandbox.resolve(args.str("path") ?: ".")
        val rel = env.sandbox.relative(dir)
        if (!dir.exists()) return ToolResult(false, "Path does not exist: $rel")
        if (dir.isFile) return ToolResult(true, rel)
        val depth = args.intOr("depth", 2).coerceIn(1, 4)
        return ToolResult(true, "$rel/\n" + renderTree(dir, depth))
    }
}

class Read : Tool {
    override val name = "Read"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """Read {"file_path": "src/Main.kt", "offset": 1, "limit": 400} — read a text file; output is `line-number<TAB>text` (max 400 lines per call; use offset/limit for the rest). A file must be Read before you Edit or overwrite it."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val f = env.sandbox.resolve(args.filePath())
        val rel = env.sandbox.relative(f)
        if (!f.exists()) return ToolResult(false, "File does not exist: $rel. Use Glob or Grep to find the right path.")
        if (f.isDirectory) return ToolResult(false, "$rel is a directory, not a file. Use LS.")
        if (f.length() > MAX_TEXT_FILE_BYTES) return ToolResult(false, "File is too large (${f.length()} bytes). Use Grep, or Read with offset/limit on a smaller file.")
        if (isBinary(f)) return ToolResult(false, "$rel looks like a binary file and cannot be read as text.")
        val text = f.readText()
        env.session.known[rel] = text.hashCode()
        if (text.isEmpty()) return ToolResult(true, Protocol.reminder("Warning: the file exists but the contents are empty."), readPaths = listOf(rel))
        val lines = contentLines(text)
        val total = lines.size
        val offset = args.intOr("offset", 1).coerceAtLeast(1)
        if (offset > total) {
            return ToolResult(true, Protocol.reminder("Warning: the file exists but is shorter than the provided offset ($offset). The file has $total lines."), readPaths = listOf(rel))
        }
        val limit = args.intOr("limit", MAX_LINES).coerceIn(1, MAX_LINES)
        val end = minOf(total, offset + limit - 1)
        val body = numbered(lines.subList(offset - 1, end), offset)
        val more = if (end < total) "\n… (file has $total lines; continue with offset=${end + 1})" else ""
        return ToolResult(true, body + more, readPaths = listOf(rel))
    }
}

class Write : Tool {
    override val name = "Write"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE
    override val doc = """Write {"file_path": "src/App.kt", "content": "..."} — create a file (parent folders are created) or fully overwrite one you have already Read. For changes to existing files prefer Edit."""
    override fun describe(args: JSONObject) = "Write ${args.str("file_path") ?: args.str("path")} (${args.str("content")?.length ?: 0} karakter)"
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val f = env.sandbox.resolve(args.filePath())
        val rel = env.sandbox.relative(f)
        val content = args.str("content") ?: throw ArgError("parameter 'content' is required")
        if (content.length > 500_000) return ToolResult(false, "Content is too large (>500 KB). Split it into several files.")
        if (f.isDirectory) return ToolResult(false, "$rel is a directory.")
        val existed = f.exists()
        if (existed) {
            if (rel !in env.session.known) return ToolResult(false, NOT_READ)
            if (f.length() > MAX_TEXT_FILE_BYTES) return ToolResult(false, "Existing file is too large (${f.length()} bytes) to overwrite directly.")
            guard(env, rel, f.readText())?.let { return ToolResult(false, it) }
        }
        env.journal?.beforeChange(f)
        f.parentFile?.mkdirs()
        f.writeText(content)
        env.session.known[rel] = content.hashCode()
        val msg = if (existed) "The file $rel has been updated (${contentLines(content).size} lines)."
        else "File created successfully at: $rel (${contentLines(content).size} lines)"
        return ToolResult(true, msg, changed = listOf(rel))
    }
}

class Edit : Tool {
    override val name = "Edit"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE
    override val doc = """Edit {"file_path": "src/App.kt", "old_string": "exact text", "new_string": "replacement", "replace_all": false} — replace exact text in a file you have Read. old_string must match exactly (indentation included, WITHOUT the line-number prefixes) and be unique unless replace_all is true. To create a new file with Edit, use an empty old_string on a path that does not exist."""
    override fun describe(args: JSONObject) = "Edit ${args.str("file_path") ?: args.str("path")}"
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val f = env.sandbox.resolve(args.filePath())
        val rel = env.sandbox.relative(f)
        val old = args.str("old_string") ?: throw ArgError("parameter 'old_string' is required")
        val repl = args.str("new_string") ?: throw ArgError("parameter 'new_string' is required (empty string to delete)")
        val replaceAll = args.boolOr("replace_all", false)
        if (old == repl) return ToolResult(false, "No changes to make: old_string and new_string are exactly the same.")

        if (!f.exists()) {
            if (old.isNotEmpty()) return ToolResult(false, "File does not exist: $rel. To create a new file use Write (or Edit with an empty old_string).")
            if (repl.length > 500_000) return ToolResult(false, "Content is too large (>500 KB). Split it into several files.")
            env.journal?.beforeChange(f)
            f.parentFile?.mkdirs()
            f.writeText(repl)
            env.session.known[rel] = repl.hashCode()
            return ToolResult(true, "File created successfully at: $rel", changed = listOf(rel))
        }
        if (!f.isFile) return ToolResult(false, "$rel is a directory.")
        if (old.isEmpty()) return ToolResult(false, "old_string is empty but $rel already exists. Provide the text to replace, or use Write to overwrite.")
        if (rel !in env.session.known) return ToolResult(false, NOT_READ)
        if (f.length() > MAX_TEXT_FILE_BYTES) return ToolResult(false, "File is too large (${f.length()} bytes) to edit.")
        if (isBinary(f)) return ToolResult(false, "$rel looks like a binary file and cannot be edited as text.")
        val text = f.readText()
        guard(env, rel, text)?.let { return ToolResult(false, it) }

        var needle = old
        var replacement = if (text.contains("\r\n") && !repl.contains('\r') && repl.contains('\n')) repl.replace("\n", "\r\n") else repl
        var idxs = findAll(text, needle)
        // File CRLF: Read membuang "\r", jadi old_string yang disalin dari Read hanya berisi "\n". Coba lagi dengan CRLF.
        if (idxs.isEmpty() && text.contains("\r\n") && !old.contains('\r') && old.contains('\n')) {
            val crlfOld = old.replace("\n", "\r\n")
            val found = findAll(text, crlfOld)
            if (found.isNotEmpty()) {
                needle = crlfOld
                idxs = found
            }
        }
        if (idxs.isEmpty()) return ToolResult(false, "String to replace not found in file.\n" + missHint(text, old))
        if (idxs.size > 1 && !replaceAll) {
            return ToolResult(
                false,
                "Found ${idxs.size} matches of the string to replace (lines ${idxs.take(8).map { lineOf(text, it) }.joinToString()}), but replace_all is false. " +
                    "To replace all occurrences, set replace_all to true. To replace only one occurrence, provide more context to uniquely identify it."
            )
        }
        val newText = if (replaceAll) text.replace(needle, replacement)
        else text.substring(0, idxs[0]) + replacement + text.substring(idxs[0] + needle.length)
        env.journal?.beforeChange(f)
        f.writeText(newText)
        env.session.known[rel] = newText.hashCode()

        val line = lineOf(text, idxs[0])
        val newLines = contentLines(newText)
        val first = minOf((line - 3).coerceAtLeast(1), newLines.size)
        val last = maxOf(first, minOf(newLines.size, line + repl.count { it == '\n' } + 3))
        val snippet = numbered(newLines.subList(first - 1, last), first, 300)
        val count = if (replaceAll) idxs.size else 1
        return ToolResult(
            true,
            "The file $rel has been updated ($count replacement${if (count > 1) "s" else ""}). Snippet of the edited region:\n$snippet",
            changed = listOf(rel)
        )
    }

    private fun findAll(text: String, needle: String): ArrayList<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) break
            out.add(i)
            from = i + needle.length
        }
        return out
    }

    private fun missHint(text: String, old: String): String {
        val ws = Regex("\\s+")
        if (text.replace(ws, " ").contains(old.replace(ws, " ").trim())) {
            return "The same text exists if whitespace/indentation/newlines are ignored. Copy it EXACTLY from the Read output (without the line-number prefix)."
        }
        val first = old.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val hits = if (first == null) emptyList<Int>()
        else text.lines().withIndex().filter { it.value.contains(first) }.map { it.index + 1 }.take(5)
        return if (hits.isNotEmpty()) "The first line of old_string appears at line ${hits.joinToString()}, but the rest does not match. Read that area again."
        else "No part of old_string was found. The path may be wrong or the content changed; use Grep."
    }
}

class Delete : Tool {
    override val name = "Delete"
    override val risk = Risk.DANGEROUS
    override val effect = Effect.WRITE
    override val doc = """Delete {"file_path": "old/Unused.kt"} — delete ONE file (not folders). Needs user approval."""
    override fun describe(args: JSONObject) = "Delete ${args.str("file_path") ?: args.str("path")}"
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val f = env.sandbox.resolve(args.filePath())
        val rel = env.sandbox.relative(f)
        if (!f.exists()) return ToolResult(false, "Path does not exist: $rel")
        if (!f.isFile) return ToolResult(false, "$rel is not a file. Only single files can be deleted.")
        env.journal?.beforeChange(f)
        if (!f.delete()) return ToolResult(false, "Failed to delete $rel")
        env.session.known.remove(rel)
        return ToolResult(true, "Deleted $rel. Use Grep to check for remaining references to it.", readPaths = listOf(rel))
    }
}

internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    var i = 0
    var braces = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                if (i + 2 < glob.length && glob[i + 2] == '/') {
                    sb.append("(?:.*/)?")
                    i += 3
                } else {
                    sb.append(".*")
                    i += 2
                }
                continue
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c == '{' && glob.indexOf('}', i + 1) > i -> { sb.append("(?:"); braces++ }
            c == '}' && braces > 0 -> { sb.append(")"); braces-- }
            c == ',' && braces > 0 -> sb.append("|")
            c in "\\.[]()+^\$|{}" -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    while (braces-- > 0) sb.append(")")
    return Regex("^$sb$")
}

private fun globMatches(re: Regex, rel: String, hasSlash: Boolean): Boolean =
    re.matches(if (hasSlash) rel else rel.substringAfterLast('/'))

class Glob : Tool {
    override val name = "Glob"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """Glob {"pattern": "**/*.kt", "path": "."} — find files by name pattern (supports **, *, ?, {a,b}); returns paths sorted by most recently modified (max 100). A pattern without '/' matches file names at any depth; a pattern with '/' is matched relative to `path`."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val pattern = args.reqStr("pattern").removePrefix("./")
        val target = env.sandbox.resolve(args.str("path") ?: ".")
        if (!target.exists()) return ToolResult(false, "Path does not exist: ${args.str("path")}")
        val re = globToRegex(pattern)
        val hasSlash = pattern.contains('/')
        val hits = ArrayList<File>()
        for (f in target.walkTopDown().onEnter { it == target || !isIgnored(it.name) }) {
            currentCoroutineContext().ensureActive()
            if (!f.isFile) continue
            val relT = if (target.isFile) f.name else relTo(target, f).replace(File.separatorChar, '/')
            if (globMatches(re, relT, hasSlash)) hits.add(f)
        }
        hits.sortByDescending { it.lastModified() }
        if (hits.isEmpty()) return ToolResult(true, "No files found")
        val shown = hits.take(100).joinToString("\n") { env.sandbox.relative(it) }
        val note = if (hits.size > 100) "\n(Results truncated: ${hits.size} files match; narrow the pattern or path.)" else ""
        return ToolResult(true, shown + note)
    }
}

/** Regex dari model bisa berbacktracking katastrofik; Matcher tak bisa dibatalkan, jadi akses karakter dipantau terhadap tenggat. */
internal class RegexTimeout : RuntimeException("regex terlalu lambat")

internal class DeadlineChars(private val s: CharSequence, private val deadlineNanos: Long) : CharSequence {
    private var n = 0
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if ((++n and 0x3FFF) == 0 && System.nanoTime() > deadlineNanos) throw RegexTimeout()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineChars(s.subSequence(startIndex, endIndex), deadlineNanos)
    override fun toString(): String = s.toString()
}

class Grep : Tool {
    override val name = "Grep"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """Grep {"pattern": "fun main\\(", "path": ".", "glob": "*.kt", "output_mode": "files_with_matches|content|count", "-i": false, "-n": true, "-C": 0, "-A": 0, "-B": 0, "head_limit": 250} — regex search in file contents (Java regex syntax; escape special characters). `glob` with '/' is matched relative to `path`. Default output_mode is files_with_matches; use content to see matching lines (path:line:text)."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val pattern = args.reqStr("pattern")
        val target = env.sandbox.resolve(args.str("path") ?: ".")
        if (!target.exists()) return ToolResult(false, "Path does not exist: ${args.str("path")}")
        val re = try {
            Regex(pattern, if (args.boolOr("-i", args.boolOr("ignore_case", false))) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            return ToolResult(false, "Invalid regex: ${e.message?.lineSequence()?.firstOrNull()}. Escape special characters such as ( ) [ ] { } . * + ? with a backslash.")
        }
        val mode = args.str("output_mode") ?: "files_with_matches"
        if (mode !in setOf("files_with_matches", "content", "count")) throw ArgError("output_mode must be files_with_matches, content or count")
        val showNum = args.boolOr("-n", true)
        val ctxC = args.intOr("-C", 0).coerceIn(0, 10)
        val before = maxOf(ctxC, args.intOr("-B", 0).coerceIn(0, 10))
        val after = maxOf(ctxC, args.intOr("-A", 0).coerceIn(0, 10))
        val limit = args.intOr("head_limit", 250).coerceIn(1, 1000)
        val fileGlob = args.str("glob")?.let {
            val clean = it.removePrefix("./")
            globToRegex(clean) to clean.contains('/')
        }

        val files: Sequence<File> = if (target.isFile) sequenceOf(target)
        else target.walkTopDown().onEnter { it == target || !isIgnored(it.name) }.filter { it.isFile }
        val out = ArrayList<String>()
        val deadline = System.nanoTime() + 15_000_000_000L
        var scanned = 0
        var truncated = false
        outer@ for (f in files) {
            currentCoroutineContext().ensureActive()
            if (++scanned > 4000) break
            val rel = env.sandbox.relative(f)
            val relT = if (target.isFile) f.name else relTo(target, f).replace(File.separatorChar, '/')
            if (fileGlob != null && !globMatches(fileGlob.first, relT, fileGlob.second)) continue
            if (f.length() > 1_000_000 || isBinary(f)) continue
            val lines = try { f.readLines() } catch (e: Exception) { continue }
            val hit = try {
                lines.indices.filter { re.containsMatchIn(DeadlineChars(lines[it], deadline)) }
            } catch (e: RegexTimeout) {
                return ToolResult(false, "Grep dihentikan: regex terlalu lambat (backtracking). Sederhanakan pola, hindari kuantifier bersarang seperti (a+)+.")
            }
            if (hit.isEmpty()) continue
            when (mode) {
                "files_with_matches" -> out.add(rel)
                "count" -> out.add("$rel:${hit.size}")
                else -> {
                    val shown = sortedSetOf<Int>()
                    for (h in hit) for (k in (h - before).coerceAtLeast(0)..(h + after).coerceAtMost(lines.size - 1)) shown.add(k)
                    var prev = -2
                    for (k in shown) {
                        if ((before > 0 || after > 0) && prev >= 0 && k != prev + 1) out.add("--")
                        val sep = if (k in hit) ":" else "-"
                        val num = if (showNum) "${k + 1}$sep" else ""
                        out.add("$rel$sep$num${lines[k].trimEnd().take(300)}")
                        prev = k
                        if (out.size >= limit) { truncated = true; break@outer }
                    }
                }
            }
            if (out.size >= limit) { truncated = true; break }
        }
        if (out.isEmpty()) return ToolResult(true, "No matches found")
        return ToolResult(true, out.joinToString("\n") + if (truncated) "\n(Results limited to $limit lines; narrow the pattern, path or glob.)" else "")
    }
}

class AskUserQuestion : Tool {
    override val name = "AskUserQuestion"
    override val risk = Risk.SAFE
    override val effect = Effect.META
    override val timeoutMs = 24 * 60 * 60_000L
    override val doc = """AskUserQuestion {"question": "...", "options": ["A", "B"]} — ask the user ONE specific question when a missing decision would change the result and cannot be inferred. Use sparingly; options are optional."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val q = args.reqStr("question")
        val opts = args.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val text = if (opts.isEmpty()) q else q + "\n" + opts.withIndex().joinToString("\n") { (i, o) -> "${i + 1}. $o" }
        val answer = env.ask(text)
        return ToolResult(true, "User has answered your question: $answer")
    }
}

/** Persetujuan rencana ditangani Agent (ia yang mengganti mode izin); run() hanya cadangan. */
class ExitPlanMode : Tool {
    override val name = "ExitPlanMode"
    override val risk = Risk.SAFE
    override val effect = Effect.META
    override val timeoutMs = 24 * 60 * 60_000L
    override val doc = """ExitPlanMode {"plan": "markdown plan"} — plan mode only: present your concrete plan for approval. After approval you may modify files."""
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult = ToolResult(true, args.reqStr("plan"))
}

class GithubBuild : Tool {
    override val name = "GithubBuild"
    override val risk = Risk.WRITE
    override val effect = Effect.EXEC
    override val timeoutMs = 30 * 60_000L
    override val doc = """GithubBuild {"timeout_s": 600} — push the whole project to the user's GitHub repo and run its GitHub Actions workflow (.github/workflows/*.yml on push); waits for the result and returns excerpts of failing logs. This is the ONLY way to build/test (there is no local shell). A fully green run counts as verification. Slow (minutes): make several edits first, then build once."""
    override fun describe(args: JSONObject) = "GithubBuild (push + GitHub Actions)"
    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val svc = env.build ?: return ToolResult(false, "GithubBuild is not available: GitHub token/repo are not configured in Settings.")
        val timeout = args.intOr("timeout_s", 600).coerceIn(60, 1500)
        val out = svc.build(env.sandbox.root, timeout)
        return ToolResult(out.ok, clip(out.text, 6_000, headRatio = 0.4), verifies = out.ok)
    }
}

class ToolRegistry(val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    private val byLower = tools.associateBy { it.name.lowercase() }
    val names: List<String> get() = tools.map { it.name }
    fun get(name: String): Tool? = byName[name] ?: byLower[name.trim().lowercase()]
    fun docs(): String = tools.joinToString("\n") { "- " + it.doc }

    companion object {
        fun default(
            withBuild: Boolean,
            withPlan: Boolean = false,
            withWeb: Boolean = true,
            subAgentLlm: LlmClient? = null,
            searchKeys: SearchKeys = SearchKeys(),
            withDevice: Boolean = false,
            withWorkspace: Boolean = false,
            withGmail: Boolean = withWorkspace,
            withCalendar: Boolean = withWorkspace,
            withDrive: Boolean = withWorkspace,
        ): ToolRegistry {
            val l = mutableListOf<Tool>(TodoWrite(), LS(), Glob(), Grep(), Read(), Edit(), Write(), Delete(), AskUserQuestion(), ImagesToPdf(), MakePdf(), EditImage(), PdfEdit(), ViewImages())
            if (withPlan) l.add(ExitPlanMode())
            if (withWeb) {
                l.add(WebSearch(searchKeys))
                l.add(WebFetch())
            }
            if (withDevice) {
                l.add(ComposeEmail())
                l.add(SetReminder())
                l.add(SetDeadlineNotice())
                l.add(CaseLedger())
            }
            if (withGmail) {
                l.add(GmailSearchRead())
                l.add(GmailDraftSend())
            }
            if (withCalendar) {
                l.add(GoogleCalendarSync())
            }
            if (withDrive) {
                l.add(GoogleDriveCaseVault())
            }
            if (withBuild) {
                l.add(GithubBuild())
                l.add(GithubImportRepo())
                l.add(GithubCreatePR())
                l.add(GithubIssues())
                l.add(GithubDownloadArtifact())
            }
            if (subAgentLlm != null) l.add(TaskTool(subAgentLlm, searchKeys, withWeb))
            return ToolRegistry(l)
        }
    }
}
