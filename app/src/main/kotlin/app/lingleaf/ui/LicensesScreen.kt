package app.lingleaf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.lingleaf.R

/**
 * Credits and links shown in the app. Every address is a fixed constant here, never taken
 * from a server's answer, and opens in the system browser.
 */
object Credits {
    const val PRIVACY_POLICY_URL = "https://github.com/erobinsonc/lingleaf-ios/blob/main/PRIVACY.md"
    const val MLKIT_TERMS_URL = "https://developers.google.com/ml-kit/terms"
    const val ANDROID_SDK_LICENSE_URL = "https://developer.android.com/studio/terms"

    /** Names that keep their spelling in every language, so each one is found and linked. */
    val DEFINITION_LINKS =
        listOf(
            "Wiktionary" to "https://en.wiktionary.org/",
            "CC BY-SA 3.0/4.0" to "https://creativecommons.org/licenses/by-sa/4.0/",
            "Free Dictionary API" to "https://dictionaryapi.dev/",
        )

    /** "Definitions from Wiktionary, CC BY-SA 3.0/4.0, via Free Dictionary API.", each name a link. */
    fun definitionsCredit(
        text: String,
        linkColor: Color,
    ): AnnotatedString =
        buildAnnotatedString {
            append(text)
            val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            for ((name, url) in DEFINITION_LINKS) {
                val start = text.indexOf(name)
                if (start >= 0) addLink(LinkAnnotation.Url(url, style), start, start + name.length)
            }
        }
}

/** A library inside the app and the licence it comes under. */
private data class Component(
    val name: String,
    @StringRes val license: Int,
    val url: String?,
)

/** Apache-2.0 §4 asks for the licence text to go with the binary: it ships in res/raw. */
private val COMPONENTS =
    listOf(
        Component("Jetpack Compose, AndroidX (Activity, Core, Lifecycle, Navigation)", R.string.license_apache, null),
        Component("Kotlin, kotlinx.coroutines, kotlinx.serialization", R.string.license_apache, null),
        Component("Google ML Kit Translation", R.string.license_mlkit, Credits.MLKIT_TERMS_URL),
        Component("Google Play services (used by ML Kit)", R.string.license_android_sdk, Credits.ANDROID_SDK_LICENSE_URL),
    )

/** Open-source licences of the libraries the app ships with (SPEC S11), without a licences library. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val apache =
        remember {
            context.resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() }
        }
    val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ScreenScaffold(title = stringResource(R.string.open_source_licenses), onBack = onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            COMPONENTS.forEach { component ->
                val url = component.url
                ListItem(
                    colors = itemColors,
                    modifier = if (url == null) Modifier else Modifier.clickable { uriHandler.openUri(url) },
                    headlineContent = { Text(component.name) },
                    supportingContent = { Text(stringResource(component.license)) },
                )
            }
            Text(
                stringResource(R.string.apache_full_text),
                style = MaterialTheme.typography.titleSmall,
                modifier =
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                        .semantics { heading() },
            )
            SelectionContainer {
                Text(
                    apache,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                )
            }
        }
    }
}
