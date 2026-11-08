package com.mythos.app.agent

import java.io.File

/**
 * Menentukan apakah pesan cukup ditangani prompt ringkas ([Protocol.lightSystemPrompt]).
 * Sengaja sempit: hanya sapaan/terima kasih pendek, tanpa gambar, dan hanya di proyek yang belum punya
 * konteks (CLAUDE.md, MYTHOS.md, KASUS.md, playbook, lampiran). Selain itu tetap memakai prompt penuh.
 */
internal object LightChat {
    private val GREETING = Regex(
        "^\\s*(halo+|hai+|hi+|hello|hey|assalamualaikum|selamat\\s+(pagi|siang|sore|malam)|terima\\s+kasih|makasih|thanks|thank\\s+you|oke|siap|apa\\s+kabar|siapa\\s+(kamu|anda)|kamu\\s+siapa)" +
            "(\\s+(mythos|bos|kak|bro|sis))?[\\s!.,?]*$",
        RegexOption.IGNORE_CASE
    )

    fun isGreeting(task: String): Boolean = task.length <= 40 && GREETING.matches(task)

    fun eligible(root: File, task: String, hasImages: Boolean): Boolean {
        if (hasImages || !isGreeting(task)) return false
        if (listOf("CLAUDE.md", "MYTHOS.md", "KASUS.md").any { File(root, it).isFile }) return false
        if (File(root, "uploads").list()?.isNotEmpty() == true) return false
        return Playbooks.render(root) == null
    }
}
