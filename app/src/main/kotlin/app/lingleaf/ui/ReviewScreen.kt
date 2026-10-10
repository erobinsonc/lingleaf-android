package app.lingleaf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lingleaf.R
import app.lingleaf.core.VocabWord

/** Flashcard review of due words with spaced repetition. */
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val library = LocalContainer.current.library
    val queue = remember { library.dueWords().toMutableStateList() }
    val total = remember { queue.size }
    var reviewed by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    fun answer(correct: Boolean) {
        val word = queue.removeAt(0)
        if (correct) reviewed++ else queue.add(word)
        revealed = false
        library.answerReview(word.word, correct)
    }

    ScreenScaffold(title = stringResource(R.string.review_title), onBack = onBack) { modifier ->
        val current = queue.firstOrNull()
        if (current == null) {
            ReviewDone(reviewed = reviewed, onBack = onBack, modifier = modifier)
        } else {
            ReviewCard(
                word = current,
                progress = if (total == 0) 0f else reviewed / total.toFloat(),
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
    reviewed: Int,
    onBack: () -> Unit,
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
            Text(stringResource(R.string.review_done), style = MaterialTheme.typography.headlineMedium)
            Text(pluralStringResource(R.plurals.reviewed_count, reviewed, reviewed))
            Button(onClick = onBack) { Text(stringResource(R.string.done)) }
        }
    }
}

@Composable
private fun ReviewCard(
    word: VocabWord,
    progress: Float,
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
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
            // The default lavender track is ≈1.2:1 on paper; the outline colour stays visible at 0%.
            trackColor = MaterialTheme.colorScheme.outline,
        )
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
                            Text(word.word, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(
                                onClick = { container.speaker.speak(word.word, container.library.state.value.settings.speechRate) },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.listen_word, word.word),
                                )
                            }
                        }
                        if (word.context.isNotEmpty()) {
                            Text(word.context, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
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
                                    if (word.phonetic.isNotEmpty()) Text(word.phonetic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        word.translation.ifEmpty { stringResource(R.string.no_translation) },
                                        style = MaterialTheme.typography.headlineSmall,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                FilledTonalButton(onClick = onReveal) { Text(stringResource(R.string.show_meaning)) }
                            }
                        }
                    }
                }
            }
        }
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
            ) { Text(stringResource(R.string.i_know_it), textAlign = TextAlign.Center) }
        }
    }
}
