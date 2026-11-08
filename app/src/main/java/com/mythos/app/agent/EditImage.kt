package com.mythos.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Watermark teks miring yang diulang menutupi seluruh kanvas (sulit dihapus tanpa merusak isi). */
internal fun drawWatermark(c: Canvas, w: Int, h: Int, text: String, alpha: Int = 70) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha.coerceIn(10, 255), 120, 120, 120)
        textSize = (minOf(w, h) / 14f).coerceAtLeast(12f)
        isFakeBoldText = true
    }
    val step = p.textSize * 4.5f
    c.save()
    c.rotate(-30f, w / 2f, h / 2f)
    val span = maxOf(w, h) * 1.5f
    var y = -span / 2
    while (y < span) {
        var x = -span / 2
        while (x < span) { c.drawText(text, x, y, p); x += p.measureText(text) + step / 2 }
        y += step
    }
    c.restore()
}

/**
 * Mengedit satu gambar dan menyimpannya sebagai berkas BARU (asli tidak pernah ditimpa, supaya bukti tetap utuh).
 * Semua wilayah memakai pecahan 0..1 dari gambar saat itu, jadi tak perlu tahu ukuran piksel.
 */
class EditImage : Tool {
    override val name = "EditImage"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE
    override val timeoutMs: Long get() = 90_000L
    override val doc = """EditImage {"input": "uploads/struk.jpg", "output": "uploads/struk-sensor.jpg", "ops": [{"op":"crop","x":0.05,"y":0.10,"w":0.90,"h":0.60}, {"op":"redact","x":0.10,"y":0.20,"w":0.40,"h":0.05}, {"op":"blur","x":0.10,"y":0.30,"w":0.50,"h":0.06}, {"op":"watermark","text":"Hanya untuk pengaduan Flip"}, {"op":"resize","max_side":1600}]} — edit ONE picture into a NEW file ONLY when the user explicitly asks for an edit of that picture. NEVER use it on evidence or on documents a provider/regulator asked for: those must be attached unmodified. (The original is never overwritten; 'output' must differ from 'input' and end in .jpg or .png). Operations run in order; x, y, w, h are fractions 0..1 of the CURRENT picture (x,y = top-left corner), so after a crop the next regions refer to the cropped picture. crop = keep only that part. redact = solid black box (cannot be undone). blur = heavy pixelation (weaker than redact). watermark = repeated diagonal text over the whole picture. resize = shrink so the longest side is at most max_side pixels. Look at the picture with ViewImages first to choose regions, and check the result with ViewImages again before using it."""

    override fun describe(args: JSONObject): String =
        "EditImage ${args.str("input") ?: "?"} -> ${args.str("output") ?: "?"} (${args.optJSONArray("ops")?.length() ?: 0} langkah)"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val ops = args.optJSONArray("ops") ?: throw ArgError("argumen 'ops' wajib berupa daftar langkah")
        if (ops.length() == 0 || ops.length() > 20) throw ArgError("'ops' harus berisi 1 sampai 20 langkah")
        val inF = env.sandbox.resolve(args.reqStr("input"))
        val outRel = args.reqStr("output")
        val lower = outRel.lowercase()
        if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) return ToolResult(false, "'output' harus berakhiran .jpg, .jpeg atau .png")
        val out = env.sandbox.resolve(outRel)
        if (out == inF) return ToolResult(false, "'output' harus berbeda dari 'input': berkas asli (bukti) tidak boleh ditimpa.")
        if (out.isDirectory) return ToolResult(false, "${env.sandbox.relative(out)} adalah folder.")
        if (!inF.isFile) return ToolResult(false, "Gambar tidak ditemukan: ${args.str("input")}")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inF.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ToolResult(false, "Tidak bisa membaca ${args.str("input")} sebagai gambar.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3000) sample *= 2
        val decoded = BitmapFactory.decodeFile(inF.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return ToolResult(false, "Tidak bisa membaca ${args.str("input")} sebagai gambar.")
        var bmp: Bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        if (bmp !== decoded) decoded.recycle()
        val log = ArrayList<String>()
        try {
            for (i in 0 until ops.length()) {
                currentCoroutineContext().ensureActive()
                val op = ops.optJSONObject(i) ?: throw ArgError("langkah ${i + 1} bukan objek")
                val kind = op.reqStr("op").lowercase()
                fun rect(): PxRect = toPixelRect(op.optDouble("x", Double.NaN), op.optDouble("y", Double.NaN), op.optDouble("w", Double.NaN), op.optDouble("h", Double.NaN), bmp.width, bmp.height)
                    ?: throw ArgError("langkah ${i + 1} ($kind): x, y, w, h harus pecahan 0..1 dan wilayahnya berada di dalam gambar")
                when (kind) {
                    "crop" -> {
                        val r = rect()
                        val n = Bitmap.createBitmap(bmp, r.x, r.y, r.w, r.h)
                        if (n !== bmp) bmp.recycle()
                        bmp = n; log.add("crop -> ${bmp.width}x${bmp.height}")
                    }
                    "redact" -> {
                        val r = rect()
                        Canvas(bmp).drawRect(r.x.toFloat(), r.y.toFloat(), (r.x + r.w).toFloat(), (r.y + r.h).toFloat(), Paint().apply { color = Color.BLACK })
                        log.add("redact")
                    }
                    "blur" -> {
                        val r = rect()
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        pixelate(px, bmp.width, r, (minOf(r.w, r.h) / 3).coerceIn(6, 60))
                        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        log.add("blur")
                    }
                    "watermark" -> {
                        val t = op.reqStr("text").trim()
                        if (t.isEmpty() || t.length > 80) throw ArgError("langkah ${i + 1}: 'text' harus 1-80 karakter")
                        drawWatermark(Canvas(bmp), bmp.width, bmp.height, t); log.add("watermark")
                    }
                    "resize" -> {
                        val m = op.intOr("max_side", 0)
                        if (m < 100 || m > 4000) throw ArgError("langkah ${i + 1}: 'max_side' harus 100-4000")
                        val big = maxOf(bmp.width, bmp.height)
                        if (big > m) {
                            val s = m.toFloat() / big
                            val n = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
                            if (n !== bmp) bmp.recycle()
                            bmp = n
                        }
                        log.add("resize -> ${bmp.width}x${bmp.height}")
                    }
                    else -> throw ArgError("langkah ${i + 1}: op '$kind' tidak dikenal (crop, redact, blur, watermark, resize)")
                }
            }
            env.journal?.beforeChange(out)
            out.parentFile?.mkdirs()
            val existed = out.exists()
            out.outputStream().use { bmp.compress(if (lower.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
            val rel = env.sandbox.relative(out)
            env.session.known.remove(rel)
            return ToolResult(
                true,
                "${if (existed) "Gambar diganti" else "Gambar dibuat"}: $rel (${bmp.width}x${bmp.height}, ${(out.length() + 1023) / 1024} KB). Langkah: ${log.joinToString(", ")}. Berkas asli tidak diubah. Periksa hasilnya dengan ViewImages sebelum dipakai.",
                changed = listOf(rel),
            )
        } finally {
            bmp.recycle()
        }
    }
}
