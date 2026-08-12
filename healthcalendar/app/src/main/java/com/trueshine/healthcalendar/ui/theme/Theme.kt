package com.trueshine.healthcalendar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = Green10,
    secondary = Teal40,
    tertiary = Sand40,
    background = SurfaceLight,
    surface = SurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green10,
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = GreenContainerLight,
    secondary = Teal80,
    tertiary = Sand80,
    background = SurfaceDark,
    surface = SurfaceDark,
)

@Composable
fun HealthCalendarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** На Android 12+ подхватываем палитру обоев пользователя. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HealthTypography,
        content = content,
    )
}
