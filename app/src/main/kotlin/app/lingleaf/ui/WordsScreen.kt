package app.lingleaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.Correction
import app.lingleaf.core.TextTools
import app.lingleaf.core.VocabWord
import kotlinx.coroutines.launch

@Composable
fun WordsScreen(onReview: () -> Unit) {
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    var showLearned by rememberSaveable { mutableStateOf(false) }
    val due = remember(state.words) { library.dueWords().size }
    val words = state.words.filter { it.learned == showLearned }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 5 s, longer if the system asks for more time to act; with a screen reader it stays (SPEC S15).
    val undoTimeout = rememberUndoTimeout(baseMillis = 5_000L)
    val target = container.targetLanguage(state.settings)

    // Fill in missing or other-language translations on the device (SPEC S12, S14).
    LaunchedEffect(target) { target?.let(container::fillMissingTranslations) }

    fun delete(word: VocabWord) {
        val name = state.displayName(word)
        library.deleteWord(word.key)
        scope.launch {
            val message = context.getString(R.string.word_deleted_message, name)
            snackbar.showUndo(message, context.getString(R.string.undo), undoTimeout()) {
                library.restoreWord(word.key, word)
            }
        }
    }

    ScreenScaffold(title = stringResource(R.string.words_title), snackbarHostState = snackbar) { modifier ->
        LazyColumn(modifier) {
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = due > 0, onClick = onReview),
                        leadingContent = { Icon(Icons.Filled.Style, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.review_now)) },
                        supportingContent = {
                            Text(
                                when {
                                    due > 0 -> pluralStringResource(R.plurals.words_due, due, due)
                                    state.words.isEmpty() -> stringResource(R.string.save_words_hint)
                                    else -> stringResource(R.string.nothing_due)
                                },
                            )
                        },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }
            }
            // Words corrected to match their sentences after an update, until closed (SPEC S18).
            if (state.corrections.isNotEmpty()) {
                item {
                    CorrectionsCard(
                        corrections = state.corrections,
                        onUndo = library::undoCorrections,
                        onClose = library::closeCorrections,
                    )
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    listOf(false to R.string.filter_learning, true to R.string.filter_known).forEachIndexed { index, (learned, label) ->
                        SegmentedButton(
                            selected = showLearned == learned,
                            onClick = { showLearned = learned },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            if (words.isEmpty()) {
                item {
                    Text(
                        stringResource(if (showLearned) R.string.known_empty else R.string.words_empty),
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                    )
                }
            }
            items(words, key = { it.key }) { word -> WordRow(word, onDelete = { delete(word) }) }
        }
    }
}

@Composable
private fun WordRow(
    word: VocabWord,
    onDelete: () -> Unit,
) {
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val name = state.displayName(word)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Row {
                Text(name, fontWeight = FontWeight.SemiBold)
                if (word.phonetic.isNotEmpty() && word.kind == null) {
                    Text("  ${word.phonetic}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        supportingContent = {
            Column {
                // A helper or modal verb is saved as its verb group: "do · Helper verb"; a
                // contraction has no label: its base ("it is") says what it is.
                word.kind?.let { kind ->
                    val label = kind.label?.let { stringResource(it) }
                    val detail = listOfNotNull(word.base, label).filter { it.isNotEmpty() }
                    if (detail.isNotEmpty()) Text(detail.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val meanings = word.meanings
                if (word.isUnsure && meanings != null) {
                    // Saved unsure: both readings' meanings, "irse, salir · izquierda" (SPEC S4).
                    val line = meaningsLine(meanings.map { it.meaning }, word.translationLanguage)
                    if (line.isNotEmpty()) Text(line)
                } else if (word.translation.isNotEmpty()) {
                    Text(word.translation)
                }
                if (word.context.isNotEmpty() && TextTools.normalize(word.context) != word.word) {
                    Text(
                        word.context,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontStyle = FontStyle.Italic,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = { container.speaker.speak(word.word, state.settings.speechRate) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.listen_word, word.word))
                }
                IconButton(onClick = { library.setLearned(word.key, !word.learned) }) {
                    if (word.learned) {
                        Icon(Icons.Filled.Replay, contentDescription = stringResource(R.string.review_again_word, name))
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.mark_known_word, name))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_word, name))
                }
            }
        },
    )
}

/** How many corrections the card lists before "and N more". */
private const val SHOWN_CORRECTIONS = 3

/**
 * Words corrected to match their sentences (SPEC S18): "Fixed to match its sentence: “leave” →
 * “left”.", or a list of up to three and "and N more", with Undo (puts all back) and Close.
 */
@Composable
private fun CorrectionsCard(
    corrections: List<Correction>,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val single = corrections.singleOrNull()
                if (single != null) {
                    val spoken = stringResource(R.string.corrected_one_spoken, single.from, single.to)
                    Text(
                        stringResource(R.string.corrected_one, single.from, single.to),
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                } else {
                    Text(stringResource(R.string.corrected_many))
                    for (item in corrections.take(SHOWN_CORRECTIONS)) {
                        val spoken = stringResource(R.string.corrected_line_spoken, item.from, item.to)
                        Text(
                            stringResource(R.string.corrected_line, item.from, item.to),
                            modifier = Modifier.semantics { contentDescription = spoken },
                        )
                    }
                    val more = corrections.size - SHOWN_CORRECTIONS
                    if (more > 0) Text(pluralStringResource(R.plurals.corrected_more, more, more))
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
        TextButton(onClick = onUndo, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.undo))
        }
    }
}
