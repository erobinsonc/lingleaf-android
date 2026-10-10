package app.lingleaf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * Brand colours that change between light and dark themes so text and marks
 * keep enough contrast on both papers (WCAG 1.4.3 / 1.4.11).
 */
@Immutable
data class LingleafColors(
    /** Underline under words being learned. The bright marker is too faint on light paper. */
    val learningUnderline: Color,
    /** Background of the word whose card is open. */
    val selectedWordBackground: Color,
    /** Text colour on top of [selectedWordBackground]; [Color.Unspecified] keeps the paragraph colour. */
    val selectedWordText: Color,
    /** Streak text on the daily goal card. */
    val streakText: Color,
    /** Keyboard focus on a word link: must stand out (≥3:1) from the page. */
    val focusedWordBackground: Color,
    val focusedWordText: Color,
)

private val LightLingleafColors =
    LingleafColors(
        learningUnderline = Color(0xFF8A6A00),
        selectedWordBackground = Brand.Marker.copy(alpha = 0.55f),
        selectedWordText = Color.Unspecified,
        streakText = Color(0xFFA0430A),
        // Ink on paper ≈6:1; the 55% marker used for selection was ≈1.2:1, too faint for focus.
        focusedWordBackground = Brand.Ink,
        focusedWordText = Color.White,
    )

private val DarkLingleafColors =
    LingleafColors(
        learningUnderline = Brand.Marker,
        selectedWordBackground = Brand.Marker,
        selectedWordText = Color.Black,
        streakText = Color(0xFFFF8A50),
        focusedWordBackground = Brand.Marker,
        focusedWordText = Color.Black,
    )

val LocalLingleafColors = staticCompositionLocalOf { LightLingleafColors }

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
    CompositionLocalProvider(LocalLingleafColors provides if (dark) DarkLingleafColors else LightLingleafColors) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
