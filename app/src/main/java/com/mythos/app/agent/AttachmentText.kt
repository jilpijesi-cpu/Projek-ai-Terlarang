package com.mythos.app.agent

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Bagian lampiran yang murni JVM (bisa diuji tanpa Android): nama aman, deteksi teks, ekstraksi Office, unzip aman. */
object AttachmentText {

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.')
        return cleaned.ifEmpty { "lampiran" }.take(80)
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Teks bila tidak ada byte NUL pada 4 KB pertama dan dapat didekode UTF-8 dengan sedikit karakter pengganti. */
    fun isProbablyText(bytes: ByteArray): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, 4096))
        if (head.any { it == 0.toByte() }) return false
        val s = String(head, Charsets.UTF_8)
        return s.count { it == '\uFFFD' } <= head.size / 50
    }

    private val XML_ENTITY = Regex("&(?:#(x|X)?([0-9a-fA-F]+)|([a-zA-Z]+));")

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return XML_ENTITY.replace(s) { m ->
            val num = m.groupValues[2]
            if (num.isNotEmpty()) {
                val cp = num.toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
                if (cp != null && cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else m.value
            } else {
                when (m.groupValues[3]) {
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "amp" -> "&"
                    else -> m.value
                }
            }
        }
    }

    private fun zipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !wanted(e.name)) continue
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = z.read(tmp)
                    if (n < 0) break
                    total += n
                    if (total > 20_000_000) throw IOException("Isi dokumen terlalu besar")
                    buf.write(tmp, 0, n)
                }
                out[e.name] = buf.toString("UTF-8")
            }
        }
        return out
    }

    /** Ekstrak teks dari .docx / .pptx / .xlsx; null bila ekstensi lain atau arsip rusak. */
    fun extractOfficeText(bytes: ByteArray, ext: String): String? {
        return try {
        when (ext) {
            "docx" -> {
                val xml = zipEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return null
                unescape(
                    xml.replace(Regex("</w:p>\\s*</w:tc>"), "\t")   // akhir sel tabel -> TAB, supaya kolom tidak menempel
                        .replace(Regex("</w:tr>"), "\n")
                        .replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<w:br[^>]*/>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                ).lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
            }
            "pptx" -> {
                val slides = zipEntries(bytes) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                    .entries.sortedBy { Regex("(\\d+)").find(it.key.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
                slides.mapIndexed { i, (_, xml) ->
                    val paras = Regex("<a:p\\b[^>]*>(.*?)</a:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { p ->
                        Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(p.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
                    }.filter { it.isNotEmpty() }.toList()
                    val t = if (paras.isNotEmpty()) paras.joinToString("\n")
                    else Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(xml).joinToString("\n") { unescape(it.groupValues[1]) }
                    "--- Slide ${i + 1} ---\n$t"
                }.joinToString("\n\n").trim()
            }
            "xlsx" -> {
                val files = zipEntries(bytes) { it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
                val shared = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(files["xl/sharedStrings.xml"].orEmpty())
                    .map { si -> unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] }) }
                    .toList()
                // urutan alami: sheet2 sebelum sheet10 (urutan abjad menaruh sheet10 lebih dulu)
                files.filterKeys { it.startsWith("xl/worksheets/") }.entries
                    .sortedBy { Regex("(\\d+)").find(it.key.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
                    .joinToString("\n\n") { (name, xml) ->
                    // <row .../> yang self-closing tidak boleh "menelan" baris sesudahnya
                    val rows = Regex("<row\\b[^>]*?(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL).findAll(xml).joinToString("\n") { r ->
                        Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(r.groupValues[1]).joinToString("\t") { c ->
                            val attrs = c.groupValues[1]
                            val inner = c.groupValues[2]
                            if (attrs.contains("t=\"inlineStr\"")) {
                                unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(inner).joinToString("") { it.groupValues[1] })
                            } else {
                                val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1).orEmpty()
                                if (attrs.contains("t=\"s\"")) shared.getOrElse(v.toIntOrNull() ?: -1) { "" } else unescape(v)
                            }
                        }
                    }
                    "--- ${name.substringAfterLast('/').removeSuffix(".xml")} ---\n$rows"
                }.trim()
            }
            else -> null
        }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    class UnzipResult(val files: Int, val bytes: Long)

    /** Ekstrak zip ke [dest] dengan perlindungan zip-slip, batas jumlah file dan total byte. */
    fun unzipSafely(zip: File, dest: File, maxFiles: Int = 500, maxBytes: Long = 50_000_000L): UnzipResult {
        dest.mkdirs()
        val root = dest.canonicalFile
        var files = 0
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val target = File(root, e.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) && target != root) throw IOException("Zip tidak aman: ${e.name}")
                if (e.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (++files > maxFiles) throw IOException("Zip berisi lebih dari $maxFiles file")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Isi zip melebihi ${maxBytes / 1_000_000} MB")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        return UnzipResult(files, total)
    }
}
