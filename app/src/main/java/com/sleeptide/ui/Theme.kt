package com.sleeptide.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Night = Color(0xFF0A0E24)
    val Surface1 = Color(0xFF141A3A)
    val Surface2 = Color(0xFF1E2550)
    val Outline = Color(0xFF343C6E)
    val Text = Color(0xFFE8EAF6)
    val TextDim = Color(0xFF9AA3C7)
    val Lavender = Color(0xFF8B9BFF)
    val Teal = Color(0xFF4FD1C5)
    val Sunrise = Color(0xFFFFB86B)
    val Rose = Color(0xFFFF7A90)

    val StageAwake = Sunrise
    val StageLight = Lavender
    val StageDeep = Color(0xFF4F55D6)
    val Snore = Rose
}

private val colors = darkColorScheme(
    primary = Palette.Lavender,
    onPrimary = Palette.Night,
    primaryContainer = Palette.Surface2,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Teal,
    onSecondary = Palette.Night,
    secondaryContainer = Color(0xFF213A5A),
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Sunrise,
    onTertiary = Palette.Night,
    background = Palette.Night,
    onBackground = Palette.Text,
    surface = Palette.Night,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface1,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerLowest = Palette.Night,
    surfaceContainerLow = Palette.Surface1,
    surfaceContainer = Palette.Surface1,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerHighest = Palette.Surface2,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Rose,
)

@Composable
fun SleepTideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
