package com.mythos.app.agent

import org.json.JSONObject

/**
 * Sub-agen ala alat "Task" Claude Code: agen utama menyerahkan penelitian yang butuh banyak Read/Grep kepada agen anak
 * dengan konteks BARU yang terpisah, lalu hanya menerima satu laporan ringkas. Konteks agen utama tidak penuh oleh hasil mentah.
 *
 * Batasan (disengaja):
 *  - hanya alat baca (LS, Glob, Grep, Read) dan, bila Pencarian web menyala, WebSearch + WebFetch; tidak bisa mengubah file, build, bertanya ke pengguna, atau memanggil Task lagi;
 *  - WebFetch tidak meminta izin (Risk.SAFE, atas permintaan pengguna), jadi sub-agen membuka halaman sendiri tanpa menunggu persetujuan;
 *  - mengikuti sakelar Pencarian web dan kunci Brave/Tavily milik pengguna (sebelumnya selalu punya WebSearch tanpa kunci);
 *  - tidak melihat percakapan utama, jadi `prompt` harus memuat semua yang dibutuhkan;
 *  - berjalan diam-diam (tanpa peristiwa ke layar); layar hanya menampilkan satu baris "Sub-agen: …".
 */
class TaskTool(
    private val llm: LlmClient,
    private val searchKeys: SearchKeys = SearchKeys(),
    private val withWeb: Boolean = true,
) : Tool {
    override val name = "Task"
    override val risk = Risk.SAFE
    override val effect = Effect.META
    override val timeoutMs = 300_000L
    override val doc = """Task {"description": "3-6 word label", "prompt": "full, self-contained instructions"} — launch a read-only sub-agent with a fresh context to investigate something and return ONE concise report. Use it for broad research that would take many Read/Grep calls (finding where something is implemented, auditing several files, summarising a module, looking things up on the web). The sub-agent cannot edit files, build, or ask questions; it can also search the web and open pages, and it cannot see this conversation, so put every detail it needs in `prompt` and say exactly what the report must contain (file paths with line numbers, findings, answers). Do not use it for a single obvious file read."""

    override fun describe(args: JSONObject): String = "Task " + (args.str("description") ?: "sub-agen")

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val prompt = args.reqStr("prompt")
        val tools = ToolRegistry(
            buildList {
                add(LS()); add(Glob()); add(Grep()); add(Read())
                if (withWeb) { add(WebSearch(searchKeys)); add(WebFetch()) }
            }
        )
        val cfg = AgentConfig(
            maxSteps = SUB_MAX_STEPS,
            permissionMode = PermissionMode.ACCEPT_EDITS,
            userAddress = null,
            ackFirst = false,
        )
        val sub = Agent(llm, tools, env.sandbox, Approver { _, _ -> false }, { "" }, null, cfg, null)
        val task = "You are a read-only research sub-agent working for another agent. Investigate the request below using only your tools, " +
            "then reply with ONE concise report (no preamble, no questions): the findings, with file paths as `path/File.kt:42` where relevant. " +
            "If something could not be found, say so plainly. For anything taken from the web, list the URLs you used and say for each whether you OPENED the page (WebFetch) or only saw its title/snippet in search results; never invent a URL, and say when only one source supports a claim.\n\nRequest:\n" + prompt
        val res = sub.execute(task) { }
        return if (res.success) ToolResult(true, res.summary.ifBlank { "(sub-agen tidak menghasilkan laporan)" })
        else ToolResult(false, "Sub-agent stopped early: ${res.summary}")
    }

    private companion object {
        const val SUB_MAX_STEPS = 50
    }
}
