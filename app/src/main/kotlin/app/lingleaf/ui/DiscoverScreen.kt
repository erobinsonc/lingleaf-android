package app.lingleaf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.GutenbergBook
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.GutenbergException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A download in progress (0…1, with its job to cancel) or why it failed. */
private sealed interface Download {
    data class Running(
        val fraction: Float,
        val job: Job,
    ) : Download

    data class Failed(
        val reason: GutenbergException.Reason,
    ) : Download
}

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
    val downloads = remember { mutableStateMapOf<Int, Download>() }

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

    fun download(book: GutenbergBook) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    withContext(container.io) {
                        val text =
                            container.gutenberg.downloadText(book) { fraction ->
                                scope.launch {
                                    val current = downloads[book.id]
                                    if (current is Download.Running) downloads[book.id] = current.copy(fraction = fraction.toFloat())
                                }
                            }
                        container.library.addBook(
                            title = book.displayTitle,
                            author = book.authorName ?: context.getString(R.string.unknown_author),
                            text = text,
                            level = GutenbergClient.levelFor(book.id),
                            gutenbergId = book.id,
                        )
                    }
                    downloads.remove(book.id)
                } catch (error: GutenbergException) {
                    downloads[book.id] = Download.Failed(error.reason)
                } catch (cancelled: CancellationException) {
                    downloads.remove(book.id)
                    throw cancelled
                }
            }
        downloads[book.id] = Download.Running(0f, job)
        job.start()
    }

    val recommended = GutenbergClient.filter(GutenbergClient.RECOMMENDED_BOOKS, levelFilter)
    val levels = remember { GutenbergClient.RECOMMENDED.values.distinct() }

    ScreenScaffold(title = stringResource(R.string.discover_title)) { modifier ->
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
            searchError?.let { reason ->
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                item { SectionHeader(stringResource(R.string.results)) }
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
                Text(
                    stringResource(R.string.gutenberg_credit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
    download: Download?,
    onGet: (GutenbergBook) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val failure = (download as? Download.Failed)?.reason
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { BookCover(book.displayTitle) },
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
                failure?.let { Text(stringResource(downloadMessage(it))) }
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
                download is Download.Running -> {
                    val label = stringResource(R.string.downloading_book, book.displayTitle)
                    val cancel = stringResource(R.string.cancel_download)
                    IconButton(
                        onClick = { download.job.cancel() },
                        modifier =
                            Modifier.semantics {
                                contentDescription = label
                                onClick(label = cancel) {
                                    download.job.cancel()
                                    true
                                }
                            },
                    ) { CircularProgressIndicator(progress = { download.fraction }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
                }
                book.textUrl == null -> Text(stringResource(R.string.not_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                failure == GutenbergException.Reason.UNAVAILABLE -> Unit
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
