package ae.dressrent.studio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Ink = Color(0xFF1B1420)
val Gold = Color(0xFFC9A227)
val Rose = Color(0xFFB76E79)
val Cream = Color(0xFFFFFBF7)
val Sand = Color(0xFFF3EAE1)
val Success = Color(0xFF2E7D57)
val Warning = Color(0xFFB3541E)
val Danger = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Cream,
    primaryContainer = Sand,
    onPrimaryContainer = Ink,
    secondary = Gold,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFF7EDD2),
    onSecondaryContainer = Color(0xFF4A3B00),
    tertiary = Rose,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Color(0xFF5A4E46),
    outline = Color(0xFFCBBDB2),
    error = Danger
)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = Color(0xFF3A2F18),
    onPrimaryContainer = Color(0xFFF7EDD2),
    secondary = Rose,
    onSecondary = Ink,
    tertiary = Color(0xFFD9BFA3),
    background = Color(0xFF14101A),
    onBackground = Color(0xFFEDE6E0),
    surface = Color(0xFF1E1826),
    onSurface = Color(0xFFEDE6E0),
    surfaceVariant = Color(0xFF2C2433),
    onSurfaceVariant = Color(0xFFCFC3BC),
    outline = Color(0xFF574C57),
    error = Color(0xFFF2B8B5)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

@Composable
fun DressRentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalContext.current

    SideEffect {
        (view as? Activity)?.window?.let { window ->
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
