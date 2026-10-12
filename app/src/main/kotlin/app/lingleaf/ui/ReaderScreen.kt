package app.lingleaf.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.FunctionWords
import app.lingleaf.core.TextTools
import app.lingleaf.core.WordRange
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Selection(val paragraph: Int, val word: WordRange)

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val container = LocalContainer.current
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val book = state.books.firstOrNull { it.id == bookId }
    val speaking by container.speaker.isSpeaking.collectAsStateWithLifecycle()
    var paragraphs by remember { mutableStateOf<List<String>?>(null) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    val fontSize = state.settings.fontSize
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 4 s, longer if the system asks for more time to act; with a screen reader it stays (SPEC S4, S5).
    val undoTimeout = rememberUndoTimeout(baseMillis = 4_000L)

    LaunchedEffect(bookId) {
        paragraphs = withContext(container.io) { library.openBook(bookId) }
    }

    // Count reading time while the reader is on screen and the app is in front.
    LifecycleResumeEffect(bookId) {
        val start = System.currentTimeMillis()
        onPauseOrDispose {
            val seconds = (System.currentTimeMillis() - start) / 1000
            container.appScope.launch {
                library.addReadingTime(seconds)
                library.flush()
            }
            container.speaker.stop()
        }
    }

    ScreenScaffold(
        title = book?.title.orEmpty(),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = {
                if (speaking) {
                    container.speaker.stop()
                } else {
                    paragraphs?.getOrNull(book?.progressParagraph ?: 0)?.let { container.speaker.speak(it, state.settings.speechRate) }
                }
            }) {
                if (speaking) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop_reading))
                } else {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.read_aloud))
                }
            }
            IconButton(
                onClick = { library.updateSettings { it.copy(fontSize = (it.fontSize - 2).coerceAtLeast(14f)) } },
                enabled = fontSize > 14f,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.smaller_text))
            }
            IconButton(
                onClick = { library.updateSettings { it.copy(fontSize = (it.fontSize + 2).coerceAtMost(34f)) } },
                enabled = fontSize < 34f,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.larger_text))
            }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            val progressLabel = stringResource(R.string.reading_progress)
            LinearProgressIndicator(
                progress = { book?.progress ?: 0f },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
                // The default lavender track is ≈1.2:1 on paper; the outline colour stays visible at 0%.
                trackColor = MaterialTheme.colorScheme.outline,
            )
            val loaded = paragraphs
            when {
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                loaded.isEmpty() ->
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.book_empty)) }
                else ->
                    ReaderText(
                        paragraphs = loaded,
                        initialParagraph = (book?.progressParagraph ?: 0).coerceIn(0, loaded.size - 1),
                        fontSize = fontSize,
                        isLearning = state::isLearning,
                        selection = selection,
                        onScroll = { library.updateProgress(bookId, it) },
                        onWordTap = { index, word -> selection = Selection(index, word) },
                    )
            }
        }
    }

    selection?.let { current ->
        val paragraph = paragraphs?.getOrNull(current.paragraph) ?: return@let
        val sentenceRange = TextTools.sentenceRangeAt(paragraph, current.word.start)
        val sentence = paragraph.substring(sentenceRange.start, sentenceRange.end)
        val inSentence =
            WordRange(current.word.start - sentenceRange.start, current.word.end - sentenceRange.start)
                .takeIf { current.word.start >= sentenceRange.start && current.word.end <= sentenceRange.end }
        WordSheet(
            word = paragraph.substring(current.word.start, current.word.end),
            sentence = sentence,
            rangeInSentence = inSentence,
            function = inSentence?.let { FunctionWords.analyze(sentence, it) },
            source = book?.title.orEmpty(),
            onChanged = { change ->
                scope.launch {
                    snackbar.showUndo(context.getString(change.message, change.label), context.getString(R.string.undo), undoTimeout()) {
                        withContext(container.io) { library.restoreWord(change.word, change.previous) }
                    }
                }
            },
            onDismiss = { selection = null },
        )
    }
}

@Composable
private fun ReaderText(
    paragraphs: List<String>,
    initialParagraph: Int,
    fontSize: Float,
    isLearning: (String) -> Boolean,
    selection: Selection?,
    onScroll: (Int) -> Unit,
    onWordTap: (Int, WordRange) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialParagraph)
    val words = remember(paragraphs) { paragraphs.map(TextTools::wordRanges) }
    val style =
        TextStyle(
            fontFamily = ReadingFont,
            fontSize = fontSize.sp,
            lineHeight = 1.6.em,
            color = MaterialTheme.colorScheme.onSurface,
        )

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onScroll(it) }
    }

    // The sheet covers the lower part of the reader: when the tapped word is there, scroll it
    // near the top (SPEC S17). Its height in the paragraph is estimated from its offset.
    LaunchedEffect(selection) {
        val selected = selection ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected.paragraph } ?: return@LaunchedEffect
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val length = paragraphs[selected.paragraph].length.coerceAtLeast(1)
        val wordY = item.offset + item.size * selected.word.start / length
        if (wordY in 0..(viewport * 2 / 5)) return@LaunchedEffect
        val offset = (item.size * selected.word.start / length - viewport / 5).coerceAtLeast(0)
        // No animation when the system turns animations off (Remove animations).
        if (ValueAnimator.areAnimatorsEnabled()) {
            listState.animateScrollToItem(selected.paragraph, offset)
        } else {
            listState.scrollToItem(selected.paragraph, offset)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            modifier =
                Modifier
                    .widthIn(max = 680.dp)
                    .navigationBarsPadding(),
        ) {
            itemsIndexed(paragraphs) { index, paragraph ->
                TappableParagraph(
                    text = paragraph,
                    words = words[index],
                    style = style,
                    isLearning = isLearning,
                    selected = selection?.takeIf { it.paragraph == index }?.word,
                    onWordTap = { onWordTap(index, it) },
                    modifier = Modifier.padding(bottom = (fontSize * 0.9f).dp),
                )
            }
        }
    }
}
