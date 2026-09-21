package com.plovault.sync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF4ADE80)
private val GreenDark = Color(0xFF16A34A)
private val Bg = Color(0xFF0E1116)
private val Surface1 = Color(0xFF161B22)

val PositiveColor = Color(0xFF4ADE80)
val NegativeColor = Color(0xFFF87171)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF07130B),
    secondary = GreenDark,
    background = Bg,
    onBackground = Color(0xFFE6EDF3),
    surface = Surface1,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFFB8C2CC),
    error = NegativeColor
)

private val LightColors = lightColorScheme(primary = GreenDark)

@Composable
fun PloVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else DarkColors,
        content = content
    )
}
