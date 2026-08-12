package com.trueshine.tgposter.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Brand = Color(0xFF2AABEE)
private val BrandDark = Color(0xFF1B8FCB)
private val Accent = Color(0xFF7C5CFF)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF04121C),
    primaryContainer = Color(0xFF12384F),
    onPrimaryContainer = Color(0xFFCDEBFB),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2B2450),
    onSecondaryContainer = Color(0xFFE0DAFF),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF11171F),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B232E),
    onSurfaceVariant = Color(0xFF9FB0C0),
    outline = Color(0xFF35404D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

private val LightColors = lightColorScheme(
    primary = BrandDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EDFB),
    onPrimaryContainer = Color(0xFF06283A),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E1FF),
    onSecondaryContainer = Color(0xFF1E1642),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF10161C),
    surface = Color.White,
    onSurface = Color(0xFF10161C),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4A5764),
    outline = Color(0xFFC5CFD9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TgPosterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalContext.current
    SideEffect {
        (view as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
