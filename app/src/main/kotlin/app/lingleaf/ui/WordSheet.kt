@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.lingleaf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.ModelStatus
import app.lingleaf.R
import app.lingleaf.TRANSLATION_LANGUAGES
import app.lingleaf.core.DictionaryEntry
import app.lingleaf.core.TextTools
import app.lingleaf.core.TranslationLanguages
import app.lingleaf.core.VocabWord
import app.lingleaf.languageName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a card button did to [word], so the reader can say so and offer Undo with [previous]. */
data class WordChange(
    val word: String,
    @StringRes val message: Int,
    val previous: VocabWord?,
)

/**
 * The card shown when a word is tapped: pronunciation, translation (with its
 * language), definition, the sentence in context, and I know it / Save word.
 * Translation starts as soon as the card opens and keeps going after the word
 * is saved, so saving never waits for it (SPEC S3, S4, S5, S12, S14, S15).
 */
@Composable
fun WordSheet(
    word: String,
    sentence: String,
    source: String,
    onChanged: (WordChange) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var entry by remember(word) { mutableStateOf<DictionaryEntry?>(null) }
    var entryLoaded by remember(word) { mutableStateOf(false) }
    val target = container.targetLanguage(state.settings)
    var translation by remember(word, target) { mutableStateOf<String?>(null) }
    var translationFailed by remember(word, target) { mutableStateOf(false) }
    var sentenceTranslation by remember(word, target) { mutableStateOf<String?>(null) }
    var modelStatus by remember(word, target) { mutableStateOf(ModelStatus.READY) }
    // Runs in the app's scope, so a translation that arrives after saving is still recorded.
    var pendingTranslation by remember(word, target) { mutableStateOf<Deferred<String?>?>(null) }

    // The dictionary's base form ("animal") when it differs from the tapped word ("animals").
    val headword = entry?.word?.takeIf { it != TextTools.normalize(word) } ?: word
    val saved = state.word(headword)

    LaunchedEffect(word) {
        entry = withContext(container.io) { container.dictionary.lookup(word) }
        entryLoaded = true
    }

    LaunchedEffect(word, target) {
        if (target == null) return@LaunchedEffect
        launch { modelStatus = runCatching { container.translator.modelStatus(target) }.getOrDefault(ModelStatus.READY) }
        launch { sentenceTranslation = container.translateOrNull(sentence, target) }
        // The tapped form starts right away, alongside the dictionary lookup.
        val tapped = container.appScope.async { container.translateOrNull(word, target) }
        pendingTranslation = tapped
        snapshotFlow { entryLoaded }.first { it }
        val base = entry?.word?.takeIf { it != TextTools.normalize(word) }
        val result =
            if (base == null) {
                tapped
            } else {
                container.appScope.async { container.translateOrNull(base, target) ?: tapped.await() }
            }
        pendingTranslation = result
        translation = result.await()
        translationFailed = translation == null
    }

    fun close(change: WordChange) {
        onChanged(change)
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    fun save(learned: Boolean) {
        val key = headword
        val language = target
        val shown = translation
        val pending = pendingTranslation
        val phonetic = entry?.phonetic
        scope.launch {
            val previous =
                withContext(container.io) {
                    library.saveWord(
                        word = key,
                        learned = learned,
                        translation = shown,
                        phonetic = phonetic,
                        context = sentence,
                        source = source,
                        translationLanguage = language.takeIf { shown != null },
                    )
                }
            if (shown == null && language != null && pending != null) {
                container.appScope.launch { pending.await()?.let { library.setTranslation(key, it, language) } }
            }
            close(WordChange(key, if (learned) R.string.word_known_message else R.string.word_saved_message, previous))
        }
    }

    fun remove(previous: VocabWord) {
        scope.launch {
            withContext(container.io) { library.deleteWord(previous.word) }
            close(WordChange(headword, R.string.word_removed_message, previous))
        }
    }

    fun chooseLanguage(code: String) = library.updateSettings { it.copy(targetLanguage = code) }

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
                if (target == null) {
                    LanguagePrompt(onPick = ::chooseLanguage)
                } else {
                    TranslationText(translation, translationFailed, modelStatus, target)
                    LanguageButton(target, onPick = ::chooseLanguage)
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
            }
            val known = saved?.learned == true
            val learning = saved?.learned == false
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { if (saved != null && known) remove(saved) else save(learned = true) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .then(if (known) Modifier.semantics { selected = true } else Modifier),
                ) {
                    Text(stringResource(if (known) R.string.card_known else R.string.card_i_know_it), textAlign = TextAlign.Center)
                }
                Button(
                    onClick = { if (saved != null && learning) remove(saved) else save(learned = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Marker, contentColor = Color.Black),
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .then(if (learning) Modifier.semantics { selected = true } else Modifier),
                ) {
                    Text(
                        stringResource(if (learning) R.string.card_saved else R.string.save_word),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The word's translation, or why it isn't there yet. */
@Composable
private fun TranslationText(
    translation: String?,
    failed: Boolean,
    modelStatus: ModelStatus,
    target: String,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        translation != null -> Text(translation, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        modelStatus == ModelStatus.NEEDS_WIFI ->
            Text(stringResource(R.string.language_needs_wifi, languageName(target)), color = muted, textAlign = TextAlign.Center)
        failed -> Text(stringResource(R.string.translation_unavailable), color = muted, textAlign = TextAlign.Center)
        else -> {
            val translating = stringResource(R.string.translating)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier
                        .size(32.dp)
                        .semantics { contentDescription = translating },
                )
                if (modelStatus == ModelStatus.DOWNLOADING) {
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.downloading_language, languageName(target)), color = muted)
                }
            }
        }
    }
}

/** Small grey "Español ⌄" button under the translation that changes its language in place. */
@Composable
private fun LanguageButton(
    target: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Text(languageName(target))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        LanguageMenu(expanded = open, onDismiss = { open = false }, onPick = onPick)
    }
}

/** Asked once when no language could be picked (SPEC S14): the region's language first, then common ones. */
@Composable
private fun LanguagePrompt(onPick: (String) -> Unit) {
    val container = LocalContainer.current
    val suggestions = remember { TranslationLanguages.suggestions(container.region(), TRANSLATION_LANGUAGES) }
    var more by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.translate_into_prompt), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            suggestions.forEach { code ->
                SuggestionChip(onClick = { onPick(code) }, label = { Text(languageName(code)) })
            }
            Box {
                SuggestionChip(onClick = { more = true }, label = { Text(stringResource(R.string.more_languages)) })
                LanguageMenu(expanded = more, onDismiss = { more = false }, onPick = onPick)
            }
        }
    }
}
