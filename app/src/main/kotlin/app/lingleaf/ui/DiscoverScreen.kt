package app.lingleaf.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.GutenbergBook
import app.lingleaf.core.GutenbergClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search and download free public-domain books from Project Gutenberg. */
@Composable
fun DiscoverScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val state by container.library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var levelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var recommended by remember { mutableStateOf<List<GutenbergBook>>(emptyList()) }
    var results by remember { mutableStateOf<List<GutenbergBook>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val downloading = remember { mutableStateListOf<Int>() }
    val downloaded = state.books.mapNotNull { it.gutenbergId }.toSet()

    fun load(search: String?) {
        scope.launch {
            loading = true
            failed = false
            runCatching {
                withContext(container.io) {
                    if (search == null) container.gutenberg.fetchRecommended() else container.gutenberg.search(search)
                }
            }.onSuccess { if (search == null) recommended = it else results = it }
                .onFailure { failed = true }
            loading = false
        }
    }

    fun download(book: GutenbergBook) {
        scope.launch {
            downloading += book.id
            val message =
                runCatching {
                    withContext(container.io) {
                        val text = container.gutenberg.downloadText(book)
                        container.library.addBook(
                            title = book.displayTitle,
                            author = book.authorName ?: context.getString(R.string.unknown_author),
                            text = text,
                            level = GutenbergClient.levelFor(book.id),
                            gutenbergId = book.id,
                        )
                    }
                }.fold(
                    onSuccess = { context.getString(R.string.downloaded, book.displayTitle) },
                    onFailure = { context.getString(R.string.download_failed, book.displayTitle) },
                )
            downloading -= book.id
            snackbar.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) { if (recommended.isEmpty()) load(null) }

    val searching = results != null
    val levels = remember(recommended) { recommended.mapNotNull { GutenbergClient.levelFor(it.id) }.distinct() }
    val selectedLevel = levelFilter?.takeIf { it in levels }
    val books = results ?: GutenbergClient.filter(recommended, selectedLevel)

    val searchHint = stringResource(R.string.search_hint)

    ScreenScaffold(title = stringResource(R.string.discover_title), snackbarHostState = snackbar) { modifier ->
        LazyColumn(modifier) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                results = null
                            }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search)) }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) load(query.trim()) }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics { contentDescription = searchHint },
                )
            }
            item { SectionHeader(stringResource(if (searching) R.string.results else R.string.recommended)) }
            if (!searching && !loading && !failed && levels.isNotEmpty()) {
                item { LevelFilter(levels, selectedLevel) { levelFilter = it } }
            }
            when {
                loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                failed ->
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.catalog_failed))
                            Spacer(Modifier.size(12.dp))
                            FilledTonalButton(
                                onClick = { load(if (searching) query.trim() else null) },
                            ) { Text(stringResource(R.string.retry)) }
                        }
                    }
                searching && books.isEmpty() -> item { Text(stringResource(R.string.no_results), Modifier.padding(24.dp)) }
                selectedLevel != null && books.isEmpty() ->
                    item { Text(stringResource(R.string.no_books_at_level), Modifier.padding(24.dp)) }
                else ->
                    items(books, key = { it.id }) { book ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { BookCover(book.displayTitle) },
                            headlineContent = { Text(book.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                                    GutenbergClient.levelFor(book.id)?.let {
                                        LevelBadge(it)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        book.authorName ?: stringResource(R.string.unknown_author),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            trailingContent = {
                                when {
                                    book.id in downloaded ->
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = stringResource(R.string.in_library),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    book.id in downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else ->
                                        FilledTonalButton(onClick = { download(book) }, enabled = book.textUrl != null) {
                                            Text(stringResource(R.string.get))
                                        }
                                }
                            },
                        )
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
