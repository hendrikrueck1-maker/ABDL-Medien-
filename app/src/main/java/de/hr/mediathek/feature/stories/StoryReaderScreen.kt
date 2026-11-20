package de.hr.mediathek.feature.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import de.hr.mediathek.data.story.ChapterDetector
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

@OptIn(FlowPreview::class)
@Composable
fun StoryReaderScreen(onBack: () -> Unit, onEdit: (String) -> Unit, onOpenStory: (String) -> Unit = {}, viewModel: StoryReaderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val split = wide && settings.splitOn
    var rowWidthPx by remember { mutableIntStateOf(1) }
    var draftFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = draftFraction ?: settings.splitFraction
    val image by viewModel.image.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // Kapitel aus den Überschriften im Text; erst ab zwei Kapiteln gibt es die Kapitelansicht.
    val chapters = remember(state.paragraphs) { ChapterDetector.detect(state.paragraphs) }

    val (bg, fg) = when (settings.theme) {
        ReaderTheme.LIGHT -> Color(0xFFFAFAFA) to Color(0xFF1B1B1B)
        ReaderTheme.DARK -> Color(0xFF121212) to Color(0xFFE0E0E0)
        ReaderTheme.SEPIA -> Color(0xFFF4ECD8) to Color(0xFF5B4636)
    }
    val family = when (settings.font) {
        ReaderFont.SANS -> FontFamily.SansSerif
        ReaderFont.SERIF -> FontFamily.Serif
        ReaderFont.MONO -> FontFamily.Monospace
    }

    Row(Modifier.fillMaxSize().background(bg).onSizeChanged { rowWidthPx = it.width.coerceAtLeast(1) }) {
    Column(Modifier.weight(if (split) 1f - fraction else 1f).fillMaxHeight()) {
        val story = state.story
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", color = fg) }
            Text(story?.title ?: "", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            if (chapters.size >= 2) TextButton(onClick = { showChapters = true }) { Text("☰", color = fg) }
            TextButton(onClick = { showBookmarks = true }) { Text("🔖", color = fg) }
            TextButton(onClick = { autoScroll = !autoScroll }) { Text(if (autoScroll) "⏸" else "▶ Auto", color = fg) }
            if (wide) TextButton(onClick = { viewModel.setSettings(settings.copy(splitOn = !settings.splitOn)) }) { Text("🖼", color = fg.copy(alpha = if (settings.splitOn) 1f else 0.4f)) }
            TextButton(onClick = { showSettings = true }) { Text("Aa", color = fg) }
            TextButton(onClick = { story?.let { onEdit(it.uuid) } }) { Text("✏", color = fg) }
        }
        if (story != null) {
            val tags by viewModel.tags.collectAsStateWithLifecycle()
            val info = listOfNotNull(
                story.author?.let { "von $it" },
                story.status?.takeIf { it != "published" }?.let { statusLabel(it) },
                tags.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "#$it" },
            ).joinToString("  ·  ")
            val series by viewModel.series.collectAsStateWithLifecycle()
            var showParts by remember { mutableStateOf(false) }
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
                        confirmButton = { TextButton(onClick = { showParts = false }) { Text("Schließen") } },
                        title = { Text(sr.name) },
                        text = {
                            LazyColumn {
                                itemsIndexed(sr.parts) { i, part ->
                                    Text(
                                        (if (i == sr.index) "▶ " else "· ") + part.title,
                                        fontWeight = if (i == sr.index) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.fillMaxWidth().clickable { showParts = false; if (i != sr.index) onOpenStory(part.uuid) }.padding(vertical = 10.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (info.isNotEmpty()) {
                Text(info, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                StarRow(story.rating, viewModel::setRating)
                Text(
                    if (story.favorite) "♥" else "♡", color = fg, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable { viewModel.toggleFavorite() }.padding(10.dp),
                )
                Text(
                    "✏ weiterschreiben",
                    color = fg.copy(alpha = if (story.continueFlag) 1f else 0.4f),
                    modifier = Modifier.clickable { viewModel.toggleContinue() }.padding(10.dp),
                )
            }
        }
        if (state.loaded && story != null) {
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
            LinearProgressIndicator(progress = { readFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            // Welches Kapitel gerade oben im Bild steht.
            val currentChapter by remember(chapters) {
                derivedStateOf {
                    val first = listState.firstVisibleItemIndex
                    chapters.indexOfLast { it.paragraphIndex <= first }.coerceAtLeast(0)
                }
            }
            if (chapters.size >= 2) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        val inside = listState.firstVisibleItemIndex > chapters[currentChapter].paragraphIndex
                        val target = if (inside) currentChapter else (currentChapter - 1).coerceAtLeast(0)
                        scope.launch { listState.animateScrollToItem(chapters[target].paragraphIndex) }
                    }) { Text("◀", color = fg) }
                    Text(
                        "Kapitel ${currentChapter + 1} von ${chapters.size} · " + chapters[currentChapter].title,
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).clickable { showChapters = true },
                    )
                    TextButton(
                        onClick = {
                            val target = (currentChapter + 1).coerceAtMost(chapters.size - 1)
                            scope.launch { listState.animateScrollToItem(chapters[target].paragraphIndex) }
                        },
                        enabled = currentChapter < chapters.size - 1,
                    ) { Text("▶", color = fg) }
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy((settings.fontSize * 0.6f).dp),
            ) {
                itemsIndexed(state.paragraphs) { index, p ->
                    if (ChapterDetector.isHeading(p, chapters, index)) {
                        // Kapitelüberschrift: größer, fett und mit Luft nach oben.
                        Text(
                            p.trim().trimStart('#', '*', '=', ' ').trimEnd('*', '=', ' '),
                            color = fg, fontFamily = family, fontWeight = FontWeight.Bold,
                            fontSize = (settings.fontSize + 5f).sp,
                            modifier = Modifier.padding(top = (settings.fontSize * 1.2f).dp),
                        )
                    } else {
                        Text(
                            p, color = fg, fontFamily = family, fontSize = settings.fontSize.sp,
                            lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                        )
                    }
                }
            }
            if (showBookmarks) {
                AlertDialog(
                    onDismissRequest = { showBookmarks = false },
                    confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Schließen") } },
                    title = { Text("Lesezeichen") },
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
                                            }.padding(vertical = 10.dp),
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
                    confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Schließen") } },
                    title = { Text("Kapitel (${chapters.size})") },
                    text = {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            itemsIndexed(chapters) { i, chapter ->
                                val isCurrent = i == currentChapter
                                val done = i < currentChapter
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showChapters = false
                                            scope.launch { listState.animateScrollToItem(chapter.paragraphIndex) }
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (done) "✓" else if (isCurrent) "▶" else "·",
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 10.dp),
                                    )
                                    Text(
                                        chapter.title,
                                        maxLines = 2,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
    if (split) {
        Box(
            Modifier.width(18.dp).fillMaxHeight().pointerInput(rowWidthPx, settings.splitFraction) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        draftFraction?.let { viewModel.setSettings(settings.copy(splitFraction = it)) }
                        draftFraction = null
                    },
                    onDragCancel = { draftFraction = null },
                ) { change, drag ->
                    change.consume()
                    draftFraction = ((draftFraction ?: settings.splitFraction) - drag / rowWidthPx).coerceIn(0.2f, 0.7f)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(4.dp).height(56.dp).background(fg.copy(alpha = 0.35f), RoundedCornerShape(2.dp)))
        }
        StorySlidePane(image, settings.slideSeconds, viewModel::nextImage, viewModel::previousImage, Modifier.weight(fraction).fillMaxHeight())
    }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Fertig") } },
            title = { Text("Leseansicht") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Schriftgröße ${settings.fontSize.toInt()}")
                    Slider(value = settings.fontSize, onValueChange = { viewModel.setSettings(settings.copy(fontSize = it)) }, valueRange = 14f..32f)
                    Text("Zeilenabstand ${"%.1f".format(settings.lineSpacing)}")
                    Slider(value = settings.lineSpacing, onValueChange = { viewModel.setSettings(settings.copy(lineSpacing = it)) }, valueRange = 1.1f..2.2f)
                    Text("Auto-Scroll: ${settings.scrollSpeed.toInt()} dp pro Sekunde")
                    Slider(value = settings.scrollSpeed, onValueChange = { viewModel.setSettings(settings.copy(scrollSpeed = it)) }, valueRange = 10f..200f)
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = settings.splitOn, onCheckedChange = { viewModel.setSettings(settings.copy(splitOn = it)) })
                            Text("Bilder neben dem Text")
                        }
                        Text("Bildwechsel alle ${settings.slideSeconds} Sekunden")
                        Slider(value = settings.slideSeconds.toFloat(), onValueChange = { viewModel.setSettings(settings.copy(slideSeconds = it.toInt())) }, valueRange = 3f..60f)
                        Text("Breite der Bilder ${(settings.splitFraction * 100).toInt()} %  (oder die Trennlinie ziehen)")
                        Slider(value = settings.splitFraction, onValueChange = { viewModel.setSettings(settings.copy(splitFraction = it)) }, valueRange = 0.2f..0.7f)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in ReaderTheme.entries) FilterChip(selected = settings.theme == t, onClick = { viewModel.setSettings(settings.copy(theme = t)) }, label = { Text(t.label) })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in ReaderFont.entries) FilterChip(selected = settings.font == f, onClick = { viewModel.setSettings(settings.copy(font = f)) }, label = { Text(f.label) })
                    }
                }
            },
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "draft" -> "Entwurf"
    else -> status
}
