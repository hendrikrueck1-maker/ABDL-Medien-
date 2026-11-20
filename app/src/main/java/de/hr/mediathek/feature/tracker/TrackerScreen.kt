package de.hr.mediathek.feature.tracker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.tracker.EntryView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)

fun formatEntryTime(e: EntryView): String {
    val z = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())
    val t = Instant.ofEpochMilli(e.instant).atZone(z)
    return DATE_FMT.format(t) + " " + TIME_FMT.format(t)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TileCard(tile: Tile, viewModel: TrackerViewModel, onTap: () -> Unit, onLongTap: () -> Unit, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, tile.uuid) { value = viewModel.photoOf(tile.uuid)?.asImageBitmap() }
    Card(
        colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLongTap),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f)) {
            val b = bmp
            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (tile.favorite) "★" else "", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium)
        }
        Text(tile.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun UndoBar(viewModel: TrackerViewModel) {
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    undo?.let { (_, title) ->
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Eingetragen: $title", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::undoLast) { Text("Rückgängig") }
            }
        }
    }
}

/** Favoriten-Kacheln zum Schnelleintragen, auch auf der Übersicht. */
@Composable
fun QuickAddRow(viewModel: TrackerViewModel = hiltViewModel(), onOpenTracker: () -> Unit = {}) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val favorites = tiles.filter { it.favorite }.take(8)
    if (favorites.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Schnell eintragen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenTracker) { Text("Alle") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (t in favorites) TileCard(t, viewModel, onTap = { viewModel.tap(t) }, onLongTap = {}, modifier = Modifier.width(140.dp))
        }
        UndoBar(viewModel)
    }
}

@Composable
fun TrackerScreen(onOpenProduct: (String) -> Unit, onStats: () -> Unit, viewModel: TrackerViewModel = hiltViewModel()) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var noteFor by remember { mutableStateOf<Tile?>(null) }
    var editing by remember { mutableStateOf<EntryView?>(null) }
    var shown by remember { mutableStateOf(30) }

    val visibleTiles = if (filter.showAll || filter.query.isNotBlank() || filter.brandUuid != null) tiles else tiles.take(12)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tracker", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onStats) { Text("Statistik") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val latest = entries.firstOrNull()
            Card(colors = glassCardColors(MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Aktuell", style = MaterialTheme.typography.labelLarge)
                    if (latest == null) {
                        Text("Noch kein Eintrag.")
                    } else {
                        Text(latest.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(enabled = latest.productUuid != null) { latest.productUuid?.let(onOpenProduct) })
                        Text(formatEntryTime(latest), style = MaterialTheme.typography.bodySmall)
                        if (latest.productUuid != null) TextButton(onClick = { onOpenProduct(latest.productUuid) }) { Text("Zur Bewertung") }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { UndoBar(viewModel) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = filter.query, onValueChange = { q -> viewModel.update { it.copy(query = q) } },
                    label = { Text("Produkt suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in brands) {
                        FilterChip(selected = filter.brandUuid == b.uuid, onClick = { viewModel.update { f -> f.copy(brandUuid = if (f.brandUuid == b.uuid) null else b.uuid) } }, label = { Text(b.name) })
                    }
                }
                Text("Tippen = eintragen, lang drücken = mit Notiz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visibleTiles, key = { "tile-" + it.uuid }) { t ->
            TileCard(t, viewModel, onTap = { viewModel.tap(t) }, onLongTap = { noteFor = t })
        }
        if (!filter.showAll && tiles.size > visibleTiles.size) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { viewModel.update { it.copy(showAll = true) } }) { Text("Alle ${tiles.size} Produkte zeigen") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Einträge", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(entries.take(shown), key = { "e-" + it.uuid }, span = { GridItemSpan(maxLineSpan) }) { e ->
            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth().clickable { editing = e }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleSmall)
                        Text(formatEntryTime(e) + (e.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("✏")
                }
            }
        }
        if (entries.size > shown) {
            item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = { shown += 50 }) { Text("Mehr anzeigen") } }
        }
    }

    noteFor?.let { tile ->
        var note by remember(tile.uuid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            confirmButton = { Button(onClick = { viewModel.tap(tile, note); noteFor = null }) { Text("Eintragen") } },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text("Abbrechen") } },
            title = { Text(tile.title) },
            text = { OutlinedTextField(note, { note = it }, label = { Text("Notiz (optional)") }, modifier = Modifier.fillMaxWidth()) },
        )
    }
    editing?.let { e -> EditEntryDialog(e, tiles, onDismiss = { editing = null }, viewModel = viewModel) }
}

@Composable
private fun EditEntryDialog(e: EntryView, tiles: List<Tile>, onDismiss: () -> Unit, viewModel: TrackerViewModel) {
    val zone = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(e.instant).atZone(zone)
    var date by remember { mutableStateOf(DATE_FMT.format(start)) }
    var time by remember { mutableStateOf(TIME_FMT.format(start)) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    var productUuid by remember { mutableStateOf(e.productUuid) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eintrag bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("Datum (TT.MM.JJJJ)") }, singleLine = true, isError = error)
                OutlinedTextField(time, { time = it }, label = { Text("Uhrzeit (HH:MM)") }, singleLine = true, isError = error)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(tiles.firstOrNull { it.uuid == productUuid }?.title ?: e.name) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (t in tiles) DropdownMenuItem(text = { Text(t.title) }, onClick = { productUuid = t.uuid; menu = false })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Notiz") })
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = runCatching {
                    val d = LocalDate.parse(date.trim(), DATE_FMT)
                    val t = java.time.LocalTime.parse(time.trim(), TIME_FMT)
                    LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull()
                if (parsed == null) error = true else { viewModel.edit(e.uuid, parsed, productUuid, note); onDismiss() }
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { viewModel.delete(e.uuid); onDismiss() }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
