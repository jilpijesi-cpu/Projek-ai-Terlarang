package com.mythos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun toStringNeverPrintsSecrets() {
        val s = UserSettings(
            provider = ProviderChoice.OPENAI,
            apiKey = "sk-super-rahasia",
            githubToken = "ghp_rahasia_banget",
            githubRepo = "me/app",
            braveKey = "BSA_rahasia",
            tavilyKey = "tvily_rahasia",
            permissionMode = com.mythos.app.agent.PermissionMode.PLAN,
        )
        val text = s.toString()
        assertFalse(text, text.contains("rahasia"))
        assertTrue(text, text.contains("me/app"))
        assertTrue(text, text.contains("permissionMode=PLAN"))
    }

    @Test
    fun permissionModeDefaultsToAcceptEditsAndCanBeChosen() {
        assertTrue(UserSettings().permissionMode == com.mythos.app.agent.PermissionMode.ACCEPT_EDITS)
        val s = UserSettings().copy(permissionMode = com.mythos.app.agent.PermissionMode.PLAN)
        assertTrue(com.mythos.app.agent.AgentConfig(permissionMode = s.permissionMode).permissionMode == com.mythos.app.agent.PermissionMode.PLAN)
    }
}
