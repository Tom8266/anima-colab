package com.tom.animacolab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B0E14)
val Surface = Color(0xFF151A24)
val SurfaceHigh = Color(0xFF1E2533)
val Primary = Color(0xFF8B7BFF)
val Secondary = Color(0xFFFF7AC6)
val TextPrimary = Color(0xFFE8ECF4)
val TextSecondary = Color(0xFF9AA4B8)
val ErrorColor = Color(0xFFFF6B81)
val SuccessColor = Color(0xFF5BD6A2)
val WarningColor = Color(0xFFFFC86B)

private val scheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Secondary,
    onSecondary = Color(0xFF2A0F20),
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = ErrorColor,
    onError = Color.White,
    outline = Color(0xFF2C3446),
    outlineVariant = Color(0xFF232A38),
)

@Composable
fun AnimaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
