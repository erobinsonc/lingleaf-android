package app.lingleaf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.BookDownload
import app.lingleaf.R
import app.lingleaf.core.GutenbergBook
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.GutenbergException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recommended classics (shipped with the app) and search of Project Gutenberg, with a
 * download that shows its progress, can be cancelled, and says why it failed (SPEC S9, S12).
 */
@Composable
fun DiscoverScreen(onOpenBook: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val state by container.library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var levelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<GutenbergBook>?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searching by remember { mutableStateOf(false) }
    // The search has taken over 8 s: say Project Gutenberg can be slow, and offer Cancel.
    var slow by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<GutenbergException.Reason?>(null) }
    val downloads by container.downloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        searching = false
        slow = false
        searchError = null
        results = null
    }

    // A new search cancels the one still running.
    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        searchJob?.cancel()
        searching = true
        slow = false
        searchError = null
        results = null
        searchJob =
            scope.launch {
                val timer =
                    launch {
                        delay(8_000)
                        slow = true
                    }
                try {
                    results = withContext(container.io) { container.gutenberg.search(text) }
                } catch (error: GutenbergException) {
                    searchError = error.reason
                } finally {
                    timer.cancel()
                    if (isActive) searching = false
                }
            }
    }

    // When a download finishes, say so: the Read button alone changes nothing a screen reader hears.
    val unknownAuthor = stringResource(R.string.unknown_author)
    val previous = remember { mutableMapOf<Int, BookDownload>() }
    LaunchedEffect(downloads) {
        for ((id, before) in previous) {
            if (before is BookDownload.Running && id !in downloads && state.books.any { it.gutenbergId == id }) {
                val title =
                    GutenbergClient.RECOMMENDED_BOOKS.firstOrNull { it.id == id }?.displayTitle
                        ?: results?.firstOrNull { it.id == id }?.displayTitle
                if (title != null) snackbar.showSnackbar(context.getString(R.string.downloaded, title))
            }
        }
        previous.clear()
        previous.putAll(downloads)
    }

    fun download(book: GutenbergBook) = container.download(book, unknownAuthor)

    val recommended = GutenbergClient.filter(GutenbergClient.RECOMMENDED_BOOKS, levelFilter)
    val levels = GutenbergClient.FILTER_LEVELS

    ScreenScaffold(title = stringResource(R.string.discover_title), snackbarHostState = snackbar) { modifier ->
        LazyColumn(modifier) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.isEmpty()) cancelSearch()
                    },
                    label = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                cancelSearch()
                            }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search)) }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (searching) item { SearchProgress(slow, onCancel = ::cancelSearch) }
            // Results and errors appear without moving focus: polite live regions say them.
            searchError?.let { reason ->
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            stringResource(
                                if (reason ==
                                    GutenbergException.Reason.OFFLINE
                                ) {
                                    R.string.search_offline
                                } else {
                                    R.string.gutenberg_no_answer
                                },
                            ),
                        )
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(onClick = ::search) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            results?.let { found ->
                item {
                    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { SectionHeader(stringResource(R.string.results)) }
                }
                if (found.isEmpty()) {
                    item { Text(stringResource(R.string.no_results), Modifier.padding(24.dp)) }
                } else {
                    items(found, key = { "result-${it.id}" }) { book ->
                        BookItem(
                            book,
                            state.books.firstOrNull {
                                it.gutenbergId == book.id
                            }?.id,
                            downloads[book.id],
                            ::download,
                            onOpenBook,
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.recommended)) }
            item { LevelFilter(levels, levelFilter) { levelFilter = it } }
            if (recommended.isEmpty()) {
                item { Text(stringResource(R.string.no_books_at_level), Modifier.padding(24.dp)) }
            } else {
                items(recommended, key = { it.id }) { book ->
                    BookItem(book, state.books.firstOrNull { it.gutenbergId == book.id }?.id, downloads[book.id], ::download, onOpenBook)
                }
            }
            item {
                // Project Gutenberg vouches only for the US; the learner checks the rest.
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.gutenberg_credit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.public_domain_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "Searching Project Gutenberg…", after 8 s "Still searching…" with Cancel. */
@Composable
private fun SearchProgress(
    slow: Boolean,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.searching_gutenberg))
            if (slow) {
                Text(stringResource(R.string.still_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (slow) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** A book row: Get → a progress ring (tap to cancel) → Read; why a download failed, with Retry when it may pass. */
@Composable
private fun BookItem(
    book: GutenbergBook,
    savedId: String?,
    download: BookDownload?,
    onGet: (GutenbergBook) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val container = LocalContainer.current
    val failure = (download as? BookDownload.Failed)?.reason
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Cover(book) },
        headlineContent = { Text(book.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                    GutenbergClient.levelFor(book.id)?.let {
                        LevelBadge(it)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(book.authorName ?: stringResource(R.string.unknown_author), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Polite live region: TalkBack reads why the download failed.
                failure?.let { Text(stringResource(downloadMessage(it)), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        trailingContent = {
            when {
                savedId != null -> {
                    val label = stringResource(R.string.read_book, book.displayTitle)
                    Button(onClick = { onOpenBook(savedId) }, modifier = Modifier.semantics { contentDescription = label }) {
                        Text(stringResource(R.string.read))
                    }
                }
                download is BookDownload.Running -> {
                    val label = stringResource(R.string.downloading_book, book.displayTitle)
                    val cancel = stringResource(R.string.cancel_download)
                    IconButton(
                        onClick = { container.cancelDownload(book.id) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = label
                                onClick(label = cancel) {
                                    container.cancelDownload(book.id)
                                    true
                                }
                            },
                    ) { CircularProgressIndicator(progress = { download.fraction }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
                }
                book.textUrl == null -> Text(stringResource(R.string.not_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    // Visible text stays short; screen readers hear which book it gets.
                    val label = stringResource(if (failure == null) R.string.get_book else R.string.retry_book, book.displayTitle)
                    FilledTonalButton(onClick = { onGet(book) }, modifier = Modifier.semantics { contentDescription = label }) {
                        Text(stringResource(if (failure == null) R.string.get else R.string.retry))
                    }
                }
            }
        },
    )
}

@StringRes
private fun downloadMessage(reason: GutenbergException.Reason) =
    when (reason) {
        GutenbergException.Reason.UNAVAILABLE -> R.string.book_unavailable
        GutenbergException.Reason.OFFLINE -> R.string.download_offline
        GutenbergException.Reason.TIMED_OUT, GutenbergException.Reason.FAILED -> R.string.gutenberg_no_answer
    }

/** Single-choice CEFR level chips: "All" plus the [levels] present in the recommended list. */
@Composable
private fun LevelFilter(
    levels: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf<String?>(null) + levels).forEach { level ->
            val description = if (level == null) stringResource(R.string.level_all_description) else stringResource(R.string.level, level)
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level ?: stringResource(R.string.level_all)) },
                modifier =
                    Modifier
                        .semantics { contentDescription = description }
                        .testTag("level-filter-${level ?: "all"}"),
            )
        }
    }
}

/**
 * The book's cover from Project Gutenberg, decorative (the row says the title and author);
 * the drawn cover shows while it loads, offline, or when the container loads none.
 */
@Composable
private fun Cover(book: GutenbergBook) {
    val container = LocalContainer.current
    val url = book.coverUrl
    // What produceState does, written out: its lint check doesn't see this assignment.
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        val load = container.covers
        // Null (the drawn cover) without a cover, in UI tests or when loading fails.
        image = if (url != null && load != null) load(url) else null
    }
    val loaded = image
    if (loaded == null) {
        BookCover(book.displayTitle)
    } else {
        Image(
            loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(width = 44.dp, height = 62.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clearAndSetSemantics {},
        )
    }
}
