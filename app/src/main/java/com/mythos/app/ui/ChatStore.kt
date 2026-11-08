package com.mythos.app.ui

import com.mythos.app.agent.ChangeKind
import com.mythos.app.agent.FileChange
import com.mythos.app.agent.PlanStep
import com.mythos.app.agent.StepStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMeta(val id: String, val title: String, val projectPath: String, val updatedAt: Long)

class SavedChat(val meta: ChatMeta, val items: List<ChatItem>, val history: List<Pair<String, String>>)

/**
 * Menyimpan obrolan sebagai JSON di penyimpanan privat aplikasi (bukan di folder proyek):
 *   <id>.json  = isi lengkap, <id>.meta = ringkasan kecil untuk daftar riwayat.
 * Penulisan atomik (tulis ke .tmp lalu rename).
 */
class ChatStore(private val dir: File) {
    init { dir.mkdirs() }

    @Synchronized
    fun save(chat: SavedChat) {
        val m = chat.meta
        val items = JSONArray()
        chat.items.forEach { items.put(encode(it)) }
        val hist = JSONArray()
        chat.history.forEach { (t, r) -> hist.put(JSONObject().put("t", t).put("r", r)) }
        val full = JSONObject().put("meta", metaJson(m)).put("items", items).put("history", hist)
        atomicWrite(File(dir, "${m.id}.json"), full.toString())
        atomicWrite(File(dir, "${m.id}.meta"), metaJson(m).toString())
    }

    @Synchronized
    fun load(id: String): SavedChat? = try {
        val o = JSONObject(File(dir, "$id.json").readText())
        val arr = o.getJSONArray("items")
        val items = ArrayList<ChatItem>()
        for (i in 0 until arr.length()) {
            decode(arr.getJSONObject(i), items.size.toLong())?.let { items.add(it) }
        }
        val hs = o.optJSONArray("history") ?: JSONArray()
        val history = (0 until hs.length()).map { i -> hs.getJSONObject(i).let { it.optString("t") to it.optString("r") } }
        SavedChat(metaOf(o.getJSONObject("meta")), items, history)
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun list(): List<ChatMeta> =
        (dir.listFiles { f -> f.extension == "meta" } ?: emptyArray())
            .mapNotNull { f -> try { metaOf(JSONObject(f.readText())) } catch (e: Exception) { null } }
            .sortedByDescending { it.updatedAt }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.meta").delete()
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun metaJson(m: ChatMeta) =
        JSONObject().put("id", m.id).put("title", m.title).put("path", m.projectPath).put("updatedAt", m.updatedAt)

    private fun metaOf(o: JSONObject) = ChatMeta(o.getString("id"), o.optString("title"), o.optString("path"), o.optLong("updatedAt"))

    internal fun encode(i: ChatItem): JSONObject = when (i) {
        is UserItem -> {
            val atts = JSONArray()
            i.attachments.forEach {
                atts.put(JSONObject().put("name", it.name).put("kind", it.kind.name).put("path", it.path).put("mime", it.mime).put("size", it.size))
            }
            JSONObject().put("k", "user").put("text", i.text).put("att", atts)
        }
        is ThoughtItem -> JSONObject().put("k", "thought").put("text", i.text)
        is ToolItem -> JSONObject().put("k", "tool").put("step", i.step).put("tool", i.tool)
            .put("summary", i.summary).put("status", i.status.name).put("preview", i.preview).put("ms", i.millis).put("note", i.note)
        is PlanItem -> {
            val a = JSONArray()
            i.steps.forEach { a.put(JSONObject().put("title", it.title).put("status", it.status.name)) }
            JSONObject().put("k", "plan").put("steps", a)
        }
        is NoticeItem -> JSONObject().put("k", "notice").put("text", i.text)
        is FinalItem -> JSONObject().put("k", "final").put("text", i.text).put("verified", i.verified).put("showStatus", i.showStatus)
        is ErrorItem -> JSONObject().put("k", "error").put("text", i.text)
        is EmailDraftItem -> JSONObject().put("k", "email").put("to", JSONArray(i.to)).put("cc", JSONArray(i.cc))
            .put("subject", i.subject).put("body", i.body).put("att", JSONArray(i.attachments))
        is ChangesItem -> {
            val files = JSONArray()
            i.files.forEach {
                files.put(JSONObject().put("p", it.path).put("t", it.kind.name).put("a", it.added).put("r", it.removed).put("b", it.binary))
            }
            JSONObject().put("k", "changes").put("checkpoint", i.checkpointId).put("undone", i.undone).put("files", files)
        }
    }

    internal fun decode(o: JSONObject, id: Long): ChatItem? = when (o.optString("k")) {
        "user" -> {
            val arr = o.optJSONArray("att")
            val atts = (0 until (arr?.length() ?: 0)).mapNotNull { idx ->
                val a = arr!!.optJSONObject(idx) ?: return@mapNotNull null
                Attachment(
                    a.optString("name"), runCatching { AttachKind.valueOf(a.optString("kind")) }.getOrDefault(AttachKind.DOC),
                    a.optString("path"), a.optString("mime"), a.optLong("size")
                )
            }
            UserItem(id, o.optString("text"), atts)
        }
        "thought" -> ThoughtItem(id, o.optString("text"))
        "tool" -> {
            val st = runCatching { ToolStatus.valueOf(o.optString("status")) }.getOrDefault(ToolStatus.ERROR)
            // Alat yang masih "berjalan" saat disimpan berarti terputus.
            ToolItem(
                id, o.optInt("step"), o.optString("tool"), o.optString("summary"),
                if (st == ToolStatus.RUNNING) ToolStatus.ERROR else st,
                o.optString("preview"), o.optLong("ms"), o.optString("note")
            )
        }
        "plan" -> {
            val a = o.optJSONArray("steps") ?: JSONArray()
            PlanItem(id, (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                PlanStep(s.optString("title"), runCatching { StepStatus.valueOf(s.optString("status")) }.getOrDefault(StepStatus.TODO))
            })
        }
        "notice" -> NoticeItem(id, o.optString("text"))
        "final" -> FinalItem(id, o.optString("text"), o.optBoolean("verified"), o.optBoolean("showStatus", true))
        "error" -> ErrorItem(id, o.optString("text"))
        "email" -> {
            fun list(k: String) = o.optJSONArray(k).let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) } }
            EmailDraftItem(id, list("to"), list("cc"), o.optString("subject"), o.optString("body"), list("att"))
        }
        "changes" -> {
            val arr = o.optJSONArray("files") ?: JSONArray()
            val files = ArrayList<FileChange>()
            for (n in 0 until arr.length()) {
                val f = arr.optJSONObject(n) ?: continue
                val kind = ChangeKind.values().firstOrNull { it.name == f.optString("t") } ?: continue
                files.add(FileChange(f.optString("p"), kind, f.optInt("a"), f.optInt("r"), f.optBoolean("b")))
            }
            ChangesItem(id, o.optString("checkpoint"), files, o.optBoolean("undone"))
        }
        else -> null
    }
}
