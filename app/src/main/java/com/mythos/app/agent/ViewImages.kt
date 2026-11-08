package com.mythos.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Menampilkan ulang gambar di folder proyek (mis. uploads/) kepada model. Gambar lama tidak tampil lagi di giliran berikutnya,
 * jadi alat ini dipakai agen untuk melihat isi foto tertentu saat perlu memilah atau memastikan. Hanya baca, tanpa perubahan file.
 */
class ViewImages : Tool {
    override val name = "ViewImages"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """ViewImages {"images": ["uploads/a.jpg", "uploads/scan.pdf"], "pages": [1, 2]} — look at images or PDF pages that are already in the project (max $MAX_PER_CALL images/pages per call; they are shown to you in the next message, in the given order). A PDF is rendered page by page; "pages" (1-based, optional, applies to PDFs; default = the first pages) picks which ones. Use it to re-check what a photo or a scanned PDF shows when your notes (uploads/_catatan.md) are not enough, for example before choosing which photos belong in a PDF. Read cannot open images or PDFs."""

    override fun describe(args: JSONObject): String {
        val n = args.optJSONArray("images")?.length() ?: 0
        return "ViewImages $n gambar"
    }

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val arr: JSONArray = args.optJSONArray("images") ?: throw ArgError("argumen 'images' wajib berupa daftar path gambar")
        val names = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return ToolResult(false, "No images given.")
        if (names.size > MAX_PER_CALL) return ToolResult(false, "Too many images (${names.size}); at most $MAX_PER_CALL per call. Ask for the rest in a later call.")

        val pages: List<Int>? = args.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }
        val parts = ArrayList<ImagePart>()
        val lines = ArrayList<String>()
        for ((idx, n) in names.withIndex()) {
            val f = env.sandbox.resolve(n)
            if (!f.isFile) return ToolResult(false, "File not found: $n")
            val rel = env.sandbox.relative(f)
            val remainingFiles = names.size - idx - 1
            if (f.extension.equals("pdf", ignoreCase = true)) {
                val room = (MAX_PER_CALL - parts.size - remainingFiles).coerceAtLeast(if (parts.size < MAX_PER_CALL) 1 else 0)
                if (room <= 0) return ToolResult(false, "Too many images/pages; at most $MAX_PER_CALL per call. Ask for the rest in a later call.")
                val r = PdfPages.render(f, pages, room)
                    ?: return ToolResult(false, "Cannot render PDF $rel (damaged, encrypted or not a real PDF). Tell the user this PDF cannot be read here and ask for photos or a text version; do NOT search the web for its contents.")
                if (r.pages.isEmpty()) return ToolResult(false, "PDF $rel has ${r.pageCount} page(s); none of the requested pages exist. Use 1-based page numbers within 1..${r.pageCount}.")
                for ((num, part) in r.pages) {
                    parts.add(part)
                    lines.add("image ${parts.size} = $rel, page $num of ${r.pageCount}")
                }
                if (r.pages.size < r.pageCount && pages.isNullOrEmpty()) lines.add("($rel has ${r.pageCount} pages; ask for others with \"pages\": [..])")
            } else {
                if (parts.size >= MAX_PER_CALL) return ToolResult(false, "Too many images/pages; at most $MAX_PER_CALL per call. Ask for the rest in a later call.")
                val part = encode(f) ?: return ToolResult(false, "Cannot read $n as an image.")
                parts.add(part)
                lines.add("image ${parts.size} = $rel")
            }
        }
        return ToolResult(
            true,
            "Showing ${parts.size} image(s) in the next message, in this order:\n" + lines.joinToString("\n"),
            images = parts,
        )
    }

    private fun encode(f: java.io.File): ImagePart? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        var bmp: Bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            bmp.recycle()
            bmp = flat
        }
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            ImagePart("image/jpeg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
        } finally {
            bmp.recycle()
        }
    }

    private companion object {
        const val MAX_PER_CALL = 6
        const val MAX_SIDE_PX = 1280
    }
}
