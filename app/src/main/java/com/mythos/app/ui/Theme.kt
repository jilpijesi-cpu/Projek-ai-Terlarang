package com.mythos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MythosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MythosColors.Ink,
            onPrimary = Color.White,
            background = MythosColors.Background,
            onBackground = MythosColors.Ink,
            surface = MythosColors.Card,
            onSurface = MythosColors.Ink,
            surfaceVariant = Color(0xFFF1EFEA),
            onSurfaceVariant = MythosColors.Hint,
            outline = MythosColors.Border,
            error = Color(0xFFB3261E),
        ),
        content = content
    )
}
