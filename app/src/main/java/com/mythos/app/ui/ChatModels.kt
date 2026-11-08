package com.mythos.app.ui

import com.mythos.app.agent.FileChange
import com.mythos.app.agent.PlanStep
import kotlinx.coroutines.CompletableDeferred

enum class Screen { HOME, CHAT, SETTINGS, PROJECTS, CODE, ARTIFACTS }
enum class ToolStatus { RUNNING, OK, ERROR }

sealed interface ChatItem { val id: Long }
data class UserItem(override val id: Long, val text: String, val attachments: List<Attachment> = emptyList()) : ChatItem
/** [live] = teks yang SEDANG mengalir dari model (belum selesai); tidak disimpan ke riwayat. */
data class ThoughtItem(override val id: Long, val text: String, val live: Boolean = false) : ChatItem
data class ToolItem(
    override val id: Long,
    val step: Int,
    val tool: String,
    val summary: String,
    val status: ToolStatus,
    val preview: String = "",
    val millis: Long = 0,
    /** Frasa status dari model untuk baris aktivitas (kosong = pakai judul alat). */
    val note: String = "",
) : ChatItem
data class PlanItem(override val id: Long, val steps: List<PlanStep>) : ChatItem
data class NoticeItem(override val id: Long, val text: String) : ChatItem
data class FinalItem(override val id: Long, val text: String, val verified: Boolean, val showStatus: Boolean = true) : ChatItem
data class ErrorItem(override val id: Long, val text: String) : ChatItem

/**
 * Ringkasan perubahan file satu tugas (titik pulih). [checkpointId] menunjuk catatan di CheckpointStore; [undone] = pengguna
 * sudah menekan "Batalkan tugas ini".
 */
data class ChangesItem(
    override val id: Long,
    val checkpointId: String,
    val files: List<FileChange>,
    val undone: Boolean = false,
) : ChatItem

/** Kartu draf email di chat. [attachments] = path berkas di proyek (PDF); aplikasi email baru dibuka saat pengguna mengetuk "Kirim email". */
data class EmailDraftItem(
    override val id: Long,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,
    val attachments: List<String>,
) : ChatItem

data class ApprovalRequest(val tool: String, val summary: String, val result: CompletableDeferred<Boolean>)
data class QuestionRequest(val question: String, val result: CompletableDeferred<String>)

/** Alat yang sedang DITULIS model (JSON-nya masih mengalir): belum menjadi ToolItem karena belum tentu valid. */
data class LiveActivity(val tool: String, val target: String?, val status: String? = null)
