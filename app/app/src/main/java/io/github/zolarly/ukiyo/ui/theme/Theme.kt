package io.github.zolarly.ukiyo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// 浮世只有夜色一套主题，不跟随系统深浅色，也不用壁纸取色。
private val Scheme = darkColorScheme(
    primary = Blossom,
    onPrimary = Abyss,
    secondary = Tide,
    onSecondary = Abyss,
    tertiary = Gold,
    background = Night,
    onBackground = Moon,
    surface = Night,
    onSurface = Moon,
    onSurfaceVariant = Dim,
    surfaceContainerHigh = Dusk,
    outline = Moon.copy(alpha = 0.24f),
    error = Ember,
)

@Composable
fun UkiyoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography, content = content)
}
