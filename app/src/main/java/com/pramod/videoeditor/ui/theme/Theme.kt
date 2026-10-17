package com.pramod.videoeditor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = DeepViolet,
    onPrimary = TextPrimary,
    primaryContainer = DeepVioletDark,
    secondary = ElectricBlue,
    onSecondary = CharcoalBlack,
    tertiary = AccentCyan,
    background = CharcoalBlack,
    onBackground = TextPrimary,
    surface = CharcoalSurface,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Danger,
)

@Composable
fun PramodEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
