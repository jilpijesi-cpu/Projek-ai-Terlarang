package com.mythos.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import com.mythos.app.agent.AttachmentText
import com.mythos.app.agent.renderTree
import com.mythos.app.agent.ImagePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

enum class AttachKind { IMAGE, DOC }
enum class AttachSource { CAMERA, PHOTO, FILE }

/** path = berkas di penyimpanan sementara (sebelum dikirim) atau di proyek uploads/ (setelah dikirim). */
data class Attachment(val name: String, val kind: AttachKind, val path: String, val mime: String, val size: Long)

class Committed(val attachment: Attachment, val note: String, val images: List<ImagePart>)

/** Menyiapkan lampiran: salin dari Uri → (gambar diperkecil & diputar sesuai EXIF) → pindah ke proyek, ekstrak teks/zip/PDF. */
object Attachments {
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MAX_ITEMS = 20
    private const val MAX_SIDE = 1600
    private const val MAX_IMAGES_PER_MESSAGE = 8

    // ---------------------------------------------------------------- tahap 1: salin ke penyimpanan sementara

    fun stage(ctx: Context, uri: Uri): Attachment {
        val cr = ctx.contentResolver
        var name = "lampiran"
        var declared = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) declared = c.getLong(1)
            }
        }
        if (name == "lampiran") uri.lastPathSegment?.let { name = it }
        name = AttachmentText.safeName(name)
        if (declared > MAX_BYTES) throw IOException("File terlalu besar (maks ${MAX_BYTES / 1_048_576} MB): $name")
        val mime = cr.getType(uri) ?: guessMime(name)
        val dir = File(ctx.cacheDir, "staged").apply { mkdirs() }
        // SVG adalah teks XML (BitmapFactory tidak bisa membacanya): perlakukan sebagai dokumen, bukan gambar.
        val isImage = mime.startsWith("image/") && mime != "image/svg+xml" && AttachmentText.extension(name) != "svg"
        val outName = if (isImage) name.substringBeforeLast('.', name) + ".jpg" else name
        val out = File(dir, "${System.currentTimeMillis()}_$outName")
        if (isImage) {
            writeScaledJpeg(ctx, uri, out)
        } else {
            cr.openInputStream(uri)?.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            out.delete()
                            throw IOException("File terlalu besar (maks ${MAX_BYTES / 1_048_576} MB): $name")
                        }
                        o.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("File tidak bisa dibuka: $name")
        }
        return Attachment(outName, if (isImage) AttachKind.IMAGE else AttachKind.DOC, out.absolutePath, if (isImage) "image/jpeg" else mime, out.length())
    }

    private fun guessMime(name: String) = when (AttachmentText.extension(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun writeScaledJpeg(ctx: Context, uri: Uri, out: File) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Gambar tidak bisa dibaca")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Gambar tidak bisa dibaca")
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val longest = maxOf(bmp.width, bmp.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        val needsMatrix = orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED || scale != 1f
        if (needsMatrix) {
            val m = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                }
                if (scale != 1f) postScale(scale, scale)
            }
            val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (transformed !== bmp) {
                bmp.recycle()
                bmp = transformed
            }
        }
        // JPEG tidak punya kanal alfa: tanpa langkah ini area transparan PNG/WebP (ikon, tangkapan layar) menjadi HITAM.
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(flat)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            bmp.recycle()
            bmp = flat
        }
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun readHead(f: File, max: Int = 8192): ByteArray = try {
        f.inputStream().use { s ->
            val buf = ByteArray(minOf(f.length().coerceAtLeast(0L), max.toLong()).toInt())
            var off = 0
            while (off < buf.size) {
                val n = s.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        }
    } catch (e: IOException) {
        ByteArray(0)
    }

    // ---------------------------------------------------------------- tahap 2: masukkan ke proyek saat dikirim

    fun commit(a: Attachment, projectDir: File): Committed {
        val up = File(projectDir, "uploads").apply { mkdirs() }
        val target = uniqueFile(up, a.name)
        val src = File(a.path)
        src.copyTo(target, overwrite = true)
        val att = a.copy(path = target.absolutePath)
        val rel = "uploads/${target.name}"
        val ext = AttachmentText.extension(target.name)

        val result = if (a.kind == AttachKind.IMAGE) {
            val b64 = Base64.encodeToString(target.readBytes(), Base64.NO_WRAP)
            Committed(att, "- $rel (gambar JPEG ${a.size / 1024} KB; terlihat langsung pada pesan ini)", listOf(ImagePart("image/jpeg", b64)))
        } else {
            when {
                ext == "zip" -> {
                    val dest = uniqueFile(up, target.name.removeSuffix(".zip").ifEmpty { "arsip" })
                    val note = try {
                        val r = AttachmentText.unzipSafely(target, dest)
                        "- $rel (zip) → diekstrak ke uploads/${dest.name}/ (${r.files} file; periksa isinya dengan Glob/Read di path `uploads/${dest.name}`). Isi (5 tingkat teratas):\n" +
                            renderTree(dest, 5, 120).prependIndent("    ")
                    } catch (e: IOException) {
                        dest.deleteRecursively()
                        "- $rel (zip) → TIDAK bisa diekstrak: ${e.message}"
                    }
                    Committed(att, note, emptyList())
                }
                ext in setOf("docx", "pptx", "xlsx") -> {
                    val text = AttachmentText.extractOfficeText(target.readBytes(), ext)
                    if (text != null) {
                        val txt = uniqueFile(up, target.name + ".txt")
                        txt.writeText(text)
                        Committed(att, "- $rel (dokumen Office) → teks diekstrak ke uploads/${txt.name} (${text.length} karakter)", emptyList())
                    } else {
                        Committed(att, "- $rel (dokumen Office rusak; isi tidak bisa dibaca)", emptyList())
                    }
                }
                ext == "pdf" -> {
                    val pages = renderPdf(target)
                    if (pages.isNotEmpty()) {
                        Committed(att, "- $rel (PDF) → ${pages.size} halaman pertama terlihat sebagai gambar pada pesan ini; teksnya tidak diekstrak; dibaca secara visual, jadi teks kecil bisa salah baca dan harus ditandai tidak pasti; halaman lain atau lihat ulang: ViewImages {\"images\": [\"$rel\"], \"pages\": [7, 8]}", pages)
                    } else {
                        Committed(att, "- $rel (PDF) → tidak bisa dirender; isi tidak tersedia", emptyList())
                    }
                }
                AttachmentText.isProbablyText(readHead(target, 8192)) ->
                    Committed(att, "- $rel (teks, ${a.size} byte; baca dengan Read)", emptyList())
                else -> Committed(att, "- $rel (berkas biner ${a.size} byte; isinya tidak bisa dibaca sebagai teks)", emptyList())
            }
        }
        src.delete()
        return result
    }

    private fun renderPdf(file: File, maxPages: Int = 6): List<ImagePart> {
        val out = ArrayList<ImagePart>()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    for (i in 0 until minOf(r.pageCount, maxPages)) {
                        r.openPage(i).use { p ->
                            val w = 1600
                            val h = (w.toFloat() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(1, 4800)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val bos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                                out.add(ImagePart("image/jpeg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (f.exists()) f = File(dir, "$base-${i++}$ext")
        return f
    }

    /** Blok teks yang ditambahkan ke tugas agar agen tahu apa saja lampirannya. */
    fun noteBlock(items: List<Committed>): String =
        if (items.isEmpty()) "" else "\n\nLAMPIRAN PENGGUNA (sudah disalin ke proyek):\n" + items.joinToString("\n") { it.note }

    /**
     * Daftar berkas di uploads/ dari pesan-pesan SEBELUMNYA, dibaca dari disk (bukan dari riwayat teks),
     * supaya agen baru di tiap giliran tetap tahu lampiran lama. Dipanggil sebelum lampiran giliran ini disalin.
     */
    fun manifest(projectDir: File, maxEntries: Int = 40): String {
        val all = File(projectDir, "uploads").listFiles()?.sortedBy { it.lastModified() } ?: return ""
        if (all.isEmpty()) return ""
        val lines = all.takeLast(maxEntries).map { f ->
            if (f.isDirectory) "- uploads/${f.name}/ (hasil ekstrak zip, ${f.walkTopDown().count { it.isFile }} file)"
            else "- uploads/${f.name} (${f.length()} byte)"
        }
        val notes = File(File(projectDir, "uploads"), "_catatan.md").takeIf { it.isFile && it.length() < 100_000 }?.readText()?.trim().orEmpty()
        val notesBlock = if (notes.isEmpty()) "" else "\n\nCATATAN FOTO (uploads/_catatan.md, ditulis agen sebelumnya):\n" + notes.take(6_000)
        return "\n\nLAMPIRAN DARI PESAN SEBELUMNYA (masih ada di proyek; baca dengan Read/Glob, jangan minta pengguna mengirim ulang." +
            " Gambar/PDF tidak tampil lagi di percakapan, lihat ulang dengan ViewImages (PDF: pilih halaman dengan 'pages'); teks Office ada di <nama>.txt):\n" + lines.joinToString("\n") + notesBlock
    }

    /** Ada gambar di uploads/ dari pesan-pesan sebelumnya (agar model yang bisa melihat gambar dipilih untuk ViewImages). */
    fun hasImages(projectDir: File): Boolean =
        File(projectDir, "uploads").listFiles()?.any { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "pdf") } == true

    fun allImages(items: List<Committed>): List<ImagePart> = items.flatMap { it.images }.take(MAX_IMAGES_PER_MESSAGE)
}
