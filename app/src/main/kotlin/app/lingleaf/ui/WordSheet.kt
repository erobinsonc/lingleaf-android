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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.ModelStatus
import app.lingleaf.R
import app.lingleaf.TRANSLATION_LANGUAGES
import app.lingleaf.core.DictionaryEntry
import app.lingleaf.core.FunctionWord
import app.lingleaf.core.IrregularVerbs
import app.lingleaf.core.TextTools
import app.lingleaf.core.TranslationLanguages
import app.lingleaf.core.VocabWord
import app.lingleaf.core.WordRange
import app.lingleaf.languageName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a card button did to [word], so the reader can say so (naming it [label]) and
 * offer Undo with [previous].
 */
data class WordChange(
    val word: String,
    @StringRes val message: Int,
    val previous: VocabWord?,
    val label: String = word,
)

/** A translation still on its way, and the [word] it is the translation of. */
private class PendingTranslation(
    val word: String,
    val result: Deferred<String?>,
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
    rangeInSentence: WordRange? = null,
    function: FunctionWord? = null,
    /** Reads the book aloud from this sentence on; null hides the button. */
    onReadFromHere: (() -> Unit)? = null,
) {
    // A helper or modal verb (SPEC S16): its verb group ("did not go") is what's shown,
    // translated, heard and saved; without one, the sentence is saved.
    val chunk = function?.chunk?.let { sentence.substring(it.start, it.end) }
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var entry by remember(word) { mutableStateOf<DictionaryEntry?>(null) }
    var entryLoaded by remember(word) { mutableStateOf(false) }
    val target = container.targetLanguage(state.settings)
    // The headword's own translation: the only one saved with the word.
    var translation by remember(word, target) { mutableStateOf<String?>(null) }
    // The tapped form's ("animals"), shown while the base form's ("animal") is on its way.
    var tappedTranslation by remember(word, target) { mutableStateOf<String?>(null) }
    var translationFailed by remember(word, target) { mutableStateOf(false) }
    var sentenceTranslation by remember(word, target) { mutableStateOf<String?>(null) }
    var modelStatus by remember(word, target) { mutableStateOf(ModelStatus.READY) }
    // Runs in the app's scope, so a translation that arrives after saving is still recorded.
    var pendingTranslation by remember(word, target) { mutableStateOf<PendingTranslation?>(null) }

    // A form that is also another word ("left", "saw") is read from its sentence (SPEC S3).
    val reading =
        remember(word, sentence, rangeInSentence) {
            rangeInSentence?.takeIf { function == null }?.let { IrregularVerbs.shared.resolve(sentence, it) }
        }
    // The irregular-verb table's base ("stolen" → "steal", "she left" → "leave"): the base form
    // when the dictionary can't be reached or doesn't know the word (SPEC S3). Not over an entry
    // the dictionary has for the form itself ("a human being").
    val tableBase =
        remember(word, sentence, rangeInSentence) {
            when {
                function != null -> null
                rangeInSentence != null -> IrregularVerbs.shared.baseAt(sentence, rangeInSentence)
                else -> IrregularVerbs.shared.formToBase[TextTools.normalize(word)]
            }
        }
    // The dictionary's base form ("animal") when it differs from the tapped word ("animals").
    val baseForm = entry?.word?.takeIf { it != TextTools.normalize(word) } ?: tableBase?.takeIf { entryLoaded && entry == null }
    val headword = function?.base ?: baseForm ?: word
    val savedKey = if (function == null) headword else chunk ?: sentence
    // How messages name it: "did not go"; without a verb group, the start of the sentence.
    val savedName =
        when {
            function == null -> headword
            chunk != null -> chunk
            sentence.length > 30 -> sentence.take(30) + "…"
            else -> sentence
        }
    val saved = state.word(savedKey)

    LaunchedEffect(word) {
        // A helper or modal verb needs no dictionary: its definition and IPA would be the
        // base form's ("do"), not what the learner sees ("didn't go").
        if (function == null) entry = withContext(container.io) { container.dictionary.lookup(word, reading) }
        entryLoaded = true
    }

    LaunchedEffect(word, target) {
        if (target == null) return@LaunchedEffect
        launch { modelStatus = runCatching { container.translator.modelStatus(target) }.getOrDefault(ModelStatus.READY) }
        launch { sentenceTranslation = container.translateOrNull(sentence, target) }
        if (function != null) {
            // Alone the helper means little ("did" → "hizo"): only its verb group is translated.
            if (chunk == null) return@LaunchedEffect
            val group = container.appScope.async { container.translateOrNull(chunk, target) }
            pendingTranslation = PendingTranslation(savedKey, group)
            translation = group.await()
            translationFailed = translation == null
            return@LaunchedEffect
        }
        // The tapped form starts right away, alongside the dictionary lookup.
        val tapped = container.appScope.async { container.translateOrNull(word, target) }
        pendingTranslation = PendingTranslation(word, tapped)
        snapshotFlow { entryLoaded }.first { it }
        val base = entry?.word?.takeIf { it != TextTools.normalize(word) } ?: tableBase?.takeIf { entry == null }
        if (base == null) {
            translation = tapped.await()
            translationFailed = translation == null
            return@LaunchedEffect
        }
        // The base form is saved only with its own translation; the tapped form's is shown meanwhile.
        val baseTranslation = container.appScope.async { container.translateOrNull(base, target) }
        pendingTranslation = PendingTranslation(base, baseTranslation)
        val showTapped = launch { tappedTranslation = tapped.await() }
        translation = baseTranslation.await()
        if (translation == null) showTapped.join()
        translationFailed = translation == null && tappedTranslation == null
    }

    // The Undo message comes once the card has gone, so TalkBack announces it (SPEC S4).
    fun close(change: WordChange) {
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                onChanged(change)
                onDismiss()
            }
        }
    }

    fun save(learned: Boolean) {
        val key = savedKey
        val language = target
        // Without a verb group, the sentence is what's saved: its translation goes with it.
        val shown = if (function != null && chunk == null) sentenceTranslation else translation
        // Only a translation of the word being saved may be written to it later.
        val pending = pendingTranslation?.takeIf { it.word == key }?.result
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
                        kind = function?.kind,
                        base = function?.base,
                        contextTranslation = sentenceTranslation,
                    )
                }
            if (shown == null && language != null && pending != null) {
                container.appScope.launch { pending.await()?.let { library.setTranslation(key, it, language) } }
            }
            close(WordChange(key, if (learned) R.string.word_known_message else R.string.word_saved_message, previous, savedName))
        }
    }

    fun remove(previous: VocabWord) {
        scope.launch {
            withContext(container.io) { library.deleteWord(previous.word) }
            close(WordChange(savedKey, R.string.word_removed_message, previous, savedName))
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
                    // A helper or modal verb: how the verb group sounds ("didn't go", not "do").
                    val shown = if (function == null) headword else chunk ?: word
                    // The tapped form itself, with the table's respelling where its sentence proves
                    // the form ("she had read" → "red"); never for the label.
                    val respelled =
                        rangeInSentence
                            ?.takeIf { function == null && TextTools.normalize(headword) == TextTools.normalize(word) }
                            ?.let { IrregularVerbs.shared.spokenAt(sentence, it) }
                    FilledTonalIconButton(onClick = { container.speaker.speak(respelled ?: shown, state.settings.speechRate) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.listen_word, shown))
                    }
                    Spacer(Modifier.width(12.dp))
                    // A screen reader says "he had or he would", not "slash".
                    val either = headword.split(" / ").takeIf { it.size == 2 }
                    val spokenHeadword = either?.let { stringResource(R.string.either_or, it[0], it[1]) } ?: headword
                    Text(
                        headword,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier.semantics {
                                heading()
                                contentDescription = spokenHeadword
                            },
                    )
                }
                entry?.phonetic?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // A helper or modal verb shows its form in the verb group just below.
                if (function == null && headword != word) {
                    Text(stringResource(R.string.in_text, word), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                function?.kind?.label?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (target == null) {
                    LanguagePrompt(onPick = ::chooseLanguage)
                } else {
                    // The verb group it belongs to, then its translation (as on iOS, once a language is set).
                    // Not when it is just the tapped word ("That's"): the headword and note explain it.
                    chunk?.takeIf { it != word }?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    if (function == null || chunk != null) {
                        TranslationText(translation ?: tappedTranslation, translationFailed, modelStatus, target)
                    }
                    LanguageButton(target, onPick = ::chooseLanguage)
                }
                function?.let {
                    Text(functionNote(it, word), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
                if (function == null && entryLoaded && entry == null) {
                    Text(stringResource(R.string.no_definition), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            markedSentence(sentence, rangeInSentence, function?.chunk),
                            fontStyle = FontStyle.Italic,
                            fontFamily = ReadingFont,
                        )
                        sentenceTranslation?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Back to an earlier point, or on from this one (SPEC S6).
                        onReadFromHere?.let {
                            TextButton(onClick = it, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.read_from_here))
                            }
                        }
                    }
                    IconButton(onClick = { container.speaker.speak(sentence, state.settings.speechRate) }) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.listen_sentence))
                    }
                }
            }
            val known = saved?.learned == true
            val learning = saved?.learned == false
            val removeLabel = stringResource(R.string.remove_from_words)
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
                            .then(if (saved != null && known) Modifier.checked(removeLabel) { remove(saved) } else Modifier),
                ) {
                    if (known) CheckMark()
                    Text(stringResource(if (known) R.string.card_known else R.string.card_i_know_it), textAlign = TextAlign.Center)
                }
                Button(
                    onClick = { if (saved != null && learning) remove(saved) else save(learned = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Marker, contentColor = Color.Black),
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .then(if (saved != null && learning) Modifier.checked(removeLabel) { remove(saved) } else Modifier),
                ) {
                    if (learning) CheckMark()
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

/** The sentence with the tapped word in bold, underlined with its verb group if it has one (SPEC S17). */
private fun markedSentence(
    sentence: String,
    word: WordRange?,
    chunk: WordRange?,
) = buildAnnotatedString {
    append(sentence)
    (chunk ?: word)?.let { addStyle(SpanStyle(textDecoration = TextDecoration.Underline), it.start, it.end) }
    word?.let { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.start, it.end) }
}

/** Null for a contraction: the card's headword ("it is") already says what it is. */
@get:StringRes
val FunctionWord.Kind.label: Int?
    get() =
        when (this) {
            FunctionWord.Kind.HELPER -> R.string.helper_verb
            FunctionWord.Kind.MODAL -> R.string.modal_verb
            FunctionWord.Kind.CONTRACTION -> null
        }

private val expandedNegatives = mapOf("didn't" to "did not", "don't" to "do not", "doesn't" to "does not")

/** One short line on what the word does here. [form] is the word as tapped ("did", "didn't"). */
@Composable
private fun functionNote(
    function: FunctionWord,
    form: String,
): String {
    // "I'll" keeps its capital I; other words are shown in lower case.
    val word = if (form.startsWith("I'") || form.startsWith("I’")) form else form.lowercase()
    function.expansion?.let { return contractionNote(function.use, word, it) }
    val contracted = TextTools.normalize(word).endsWith("n't")
    val negative = if (contracted) word else "$word not"
    val expanded = expandedNegatives[TextTools.normalize(word)] ?: negative
    // A perfect's helper as tapped, with "not" when it follows: "has not" for "has not seen".
    val shown = if (function.negative && !contracted) "$word not" else word
    return when (val use = function.use) {
        FunctionWord.Use.PastNegative ->
            if (contracted) {
                stringResource(R.string.note_past_negative_contracted, negative, expanded)
            } else {
                stringResource(R.string.note_past_negative, negative)
            }
        FunctionWord.Use.PresentNegative ->
            if (contracted) {
                stringResource(R.string.note_present_negative_contracted, negative, expanded)
            } else {
                stringResource(R.string.note_present_negative, negative)
            }
        is FunctionWord.Use.Modal ->
            if (function.negative) {
                // The meaning can flip: "must" = necessary, "mustn't" = not allowed.
                val shown = if (contracted || word == "cannot") word else "$word not"
                when {
                    use.base == "must" -> stringResource(R.string.note_modal_not_allowed, shown)
                    contracted || word == "cannot" ->
                        stringResource(
                            R.string.note_modal_negative_contracted,
                            shown,
                            "${use.base} not",
                            use.base,
                        )
                    else -> stringResource(R.string.note_modal_negative, shown, use.base)
                }
            } else {
                stringResource(modalNotes[use.base] ?: R.string.note_might)
            }
        FunctionWord.Use.Prohibition -> stringResource(R.string.note_prohibition, negative)
        FunctionWord.Use.QuestionTag -> stringResource(R.string.note_question_tag, word)
        FunctionWord.Use.PastQuestion -> stringResource(R.string.note_past_question, word)
        FunctionWord.Use.PresentQuestion -> stringResource(R.string.note_present_question, word)
        FunctionWord.Use.PresentPerfect -> stringResource(R.string.note_present_perfect, shown)
        FunctionWord.Use.PastPerfect -> stringResource(R.string.note_past_perfect, shown)
        FunctionWord.Use.HadBetter -> stringResource(R.string.note_had_better)
        FunctionWord.Use.Contraction -> stringResource(R.string.note_contraction, word, function.base)
    }
}

/** A pronoun contraction: what it stands for, and what it does when it is a helper here. */
@Composable
private fun contractionNote(
    use: FunctionWord.Use,
    word: String,
    expansion: String,
): String =
    when {
        use == FunctionWord.Use.PresentPerfect -> stringResource(R.string.note_present_perfect_contracted, word, expansion)
        use == FunctionWord.Use.PastPerfect -> stringResource(R.string.note_past_perfect_contracted, word, expansion)
        use == FunctionWord.Use.HadBetter -> stringResource(R.string.note_had_better_contracted)
        use == FunctionWord.Use.Modal("will") -> stringResource(R.string.note_will_contracted, word, expansion)
        use is FunctionWord.Use.Modal -> stringResource(R.string.note_would_contracted, word, expansion)
        // "he'd put": had or would; the sentence's translation settles it.
        " / " in expansion -> expansion.split(" / ").let { stringResource(R.string.note_had_or_would, word, it[0], it[1]) }
        TextTools.normalize(word) == "let's" -> stringResource(R.string.note_lets)
        // The lookalikes learners mix up.
        TextTools.normalize(word) == "it's" -> stringResource(R.string.note_its, expansion)
        TextTools.normalize(word) == "who's" -> stringResource(R.string.note_whos, expansion)
        else -> stringResource(R.string.note_contraction, word, expansion)
    }

private val modalNotes =
    mapOf(
        "will" to R.string.note_will,
        "would" to R.string.note_would,
        "can" to R.string.note_can,
        "could" to R.string.note_could,
        "shall" to R.string.note_shall,
        "should" to R.string.note_should,
        "must" to R.string.note_must,
        "may" to R.string.note_may,
    )

/**
 * A checked card button: selected, and a screen reader says tapping it will
 * [removeLabel] ("Remove from Words"), which runs [onRemove] (SPEC S15).
 */
private fun Modifier.checked(
    removeLabel: String,
    onRemove: () -> Unit,
): Modifier =
    semantics {
        selected = true
        onClick(label = removeLabel) {
            onRemove()
            true
        }
    }

/** The check mark that leads a checked card button's label; the button's selected state says it to screen readers. */
@Composable
private fun CheckMark() {
    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
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
    val label = stringResource(R.string.translation_language_label, languageName(target))
    Box {
        TextButton(
            onClick = { open = true },
            modifier =
                Modifier.semantics {
                    contentDescription = label
                    role = Role.DropdownList
                },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            // Read as "Translation language: Español" instead of just the name.
            Text(languageName(target), modifier = Modifier.clearAndSetSemantics {})
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
