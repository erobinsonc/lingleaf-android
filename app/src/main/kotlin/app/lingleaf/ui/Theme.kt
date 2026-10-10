package app.lingleaf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Brand colours: dark-green ink and a highlighter yellow. */
object Brand {
    val Ink = Color(0xFF2E6B5E)
    val InkLight = Color(0xFF8FD3BF)
    val Marker = Color(0xFFF5CF4A)
    val Learned = Color(0xFF2E7D57)
    val PaperLight = Color(0xFFFBF8F1)
    val PaperDark = Color(0xFF171B1A)
}

val ReadingFont = FontFamily.Serif

private val LightColors =
    lightColorScheme(
        primary = Brand.Ink,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDEBE1),
        onPrimaryContainer = Color(0xFF0F3A31),
        secondary = Color(0xFF7A5E00),
        background = Brand.PaperLight,
        surface = Brand.PaperLight,
        surfaceContainer = Color(0xFFF1EEE6),
        surfaceContainerLow = Color(0xFFF5F2EA),
    )

private val DarkColors =
    darkColorScheme(
        primary = Brand.InkLight,
        onPrimary = Color(0xFF0F3A31),
        primaryContainer = Color(0xFF1F4F45),
        onPrimaryContainer = Color(0xFFCDEBE1),
        secondary = Brand.Marker,
        background = Brand.PaperDark,
        surface = Brand.PaperDark,
        surfaceContainer = Color(0xFF222826),
        surfaceContainerLow = Color(0xFF1C2120),
    )

@Composable
fun LingleafTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
