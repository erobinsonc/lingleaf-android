package app.lingleaf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.IrregularVerbs
import app.lingleaf.core.TextTools
import app.lingleaf.core.VocabWord

/**
 * Recall-first review of due words with spaced repetition (SPEC S7): the word alone, then
 * "Show meaning" reveals the translation, the sentence it came from and that sentence's
 * translation; only then "Again" or "I remembered".
 */
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val queue = remember { library.reviewSession().toMutableStateList() }
    var total by remember { mutableIntStateOf(queue.size) }
    var reviewed by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    val target = container.targetLanguage(state.settings)

    // Fill in missing or other-language translations on the device (SPEC S12, S14).
    LaunchedEffect(target) { target?.let(container::fillMissingTranslations) }

    fun answer(correct: Boolean) {
        val word = queue.removeAt(0)
        if (correct) reviewed++ else queue.add(word)
        revealed = false
        library.answerReview(word.key, correct)
    }

    ScreenScaffold(title = stringResource(R.string.review_title), onBack = onBack) { modifier ->
        val current = queue.firstOrNull()
        if (current == null) {
            ReviewDone(
                dueNow = library.dueWords().size,
                tomorrow = library.dueByEndOfTomorrow(),
                onBack = onBack,
                onKeepGoing = {
                    queue.addAll(library.reviewSession())
                    total = queue.size
                    reviewed = 0
                },
                modifier = modifier,
            )
        } else {
            ReviewCard(
                // The saved word, so a translation filled in meanwhile shows up.
                word = state.word(current.key) ?: current,
                // Before "Show meaning", two senses are named without their meaning: recall first (SPEC S7).
                name = state.displayName(state.word(current.key) ?: current, withMeaning = revealed),
                target = target,
                reviewed = reviewed,
                total = total,
                revealed = revealed,
                onReveal = { revealed = true },
                onAnswer = ::answer,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ReviewDone(
    dueNow: Int,
    tomorrow: Int,
    onBack: () -> Unit,
    onKeepGoing: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            // "Done for today. 12 words tomorrow.", or, when more words are due now than one
            // session holds, "Done for now. 15 more due today." with Keep going.
            if (dueNow > 0) {
                Text(stringResource(R.string.review_done_for_now), style = MaterialTheme.typography.headlineMedium)
                Text(pluralStringResource(R.plurals.more_due_today, dueNow, dueNow))
            } else {
                Text(stringResource(R.string.review_done), style = MaterialTheme.typography.headlineMedium)
                if (tomorrow > 0) Text(pluralStringResource(R.plurals.more_tomorrow, tomorrow, tomorrow))
            }
            Button(onClick = onBack) { Text(stringResource(R.string.done)) }
            if (dueNow > 0) OutlinedButton(onClick = onKeepGoing) { Text(stringResource(R.string.keep_going)) }
        }
    }
}

@Composable
private fun ReviewCard(
    word: VocabWord,
    /** "to wind" / "the wind" when both senses are saved, else the word (SPEC S4). */
    name: String,
    target: String?,
    reviewed: Int,
    total: Int,
    revealed: Boolean,
    onReveal: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val container = LocalContainer.current
    Column(
        modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        val progressLabel = stringResource(R.string.review_progress)
        val count = stringResource(R.string.review_count, reviewed, total)
        // The count beside the bar: at the start a dark full-width track alone read as "done".
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = progressLabel
                stateDescription = count
                // Still a progress bar to TalkBack, as on iOS.
                progressBarRangeInfo = ProgressBarRangeInfo(reviewed.toFloat(), 0f..maxOf(total, 1).toFloat())
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else reviewed / total.toFloat() },
                modifier = Modifier.weight(1f),
                // A light track and no end dot: only the filled part looks like progress.
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
            )
            Spacer(Modifier.width(12.dp))
            Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The card scrolls (large text, landscape) while the answer buttons stay at the bottom.
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(
                                onClick = {
                                    // A verb with its base's spoken form ("tare"), a noun as written, a word
                                    // saved unsure in each reading (SPEC S7).
                                    val spoken = IrregularVerbs.shared.spokenSaved(word)
                                    container.speaker.speak(spoken, container.library.state.value.settings.speechRate)
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.listen_word, word.word),
                                )
                            }
                        }
                        // A polite live region: when "Show meaning" is replaced by the answer,
                        // TalkBack reads the translation instead of going silent.
                        AnimatedContent(
                            targetState = revealed,
                            label = "reveal",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        ) { shown ->
                            if (shown) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.semantics(mergeDescendants = true) {},
                                ) {
                                    if (word.phonetic.isNotEmpty() && word.kind == null) {
                                        Text(word.phonetic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val meanings = word.meanings
                                    if (word.isUnsure && meanings != null) {
                                        // Saved unsure: both readings, as on the card (SPEC S7).
                                        for (meaning in meanings) {
                                            Text(
                                                readingLine(meaning.label, meaning.meaning, word.translationLanguage),
                                                style = MaterialTheme.typography.titleMedium,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    } else {
                                        // In its language, so TalkBack reads it with that voice.
                                        val shown =
                                            if (word.translation.isEmpty()) {
                                                AnnotatedString(stringResource(R.string.no_translation))
                                            } else {
                                                meaningsLine(listOf(word.translation), word.translationLanguage)
                                            }
                                        Text(shown, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                    }
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    // Two senses of one spelling ("to lie"): the sentence tells which, its translation not yet.
                                    if (name != word.word && word.context.isNotEmpty()) {
                                        Text(
                                            markedSentence(word),
                                            fontStyle = FontStyle.Italic,
                                            fontFamily = ReadingFont,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                    FilledTonalButton(onClick = onReveal) { Text(stringResource(R.string.show_meaning)) }
                                }
                            }
                        }
                        // Outside the live region: TalkBack reads the meaning, not the whole sentence too.
                        if (revealed &&
                            word.context.isNotEmpty() &&
                            TextTools.normalize(word.context) != word.word
                        ) {
                            SavedSentence(word, target)
                        }
                    }
                }
            }
        }
        // Only after the meaning is shown: recall comes first.
        if (revealed) {
            AnswerButtons(onAnswer)
        }
    }
}

/** The sentence the word was saved from (the word in bold), its translation and a button to hear it. */
@Composable
private fun SavedSentence(
    word: VocabWord,
    target: String?,
) {
    val container = LocalContainer.current
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(markedSentence(word), fontStyle = FontStyle.Italic, fontFamily = ReadingFont)
            // Only in the current translation language; another one is translated meanwhile.
            word.contextTranslation(target)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { container.speaker.speak(word.context, container.library.state.value.settings.speechRate) }) {
            Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.listen_sentence))
        }
    }
}

/**
 * The saved [word]'s sentence with the occurrence that was tapped in bold (SPEC S7); for a word
 * saved before that was kept, its forms read in its sense, or a saved group of words.
 */
internal fun markedSentence(word: VocabWord) =
    buildAnnotatedString {
        append(word.context)
        val ranges = word.formRange?.let(::listOf) ?: TextTools.occurrences(word, word.context)
        for (range in ranges) addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.start, range.end)
    }

@Composable
private fun AnswerButtons(onAnswer: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        OutlinedButton(
            onClick = { onAnswer(false) },
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.again), textAlign = TextAlign.Center) }
        Button(
            onClick = { onAnswer(true) },
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.got_it), textAlign = TextAlign.Center) }
    }
}
