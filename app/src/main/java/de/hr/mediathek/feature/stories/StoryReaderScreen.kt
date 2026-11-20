package de.hr.mediathek.feature.stories

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.feature.images.AccentPill
import de.hr.mediathek.feature.images.FavoritePill
import de.hr.mediathek.feature.images.RoundIconButton

/**
 * Geschichten-Leser. Tablet und Querformat: Leseseite links, Bilder rechts, dazwischen ein verschiebbarer Teiler.
 * Handy hochkant: Bilder als einklappbarer Streifen oben (ca. 40 % Höhe), Text darunter.
 * "Nur Text" blendet die Bilder aus (Tablet: Seite mittig, rechts die Werkzeuge).
 */
@Composable
fun StoryReaderScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenStory: (String) -> Unit = {},
    onEditSeries: (String) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    viewModel: StoryReaderViewModel = hiltViewModel(),
) {
    val c = LocalAppColors.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val image by viewModel.image.collectAsStateWithLifecycle()
    val folders by viewModel.imageFolders.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 600
    val sideBySide = wide || cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val textOnly = !settings.splitOn
    var stripOpen by rememberSaveable { mutableStateOf(true) }
    var rowWidthPx by remember { mutableIntStateOf(1) }
    var draftFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = draftFraction ?: settings.splitFraction
    val page = pageColors(settings.theme)

    val toggleImages = { viewModel.setSettings(settings.copy(splitOn = !settings.splitOn)) }
    val card: @Composable (Modifier) -> Unit = { m ->
        ReadingCard(
            viewModel, state, settings, page, wide, textOnly, toggleImages, onBack, onEdit, onOpenStory, onEditSeries,
            onOpenSettings = { showSettings = true }, modifier = m,
        )
    }
    val panel: @Composable (Modifier, (() -> Unit)?) -> Unit = { m, collapse ->
        StoryImagePanel(
            image = image, folders = folders, seconds = settings.slideSeconds, random = settings.slideRandom,
            onStart = viewModel::startImages, onNext = viewModel::nextImage, onPrevious = viewModel::previousImage,
            onFolder = viewModel::setImageFolder,
            onRandom = { viewModel.setSettings(settings.copy(slideRandom = it)) },
            onInterval = { viewModel.setSettings(settings.copy(slideSeconds = it)) },
            onFullscreen = { image.uuid?.let(onOpenImage) },
            modifier = m, onCollapse = collapse,
        )
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        when {
            sideBySide && !textOnly -> Row(Modifier.fillMaxSize().onSizeChanged { rowWidthPx = it.width.coerceAtLeast(1) }) {
                card(Modifier.weight(1f - fraction).fillMaxHeight())
                // Teiler: Griff 20 × 80 dp; Verhältnis wird gemerkt (Text 35–75 %).
                Box(
                    Modifier.width(20.dp).fillMaxHeight()
                        .semantics { contentDescription = "" }
                        .pointerInput(rowWidthPx, settings.splitFraction) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    draftFraction?.let { viewModel.setSettings(settings.copy(splitFraction = it)) }
                                    draftFraction = null
                                },
                                onDragCancel = { draftFraction = null },
                            ) { change, drag ->
                                change.consume()
                                draftFraction = ((draftFraction ?: settings.splitFraction) - drag / rowWidthPx).coerceIn(0.25f, 0.65f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(6.dp).height(80.dp).clip(RoundedCornerShape(3.dp)).background(c.bar))
                }
                panel(Modifier.weight(fraction).fillMaxHeight(), null)
            }
            sideBySide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxHeight().weight(1f, fill = false)) { card(Modifier.fillMaxSize()) }
                if (wide) TextOnlyTools(viewModel, state, page, toggleImages, onEdit) { showSettings = true }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!textOnly) {
                    if (stripOpen) {
                        panel(Modifier.fillMaxWidth().height((cfg.screenHeightDp * 0.4f).dp), { stripOpen = false })
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.bar)
                                .clickable(onClickLabel = stringResource(R.string.reader_expand_images)) { stripOpen = true }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.reader_images_title), color = c.onCard, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.reader_expand_images), tint = c.onCard)
                        }
                    }
                }
                card(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (showSettings) ReaderSettingsDialog(settings, viewModel, wide || sideBySide) { showSettings = false }
}

/** Rechte Spalte im Modus "Nur Text" auf dem Tablet. */
@Composable
private fun TextOnlyTools(
    viewModel: StoryReaderViewModel,
    state: ReaderUiState,
    page: PageColors,
    onShowImages: () -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalAppColors.current
    val story = state.story ?: return
    Column(
        Modifier.width(260.dp).fillMaxHeight().clip(RoundedCornerShape(32.dp)).background(c.bar).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.reader_tools_title), style = MaterialTheme.typography.titleSmall, color = c.onCard)
        AccentPill(stringResource(R.string.reader_show_images), c.hellblau, Icons.Filled.Image, onShowImages)
        AccentPill(stringResource(R.string.reader_settings), c.mint, Icons.Filled.TextFields, onSettings)
        PageStars(story.rating, PageColors(c.bar, c.onCard, c.rosa, c.gold), viewModel::setRating)
        FavoritePill(story.favorite) { viewModel.toggleFavorite() }
        AccentPill(stringResource(R.string.reader_open_editor), c.lavendel, Icons.Filled.Edit) { onEdit(story.uuid) }
    }
}

@Composable
private fun ReaderSettingsDialog(settings: ReaderSettings, viewModel: StoryReaderViewModel, showSplit: Boolean, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bar.copy(alpha = 1f),
        shape = RoundedCornerShape(30.dp),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        title = { Text(stringResource(R.string.reader_settings)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Schriftgröße ${settings.fontSize.toInt()}")
                Slider(value = settings.fontSize, onValueChange = { viewModel.setSettings(settings.copy(fontSize = it)) }, valueRange = 14f..32f)
                Text("Zeilenabstand ${"%.2f".format(settings.lineSpacing)}")
                Slider(value = settings.lineSpacing, onValueChange = { viewModel.setSettings(settings.copy(lineSpacing = it)) }, valueRange = 1.1f..2.2f)
                Text("Auto-Scroll: ${settings.scrollSpeed.toInt()} dp pro Sekunde")
                Slider(value = settings.scrollSpeed, onValueChange = { viewModel.setSettings(settings.copy(scrollSpeed = it)) }, valueRange = 10f..200f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = settings.splitOn, onCheckedChange = { viewModel.setSettings(settings.copy(splitOn = it)) })
                    Text("Bilder zum Text zeigen")
                }
                if (showSplit && settings.splitOn) {
                    Text("Breite der Bilder ${(settings.splitFraction * 100).toInt()} %  (oder den Teiler ziehen)")
                    Slider(value = settings.splitFraction, onValueChange = { viewModel.setSettings(settings.copy(splitFraction = it)) }, valueRange = 0.25f..0.65f)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in ReaderTheme.entries.sortedBy { listOf(ReaderTheme.DARK, ReaderTheme.SEPIA, ReaderTheme.LIGHT).indexOf(it) }) {
                        FilterChip(selected = settings.theme == t, onClick = { viewModel.setSettings(settings.copy(theme = t)) }, label = { Text(t.label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in ReaderFont.entries) FilterChip(selected = settings.font == f, onClick = { viewModel.setSettings(settings.copy(font = f)) }, label = { Text(f.label) })
                }
            }
        },
    )
}
