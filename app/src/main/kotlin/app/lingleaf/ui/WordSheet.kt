@file:OptIn(ExperimentalMaterial3Api::class)

package app.lingleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.DictionaryEntry
import app.lingleaf.core.TextTools
import app.lingleaf.defaultTargetLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The card shown when a word is tapped: pronunciation, translation,
 * definition, the sentence in context, and Learned / Learn.
 */
@Composable
fun WordSheet(
    word: String,
    sentence: String,
    source: String,
    onDismiss: () -> Unit,
) {
    val container = LocalContainer.current
    val state by container.library.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var entry by remember(word) { mutableStateOf<DictionaryEntry?>(null) }
    var entryLoaded by remember(word) { mutableStateOf(false) }
    var translation by remember(word) { mutableStateOf<String?>(null) }
    var sentenceTranslation by remember(word) { mutableStateOf<String?>(null) }
    var translationFailed by remember(word) { mutableStateOf(false) }
    val target = state.settings.targetLanguage ?: defaultTargetLanguage()

    // The dictionary's base form ("animal") when it differs from the tapped word ("animals").
    val headword = entry?.word?.takeIf { it != TextTools.normalize(word) } ?: word

    LaunchedEffect(word, target) {
        val sentenceJob = async(container.io) { runCatching { container.translator.translate(sentence, target) }.getOrNull() }
        entry = withContext(container.io) { container.dictionary.lookup(word) }
        entryLoaded = true
        val base = entry?.word?.takeIf { it != TextTools.normalize(word) } ?: word
        withContext(container.io) { runCatching { container.translator.translate(base, target) } }
            .onSuccess { translation = it }
            .onFailure { translationFailed = true }
        sentenceTranslation = sentenceJob.await()
    }

    fun save(learned: Boolean) {
        scope.launch {
            withContext(container.io) {
                container.library.saveWord(
                    word = headword,
                    learned = learned,
                    translation = translation,
                    phonetic = entry?.phonetic,
                    context = sentence,
                    source = source,
                )
            }
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { container.speaker.speak(headword, state.settings.speechRate) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.listen_word, headword))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(headword, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                }
                entry?.phonetic?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (headword != word) {
                    Text(stringResource(R.string.in_text, word), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    translation != null -> Text(translation!!, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    translationFailed ->
                        Text(
                            stringResource(R.string.translation_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    else -> {
                        val translating = stringResource(R.string.translating)
                        CircularProgressIndicator(
                            Modifier
                                .size(32.dp)
                                .semantics { contentDescription = translating },
                        )
                    }
                }
                entry?.definition?.let { definition ->
                    Text(
                        buildAnnotatedString {
                            entry?.partOfSpeech?.let { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("$it · ") } }
                            append(definition)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (entryLoaded && entry == null) {
                    Text(stringResource(R.string.no_definition), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(sentence, fontStyle = FontStyle.Italic, fontFamily = ReadingFont)
                        sentenceTranslation?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { container.speaker.speak(sentence, state.settings.speechRate) }) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.listen_sentence))
                    }
                }
                state.word(headword)?.let { saved ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(if (saved.learned) R.string.already_learned else R.string.already_saved),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row {
                Button(
                    onClick = { save(learned = true) },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Learned, contentColor = Color.White),
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp),
                ) { Text(stringResource(R.string.learned), fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { save(learned = false) },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Marker, contentColor = Color.Black),
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp),
                ) { Text(stringResource(R.string.learn), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
