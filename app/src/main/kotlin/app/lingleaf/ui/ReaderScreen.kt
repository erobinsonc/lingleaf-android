package app.lingleaf.ui

import android.provider.Settings.Global
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Remove
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
import app.lingleaf.core.ReadAloud
import app.lingleaf.core.SentencePosition
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
    // Reading aloud sentence by sentence (SPEC S6); reading moves the saved position.
    val reading =
        remember(bookId) {
            ReadAloudController(container.speaker, { library.state.value.settings.speechRate }) {
                library.updateProgress(bookId, it.paragraph)
            }
        }
    // Hoisted so Read aloud starts at the paragraph on screen, not the last saved one.
    var listState by remember { mutableStateOf<LazyListState?>(null) }
    var following by remember { mutableStateOf(true) }

    fun readFrom(
        paragraph: Int,
        offset: Int,
    ) {
        val loaded = paragraphs ?: return
        val start = ReadAloud.start(loaded, paragraph, offset) ?: return
        following = true
        reading.read(loaded, start)
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
            // No reading aloud without the text on screen.
            reading.pause()
            container.speaker.stop()
        }
    }

    ScreenScaffold(
        title = book?.title.orEmpty(),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = {
                val loaded = paragraphs
                val visible = listState?.layoutInfo?.visibleItemsInfo?.map { it.index }.orEmpty()
                val paused = reading.current
                when {
                    reading.isReading -> reading.pause()
                    loaded.isNullOrEmpty() -> Unit
                    // From the paused sentence when it is on screen, else from the top of the screen.
                    paused != null && paused.paragraph in visible -> {
                        following = true
                        reading.read(loaded, paused)
                    }
                    else -> readFrom(listState?.firstVisibleItemIndex ?: book?.progressParagraph ?: 0, 0)
                }
            }) {
                if (reading.isReading) {
                    Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.pause_reading))
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
                        reading = reading.current,
                        following = following,
                        onFollowingChange = { following = it },
                        onListState = { listState = it },
                        onScroll = { library.updateProgress(bookId, it) },
                        onWordTap = { index, word ->
                            // Tapping a word pauses reading aloud: its sheet has its own Listen.
                            reading.pause()
                            selection = Selection(index, word)
                        },
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
            onReadFromHere = {
                selection = null
                readFrom(current.paragraph, current.word.start)
            },
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
    reading: SentencePosition?,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    onListState: (LazyListState) -> Unit,
    onScroll: (Int) -> Unit,
    onWordTap: (Int, WordRange) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialParagraph)
    LaunchedEffect(listState) { onListState(listState) }
    val animate = Global.getFloat(LocalContext.current.contentResolver, Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    // Scrolling by hand while reading aloud: stop following until the sentence is back on screen.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) onFollowingChange(false) }
    }

    // Keep the sentence being read on screen: when it starts below 85 % of the reader or above
    // it, scroll it to the upper third (estimated from its offset, as for a tapped word).
    LaunchedEffect(reading, following) {
        val sentence = reading ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == sentence.paragraph }
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val length = paragraphs[sentence.paragraph].length.coerceAtLeast(1)
        val sentenceY = item?.let { it.offset + it.size * sentence.range.start / length }
        val onScreen = sentenceY != null && sentenceY in 0..(viewport * 85 / 100)
        if (!following) {
            if (onScreen) onFollowingChange(true)
            return@LaunchedEffect
        }
        if (onScreen) return@LaunchedEffect
        val offset = item?.let { (it.size * sentence.range.start / length - viewport / 3).coerceAtLeast(0) } ?: 0
        if (animate) listState.animateScrollToItem(sentence.paragraph, offset) else listState.scrollToItem(sentence.paragraph, offset)
    }
    val context = LocalContext.current
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
        if (Global.getFloat(context.contentResolver, Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
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
                    reading = reading?.takeIf { it.paragraph == index }?.range,
                    onWordTap = { onWordTap(index, it) },
                    modifier = Modifier.padding(bottom = (fontSize * 0.9f).dp),
                )
            }
        }
    }
}
