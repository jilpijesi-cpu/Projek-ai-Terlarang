package com.mythos.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class OutputFormatPromptTest {
    @Test
    fun promptEndsWithOutputFormatRule() {
        val p = Protocol.systemPrompt(ToolRegistry.default(false), false, notes = "catatan lingkungan")
        val i = p.indexOf("# OUTPUT FORMAT")
        assertTrue("aturan format harus ada", i >= 0)
        assertTrue("aturan format harus di bagian paling akhir", i > p.indexOf("catatan lingkungan"))
        listOf("italics", "tables", "[text](url)", "no **").forEach { assertTrue("hilang: $it", p.substring(i).contains(it)) }
    }
}
