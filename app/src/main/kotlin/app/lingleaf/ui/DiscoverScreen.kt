package app.lingleaf.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
                            title = book.title,
                            author = book.authorName ?: context.getString(R.string.unknown_author),
                            text = text,
                            level = GutenbergClient.levelFor(book.id),
                            gutenbergId = book.id,
                        )
                    }
                }.fold(
                    onSuccess = { context.getString(R.string.downloaded, book.title) },
                    onFailure = { context.getString(R.string.download_failed, book.title) },
                )
            downloading -= book.id
            snackbar.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) { if (recommended.isEmpty()) load(null) }

    val searching = results != null
    val books = results ?: recommended

    ScreenScaffold(title = stringResource(R.string.discover_title), snackbarHostState = snackbar) { modifier ->
        LazyColumn(modifier) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_hint)) },
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
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { SectionHeader(stringResource(if (searching) R.string.results else R.string.recommended)) }
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
                else ->
                    items(books, key = { it.id }) { book ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { BookCover(book.title) },
                            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
                                    else -> {
                                        // Visible text stays short; screen readers hear which book it gets.
                                        val label = stringResource(R.string.get_book, book.title)
                                        FilledTonalButton(
                                            onClick = { download(book) },
                                            enabled = book.textUrl != null,
                                            modifier = Modifier.semantics { contentDescription = label },
                                        ) { Text(stringResource(R.string.get)) }
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
