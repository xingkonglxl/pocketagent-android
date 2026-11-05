package app.pocketagent.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.pocketagent.android.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AB0FF),
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF1C2C42),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFF9AC7A9),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE8EBF1),
    surface = Color(0xFF14181F),
    onSurface = Color(0xFFE8EBF1),
    surfaceVariant = Color(0xFF1D232C),
    onSurfaceVariant = Color(0xFF9BA5B6),
    outline = Color(0xFF2C3440),
    outlineVariant = Color(0xFF232A34),
    error = Color(0xFFFF7A72),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D6BE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2550),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14171C),
    surface = Color.White,
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF5A6472),
    outline = Color(0xFFD5DCE6),
    outlineVariant = Color(0xFFE3E8EF),
    error = Color(0xFFC0392B),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun PocketAgentTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)
