@file:OptIn(ExperimentalMaterial3Api::class)

package app.lingleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lingleaf.R
import app.lingleaf.core.Book
import app.lingleaf.core.TextTools
import app.lingleaf.core.WordRange

/** Screen frame: top bar plus content; the bottom bar lives in [LingleafApp]. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                .semantics { heading() },
    )
}

private val coverPalette =
    listOf(Color(0xFF2E6B5E), Color(0xFF3D5A80), Color(0xFFB5651D), Color(0xFF8E3B46), Color(0xFF5B6C2E), Color(0xFF6D4C8E))

@Composable
fun BookCover(title: String) {
    val color = coverPalette[title.sumOf { it.code } % coverPalette.size]
    Box(
        Modifier
            .size(width = 44.dp, height = 62.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(title.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LevelBadge(level: String) {
    val description = stringResource(R.string.level, level)
    Text(
        level,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = description },
    )
}

@Composable
fun BookRow(
    book: Book,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { BookCover(book.title) },
        headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    book.level?.let {
                        LevelBadge(it)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (book.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                    )
                }
            }
        },
    )
}

@Composable
fun DailyGoalCard() {
    val library = LocalContainer.current.library
    val state by library.state.collectAsStateWithLifecycle()
    val goal = state.settings.dailyGoalMinutes
    val seconds = remember(state.readingLog) { library.secondsToday() }
    val streak = remember(state.readingLog, goal) { library.streak() }
    val minutes = seconds / 60
    val fraction = (seconds / (goal * 60f)).coerceIn(0f, 1f)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(64.dp), strokeWidth = 7.dp)
                Text("$minutes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (fraction >= 1f) stringResource(R.string.goal_done) else stringResource(R.string.goal_read_daily, goal),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.goal_minutes_today, minutes, goal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (streak > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Whatshot, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(pluralStringResource(R.plurals.streak_days, streak, streak), color = Color(0xFFE65100))
                    }
                }
            }
        }
    }
}

/**
 * Paragraph where every word can be tapped. One text layout per paragraph;
 * taps are hit-tested against it instead of creating a clickable per word.
 * Words being learned get a highlighter-yellow underline.
 */
@Composable
fun TappableParagraph(
    text: String,
    words: List<WordRange>,
    style: TextStyle,
    isLearning: (String) -> Boolean,
    selected: WordRange?,
    onWordTap: (WordRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val learning = remember(text, isLearning) { words.filter { isLearning(text.substring(it.start, it.end)) } }
    val markerHeight = with(LocalDensity.current) { 3.dp.toPx() }
    val annotated =
        remember(text, selected) {
            buildAnnotatedString {
                append(text)
                selected?.let { addStyle(SpanStyle(background = Brand.Marker.copy(alpha = 0.55f)), it.start, it.end) }
            }
        }
    BasicText(
        text = annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier =
            modifier
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    for (word in learning) {
                        val first = result.getBoundingBox(word.start)
                        val last = result.getBoundingBox(word.end - 1)
                        if (first.top != last.top) continue
                        val y = result.getLineBaseline(result.getLineForOffset(word.start)) + markerHeight
                        drawRoundRect(
                            color = Brand.Marker,
                            topLeft = Offset(first.left, y),
                            size = Size(last.right - first.left, markerHeight),
                            cornerRadius = CornerRadius(markerHeight / 2),
                        )
                    }
                }.pointerInput(text) {
                    detectTapGestures { position ->
                        val result = layout ?: return@detectTapGestures
                        val word = TextTools.wordAt(text, result.getOffsetForPosition(position)) ?: return@detectTapGestures
                        val first = result.getBoundingBox(word.start)
                        val last = result.getBoundingBox(word.end - 1)
                        val inside =
                            position.y in (first.top - 4f)..(first.bottom + 4f) &&
                                position.x in (minOf(first.left, last.left) - 4f)..(maxOf(first.right, last.right) + 4f)
                        if (inside) onWordTap(word)
                    }
                },
    )
}
