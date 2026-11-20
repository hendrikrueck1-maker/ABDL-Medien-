package de.hr.mediathek.feature.stories

import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.core.ui.theme.LiterataFamily
import de.hr.mediathek.core.ui.theme.ReaderPage
import de.hr.mediathek.data.story.ChapterDetector
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Farben der Leseseite: Fläche, Text, Akzent (Fortschritt) und Sternfarbe, passend zu Nacht, Sepia oder Hell. */
internal class PageColors(val bg: Color, val fg: Color, val accent: Color, val star: Color)

@Composable
internal fun pageColors(theme: ReaderTheme): PageColors {
    val c = LocalAppColors.current
    return when (theme) {
        ReaderTheme.DARK -> PageColors(ReaderPage.night.first, ReaderPage.night.second, c.rosa, c.gold)
        ReaderTheme.SEPIA -> PageColors(ReaderPage.sepia.first, ReaderPage.sepia.second, Color(0xFFB3306B), Color(0xFF9A6A00))
        ReaderTheme.LIGHT -> PageColors(ReaderPage.light.first, ReaderPage.light.second, Color(0xFFB3306B), Color(0xFF9A6A00))
    }
}

/** Fünf Sterne in den Farben der Leseseite. */
@Composable
internal fun PageStars(rating: Int, page: PageColors, onRate: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onRate(i) }
                    .semantics { contentDescription = "$i Sterne" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (i <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = if (i <= rating) page.star else page.fg.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint) }
}

@Composable
private fun HeaderText(text: String, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
}

/** Die Leseseite als Karte: Kopf, Serie, Bewertung, Kapitel, Text in Literata und Fortschrittsbalken unten. */
@OptIn(FlowPreview::class)
@Composable
internal fun ReadingCard(
    viewModel: StoryReaderViewModel,
    state: ReaderUiState,
    settings: ReaderSettings,
    page: PageColors,
    wide: Boolean,
    textOnly: Boolean,
    onToggleImages: () -> Unit,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenStory: (String) -> Unit,
    onEditSeries: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val fg = page.fg
    val story = state.story
    Column(modifier.clip(RoundedCornerShape(32.dp)).background(page.bg)) {
        if (!state.loaded || story == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.player_loading), color = fg) }
            return@Column
        }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        var autoScroll by remember { mutableStateOf(false) }
        var showChapters by remember { mutableStateOf(false) }
        var showBookmarks by remember { mutableStateOf(false) }
        var showParts by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        val chapters = remember(state.paragraphs) { ChapterDetector.detect(state.paragraphs) }
        val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
        val tags by viewModel.tags.collectAsStateWithLifecycle()
        val series by viewModel.series.collectAsStateWithLifecycle()

        val listState = rememberLazyListState(story.readingParagraph, story.readingOffset)
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .debounce(800)
                .collect { (i, o) -> viewModel.saveProgress(i, o) }
        }
        DisposableEffect(listState) {
            onDispose { viewModel.saveProgress(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
        }
        val dragged by listState.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(dragged) { if (dragged) autoScroll = false }
        LaunchedEffect(autoScroll, settings.scrollSpeed) {
            if (autoScroll) {
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = (now - last) / 1_000_000_000f
                    last = now
                    listState.scrollBy(settings.scrollSpeed * density * dt)
                    if (!listState.canScrollForward) {
                        autoScroll = false
                        break
                    }
                }
            }
        }
        val readFraction by remember(state.paragraphs.size) {
            derivedStateOf { if (state.paragraphs.size <= 1) 0f else listState.firstVisibleItemIndex.toFloat() / (state.paragraphs.size - 1) }
        }
        val currentChapter by remember(chapters) {
            derivedStateOf { chapters.indexOfLast { it.paragraphIndex <= listState.firstVisibleItemIndex }.coerceAtLeast(0) }
        }
        val percent = (readFraction.coerceIn(0f, 1f) * 100).toInt()
        val family: FontFamily = when (settings.font) {
            ReaderFont.SANS -> MaterialTheme.typography.bodyLarge.fontFamily ?: FontFamily.SansSerif
            ReaderFont.SERIF -> LiterataFamily
            ReaderFont.MONO -> FontFamily.Monospace
        }

        // Kopf
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.diag_back), fg, onBack)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(story.title, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (chapters.size >= 2) stringResource(R.string.reader_progress, currentChapter + 1, chapters.size, percent)
                    else stringResource(R.string.reader_progress_plain, percent),
                    color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (wide) {
                HeaderText("A−", stringResource(R.string.reader_font_smaller), fg) {
                    viewModel.setSettings(settings.copy(fontSize = (settings.fontSize - 1f).coerceAtLeast(14f)))
                }
                HeaderText("A+", stringResource(R.string.reader_font_larger), fg) {
                    viewModel.setSettings(settings.copy(fontSize = (settings.fontSize + 1f).coerceAtMost(32f)))
                }
            }
            HeaderIcon(
                if (story.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(R.string.mark_fav_long),
                if (story.favorite) page.accent else fg,
            ) { viewModel.toggleFavorite() }
            Box {
                HeaderIcon(Icons.Filled.MoreVert, stringResource(R.string.reader_more), fg) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (chapters.size >= 2) DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_chapters)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null) },
                        onClick = { menu = false; showChapters = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_bookmarks)) },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, null) },
                        onClick = { menu = false; showBookmarks = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (autoScroll) R.string.reader_autoscroll_off else R.string.reader_autoscroll_on)) },
                        leadingIcon = { Icon(if (autoScroll) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) },
                        onClick = { menu = false; autoScroll = !autoScroll },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (textOnly) R.string.reader_show_images else R.string.reader_only_text)) },
                        leadingIcon = { Icon(Icons.Filled.Image, null) },
                        onClick = { menu = false; onToggleImages() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_continue)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, tint = if (story.continueFlag) c.mint else LocalContentColorFallback()) },
                        onClick = { menu = false; viewModel.toggleContinue() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_settings)) },
                        leadingIcon = { Icon(Icons.Filled.TextFields, null) },
                        onClick = { menu = false; onOpenSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_open_editor)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onEdit(story.uuid) },
                    )
                }
            }
        }

        // Serie, Angaben und Bewertung
        series?.let { sr ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenStory(sr.parts[sr.index - 1].uuid) }, enabled = sr.index > 0) { Text("◀", color = fg) }
                Text(
                    "${sr.name} · Teil ${sr.index + 1} von ${sr.parts.size}",
                    color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    modifier = Modifier.weight(1f).clickable { showParts = true }.padding(8.dp),
                )
                TextButton(onClick = { onOpenStory(sr.parts[sr.index + 1].uuid) }, enabled = sr.index < sr.parts.lastIndex) { Text("▶", color = fg) }
            }
            if (showParts) {
                AlertDialog(
                    onDismissRequest = { showParts = false },
                    containerColor = c.bar, shape = RoundedCornerShape(30.dp),
                    confirmButton = { TextButton(onClick = { showParts = false }) { Text("Schließen") } },
                    dismissButton = { TextButton(onClick = { showParts = false; story.seriesUuid?.let(onEditSeries) }) { Text("✎ Serie bearbeiten") } },
                    title = { Text(sr.name) },
                    text = {
                        LazyColumn {
                            itemsIndexed(sr.parts) { i, part ->
                                Text(
                                    (if (i == sr.index) "▶ " else "· ") + part.title,
                                    fontWeight = if (i == sr.index) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth().clickable { showParts = false; if (i != sr.index) onOpenStory(part.uuid) }
                                        .defaultMinSize(minHeight = 48.dp).padding(vertical = 10.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        val info = listOfNotNull(
            story.author?.let { "von $it" },
            story.status?.takeIf { it != "published" }?.let { statusLabel(it) },
            tags.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "#$it" },
        ).joinToString("  ·  ")
        if (info.isNotEmpty()) {
            Text(info, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PageStars(story.rating, page, viewModel::setRating)
            if (story.continueFlag) {
                Text("✏ weiterschreiben", color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }

        // Kapitelzeile
        if (chapters.size >= 2) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val inside = listState.firstVisibleItemIndex > chapters[currentChapter].paragraphIndex
                    val target = if (inside) currentChapter else (currentChapter - 1).coerceAtLeast(0)
                    scope.launch { listState.animateScrollToItem(chapters[target].paragraphIndex) }
                }) { Text("◀", color = fg) }
                Text(
                    chapters[currentChapter].title, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable { showChapters = true },
                )
                TextButton(
                    onClick = { scope.launch { listState.animateScrollToItem(chapters[(currentChapter + 1).coerceAtMost(chapters.size - 1)].paragraphIndex) } },
                    enabled = currentChapter < chapters.size - 1,
                ) { Text("▶", color = fg) }
            }
        }

        // Text
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy((settings.fontSize * 0.7f).dp),
        ) {
            itemsIndexed(state.paragraphs) { index, p ->
                if (ChapterDetector.isHeading(p, chapters, index)) {
                    Text(
                        p.trim().trimStart('#', '*', '=', ' ').trimEnd('*', '=', ' '),
                        color = fg, fontFamily = family, fontWeight = FontWeight.Bold,
                        fontSize = (settings.fontSize + 5f).sp,
                        modifier = Modifier.padding(top = (settings.fontSize * 1.2f).dp),
                    )
                } else {
                    Text(p, color = fg, fontFamily = family, fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineSpacing).sp)
                }
            }
        }
        // Fortschritt
        LinearProgressIndicator(
            progress = { readFraction.coerceIn(0f, 1f) },
            color = page.accent, trackColor = fg.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )

        if (showBookmarks) {
            AlertDialog(
                onDismissRequest = { showBookmarks = false },
                containerColor = c.bar, shape = RoundedCornerShape(30.dp),
                confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Schließen") } },
                title = { Text(stringResource(R.string.reader_bookmarks)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { viewModel.addBookmark(listState.firstVisibleItemIndex) }) { Text("🔖 Hier ein Lesezeichen setzen") }
                        if (bookmarks.isEmpty()) Text("Noch keine Lesezeichen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn {
                            items(bookmarks, key = { it.uuid }) { b ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Absatz ${b.paragraph + 1}: ${b.label}",
                                        maxLines = 2,
                                        modifier = Modifier.weight(1f).clickable {
                                            showBookmarks = false
                                            scope.launch { listState.scrollToItem(b.paragraph) }
                                        }.defaultMinSize(minHeight = 48.dp).padding(vertical = 10.dp),
                                    )
                                    TextButton(onClick = { viewModel.deleteBookmark(b.uuid) }) { Text("✕") }
                                }
                            }
                        }
                    }
                },
            )
        }
        if (showChapters) {
            AlertDialog(
                onDismissRequest = { showChapters = false },
                containerColor = c.bar, shape = RoundedCornerShape(30.dp),
                confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Schließen") } },
                title = { Text("Kapitel (${chapters.size})") },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(chapters) { i, chapter ->
                            val isCurrent = i == currentChapter
                            val done = i < currentChapter
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        showChapters = false
                                        scope.launch { listState.animateScrollToItem(chapter.paragraphIndex) }
                                    }
                                    .defaultMinSize(minHeight = 48.dp)
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (done) "✓" else if (isCurrent) "▶" else "·",
                                    color = if (isCurrent) c.rosa else c.onCardVariant,
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                                Text(
                                    chapter.title, maxLines = 2,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) c.rosa else c.onCard,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun LocalContentColorFallback(): Color = LocalAppColors.current.onCardVariant

internal fun statusLabel(status: String): String = when (status) {
    "draft" -> "Entwurf"
    else -> status
}
