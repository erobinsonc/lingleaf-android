package app.lingleaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.TRANSLATION_LANGUAGES
import app.lingleaf.defaultTargetLanguage
import app.lingleaf.languageName
import java.util.Locale
import kotlin.math.roundToInt

private const val REPOSITORY_URL = "https://github.com/erobinsonc/lingleaf-android"

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val uriHandler = LocalUriHandler.current
    var languageMenu by remember { mutableStateOf(false) }
    val target = settings.targetLanguage ?: defaultTargetLanguage()
    val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    ScreenScaffold(title = stringResource(R.string.settings_title)) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.daily_goal))
            ListItem(
                colors = itemColors,
                headlineContent = { Text(stringResource(R.string.minutes_a_day, settings.dailyGoalMinutes)) },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = { library.updateSettings { it.copy(dailyGoalMinutes = it.dailyGoalMinutes - 5) } },
                            enabled = settings.dailyGoalMinutes > 5,
                        ) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.fewer_minutes)) }
                        IconButton(
                            onClick = { library.updateSettings { it.copy(dailyGoalMinutes = it.dailyGoalMinutes + 5) } },
                            enabled = settings.dailyGoalMinutes < 60,
                        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.more_minutes)) }
                    }
                },
            )

            SectionHeader(stringResource(R.string.reading))
            val textSize = stringResource(R.string.text_size)
            // Read as "Text size, 20" instead of a percentage of the slider's range.
            val textSizeValue = "%d".format(Locale.getDefault(), settings.fontSize.roundToInt())
            Slider(
                value = settings.fontSize,
                onValueChange = { value -> library.updateSettings { it.copy(fontSize = value) } },
                valueRange = 14f..34f,
                steps = 9,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .semantics {
                            contentDescription = textSize
                            stateDescription = textSizeValue
                        },
            )
            Text(
                "All animals are equal.",
                fontFamily = ReadingFont,
                fontSize = settings.fontSize.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            SectionHeader(stringResource(R.string.voice))
            val speechSpeed = stringResource(R.string.speech_speed)
            val speechSpeedValue = stringResource(R.string.speech_speed_value, "%.1f".format(Locale.getDefault(), settings.speechRate))
            Slider(
                value = settings.speechRate,
                onValueChange = { value -> library.updateSettings { it.copy(speechRate = value) } },
                valueRange = 0.5f..1.3f,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .semantics {
                            contentDescription = speechSpeed
                            stateDescription = speechSpeedValue
                        },
            )
            ListItem(
                colors = itemColors,
                modifier =
                    Modifier.clickable {
                        container.speaker.speak("Reading ten minutes a day makes a big difference.", settings.speechRate)
                    },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.test_voice)) },
            )

            SectionHeader(stringResource(R.string.translation))
            ListItem(
                colors = itemColors,
                headlineContent = { Text(stringResource(R.string.translate_to)) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { languageMenu = true }) { Text(languageName(target)) }
                        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            TRANSLATION_LANGUAGES.forEach { code ->
                                DropdownMenuItem(
                                    text = { Text(languageName(code)) },
                                    onClick = {
                                        languageMenu = false
                                        library.updateSettings { it.copy(targetLanguage = code) }
                                    },
                                )
                            }
                        }
                    }
                },
            )
            Text(
                stringResource(R.string.translation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(stringResource(R.string.about))
            ListItem(
                colors = itemColors,
                modifier = Modifier.clickable { uriHandler.openUri(REPOSITORY_URL) },
                leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.source_code)) },
                supportingContent = { Text(stringResource(R.string.open_source_note)) },
            )
            Text(
                stringResource(R.string.credits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            )
        }
    }
}
