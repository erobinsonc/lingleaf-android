package app.lingleaf.ui

import android.text.BidiFormatter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle

/**
 * One reading of a word as the unsure card, Review and Words show it: "to leave — irse, salir"
 * (SPEC S3). The meaning is marked as [language], so a screen reader says it in that language,
 * and isolated, so a right-to-left meaning doesn't reorder the English part.
 */
fun readingLine(
    label: String,
    meaning: String,
    language: String?,
): AnnotatedString =
    buildAnnotatedString {
        append(label)
        if (meaning.isBlank()) return@buildAnnotatedString
        append(" — ")
        appendMeaning(meaning, language)
    }

/** The meanings alone, joined with " · " ("irse, salir · izquierda"), each marked as [language]. */
fun meaningsLine(
    meanings: List<String>,
    language: String?,
): AnnotatedString =
    buildAnnotatedString {
        meanings.filter { it.isNotBlank() }.forEachIndexed { index, meaning ->
            if (index > 0) append(" · ")
            appendMeaning(meaning, language)
        }
    }

private fun AnnotatedString.Builder.appendMeaning(
    meaning: String,
    language: String?,
) {
    val isolated = BidiFormatter.getInstance().unicodeWrap(meaning)
    if (language == null) {
        append(isolated)
    } else {
        withStyle(SpanStyle(localeList = LocaleList(language))) { append(isolated) }
    }
}
