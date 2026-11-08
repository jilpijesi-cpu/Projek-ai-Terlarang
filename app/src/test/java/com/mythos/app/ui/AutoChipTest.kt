package com.mythos.app.ui
import com.mythos.app.ProviderChoice
import com.mythos.app.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test
class AutoChipTest {
    @Test
    fun blankModelShowsProviderLocalAutomaticMode() {
        val s = UserSettings(provider = ProviderChoice.OPENAI)
        assertEquals("Otomatis" to "OpenAI", autoChip(s))
    }
    @Test
    fun selectedModelShowsProviderAndModel() {
        val s = UserSettings(provider = ProviderChoice.OPENAI, model = "gpt-4.1")
        assertEquals("gpt-4.1" to "OpenAI", autoChip(s, "ignored", 3, 15))
    }
    @Test
    fun uiModelChipMatchesProviderLocalMode() {
        val s = UserSettings(provider = ProviderChoice.OPENROUTER)
        assertEquals("openrouter/free" to "OpenRouter", uiModelChip(s))
    }
}
