package com.mythos.app.agent

enum class Role { USER, ASSISTANT }

/** Gambar terlampir pada pesan (base64 tanpa awalan data:). */
class ImagePart(val mime: String, val base64: String)

data class Msg(val role: Role, val text: String, val images: List<ImagePart> = emptyList())

enum class Risk { SAFE, WRITE, DANGEROUS }
enum class Effect { READ, WRITE, EXEC, META }
enum class StepStatus { TODO, DOING, DONE, BLOCKED }

data class PlanStep(val title: String, val status: StepStatus)

/**
 * Hasil satu pemanggilan alat.
 * changed   : file yang diubah (masuk daftar "belum diverifikasi")
 * readPaths : file yang baru dibaca ulang (menghapus status "belum diverifikasi")
 * verifies  : true bila perintah build/test sukses (menghapus semua status belum diverifikasi)
 */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val changed: List<String> = emptyList(),
    val readPaths: List<String> = emptyList(),
    val verifies: Boolean = false,
    /** Gambar yang ikut dikirim ke model pada pesan berikutnya (mis. ViewImages). */
    val images: List<ImagePart> = emptyList(),
)

sealed interface AgentEvent {
    data class Thought(val step: Int, val text: String) : AgentEvent
    /**
     * Teks balasan model yang sedang tiba (dikirim berulang selama model menulis). [text] = narasi yang sudah terbaca, KUMULATIF dan tanpa
     * blok alat; kosong = percobaan diulang dari awal. [tool]/[target] terisi bila model sudah mulai MENULIS pemanggilan alat
     * (mis. "Write" + "src/A.kt"), sehingga UI bisa menampilkan "Menulis A.kt" selagi JSON-nya masih mengalir.
     */
    data class Streaming(val text: String, val tool: String? = null, val target: String? = null, val status: String? = null) : AgentEvent
    data class PlanChanged(val steps: List<PlanStep>) : AgentEvent
    data class ToolStart(val step: Int, val tool: String, val summary: String, val status: String = "") : AgentEvent
    data class ToolEnd(val step: Int, val tool: String, val ok: Boolean, val preview: String, val millis: Long, val summary: String = "") : AgentEvent
    /** Draf email siap ditinjau: UI menampilkannya sebagai kartu dengan tombol "Kirim email" (aplikasi email baru dibuka saat diketuk). */
    data class EmailDraft(val to: List<String>, val cc: List<String>, val subject: String, val body: String, val attachments: List<String>) : AgentEvent
    data class Notice(val text: String) : AgentEvent
    data class Final(val summary: String, val verified: Boolean, val steps: Int) : AgentEvent
    data class Failed(val reason: String) : AgentEvent
}

data class AgentResult(val success: Boolean, val summary: String, val verified: Boolean, val steps: Int)

class AgentAbort(message: String) : Exception(message)
class ArgError(message: String) : Exception(message)
