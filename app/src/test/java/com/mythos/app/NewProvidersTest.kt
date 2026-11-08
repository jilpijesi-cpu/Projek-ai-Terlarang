package com.mythos.app

import com.mythos.app.agent.ProviderKind
import com.mythos.app.agent.listRequest
import com.mythos.app.agent.parseModelIds
import com.mythos.app.agent.supportsVision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewProvidersTest {
    @Test
    fun huggingFaceUsesRouter() {
        val c = UserSettings(provider = ProviderChoice.HUGGINGFACE, apiKey = "hf_x").toProviderConfig()
        assertEquals("https://router.huggingface.co/v1", c.baseUrl)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, c.kind)
    }

    @Test
    fun githubModelsUsesInferenceAndCatalog() {
        val c = UserSettings(provider = ProviderChoice.GITHUB_MODELS, apiKey = "ghp_x").toProviderConfig()
        assertEquals("https://models.github.ai/inference", c.baseUrl)
        val (url, headers) = listRequest(c)
        assertEquals("https://models.github.ai/catalog/models", url)
        assertEquals("Bearer ghp_x", headers["Authorization"])
    }

    @Test
    fun githubCatalogIsTopLevelArray() {
        val ids = parseModelIds(ProviderKind.OPENAI_COMPATIBLE, """[{"id":"openai/gpt-4.1"},{"id":"meta/llama-3.3-70b-instruct"}]""")
        assertEquals(listOf("openai/gpt-4.1", "meta/llama-3.3-70b-instruct"), ids)
    }

    @Test
    fun openRouterModelIsLockedEvenWithOverride() {
        val s = UserSettings(provider = ProviderChoice.OPENROUTER, apiKey = "sk-or-x", model = "apa-saja")
        assertEquals("openrouter/free", s.toProviderConfig().model)
        assertEquals("openrouter/free", s.toProviderConfig(modelOverride = "").model)
        assertEquals("openrouter/free", s.displayModel)
        assertTrue(s.isConfigured)
        assertTrue(supportsVision(ProviderKind.OPENAI_COMPATIBLE, "openrouter/free"))
    }

    @Test
    fun otherProvidersAreNotLocked() {
        ProviderChoice.values().filter { it != ProviderChoice.OPENROUTER }.forEach { assertEquals(it.name, null, it.lockedModel) }
    }
}
