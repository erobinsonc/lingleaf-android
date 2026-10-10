@file:OptIn(ExperimentalFoundationApi::class)

package app.lingleaf.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.Book
import app.lingleaf.core.GutenbergClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val state by container.library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<Book?>(null) }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val message =
                    withContext(container.io) {
                        runCatching {
                            val resolver = context.contentResolver
                            val name =
                                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                } ?: "Book"
                            val raw = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                            val text = GutenbergClient.stripBoilerplate(raw)
                            require(text.isNotBlank())
                            val title = name.removeSuffix(".txt").removeSuffix(".TXT")
                            container.library.addBook(title, "—", text)
                            context.getString(R.string.imported, title)
                        }.getOrElse { context.getString(R.string.import_failed) }
                    }
                snackbar.showSnackbar(message)
            }
        }

    ScreenScaffold(
        title = stringResource(R.string.library_title),
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { importLauncher.launch(arrayOf("text/plain")) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.import_txt))
            }
        },
    ) { modifier ->
        LazyColumn(modifier) {
            item { DailyGoalCard() }
            state.lastOpened?.let { current ->
                item { SectionHeader(stringResource(R.string.continue_reading)) }
                item(key = "current") { BookRow(current, Modifier.combinedClickable(onClick = { onOpenBook(current.id) })) }
            }
            item { SectionHeader(stringResource(R.string.your_books)) }
            if (state.books.isEmpty()) {
                item { Text(stringResource(R.string.library_empty), Modifier.padding(24.dp)) }
            }
            items(state.books.asReversed(), key = { it.id }) { book ->
                BookRow(
                    book,
                    Modifier.combinedClickable(
                        onClick = { onOpenBook(book.id) },
                        onLongClick = { toDelete = book },
                        onLongClickLabel = stringResource(R.string.delete),
                    ),
                )
            }
            if (state.books.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.long_press_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    toDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_book_title)) },
            text = { Text(stringResource(R.string.delete_book_message, book.title)) },
            confirmButton = {
                Button(onClick = {
                    toDelete = null
                    scope.launch { withContext(container.io) { container.library.deleteBook(book.id) } }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
