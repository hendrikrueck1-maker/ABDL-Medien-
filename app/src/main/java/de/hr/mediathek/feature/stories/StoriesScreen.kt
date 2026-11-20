package de.hr.mediathek.feature.stories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.bounceClick
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryRow
import kotlinx.coroutines.launch

@Composable
fun StoriesScreen(
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onImport: () -> Unit,
    onDuplicates: () -> Unit = {},
    onSeriesSuggest: () -> Unit = {},
    onTools: () -> Unit = {},
    viewModel: StoriesViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    val resume by viewModel.continueReading.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val folderItems by viewModel.folderItems.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<List<String>?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) } // Name im Eingabedialog
    var renameFolder by remember { mutableStateOf<String?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf(false) }
    val currentFolder = folders.firstOrNull { it.uuid == filter.folder }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (filter.trash) "Papierkorb" else "Geschichten",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!filter.trash) {
                    OutlinedButton(onClick = onImport) { Text("Importieren") }
                    Button(onClick = { scope.launch { onEdit(viewModel.createEmpty()) } }) { Text("Neu") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Duplikate suchen") }, onClick = { menu = false; onDuplicates() })
                            DropdownMenuItem(text = { Text("Serien erkennen") }, onClick = { menu = false; onSeriesSuggest() })
                            DropdownMenuItem(text = { Text("Statistik und Export") }, onClick = { menu = false; onTools() })
                            DropdownMenuItem(text = { Text("Papierkorb") }, onClick = { menu = false; viewModel.update { it.copy(trash = true) } })
                        }
                    }
                } else {
                    TextButton(onClick = { viewModel.update { it.copy(trash = false) } }) { Text("Zurück") }
                }
            }
            if (!filter.trash) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { q -> viewModel.update { it.copy(query = q) } },
                    label = { Text("Suchen in Titel, Autor und Quelle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in StoryView.entries) {
                        FilterChip(selected = filter.view == v, onClick = { viewModel.update { it.copy(view = v) } }, label = { Text(v.label) })
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter.category == null, onClick = { viewModel.update { it.copy(category = null) } }, label = { Text("Alle") })
                    for (c in StoryCategory.entries) {
                        FilterChip(
                            selected = filter.category == c,
                            onClick = { viewModel.update { f -> f.copy(category = if (f.category == c) null else c) } },
                            label = { Text(categoryLabel(c)) },
                        )
                    }
                    FilterChip(selected = filter.favoritesOnly, onClick = { viewModel.update { it.copy(favoritesOnly = !it.favoritesOnly) } }, label = { Text("♥ Favoriten") })
                    FilterChip(selected = filter.continueOnly, onClick = { viewModel.update { it.copy(continueOnly = !it.continueOnly) } }, label = { Text("✏ Weiterschreiben") })
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (r in ReadState.entries.drop(1)) {
                        FilterChip(
                            selected = filter.readState == r,
                            onClick = { viewModel.update { f -> f.copy(readState = if (f.readState == r) ReadState.ALL else r) } },
                            label = { Text(r.label) },
                        )
                    }
                    for (s in StorySort.entries) {
                        FilterChip(selected = filter.sort == s, onClick = { viewModel.update { f -> f.copy(sort = s) } }, label = { Text("↕ ${s.label}") })
                    }
                }
                if (filter.view == StoryView.FOLDERS) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (filter.folder != null) {
                            TextButton(onClick = { viewModel.update { it.copy(folder = currentFolder?.parentUuid) } }) { Text("← Hoch") }
                        }
                        Text(
                            currentFolder?.name ?: if (filter.folder == NO_FOLDER) "Ohne Ordner" else "Alle Ordner",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (filter.folder != NO_FOLDER) TextButton(onClick = { newFolder = "" }) { Text("+ Ordner") }
                        if (currentFolder != null) {
                            TextButton(onClick = { renameFolder = currentFolder.name }) { Text("✎") }
                            TextButton(onClick = { confirmDeleteFolder = true }) { Text("🗑") }
                        }
                    }
                }
            }

            if (filter.trash) {
                if (trash.isEmpty()) {
                    Text("Der Papierkorb ist leer. Gelöschte Geschichten bleiben hier 30 Tage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trash, key = { it.uuid }) { row ->
                        Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.restore(row) }) { Text("Wiederherstellen") }
                                    OutlinedButton(onClick = { viewModel.purge(row) }) { Text("Endgültig löschen") }
                                }
                            }
                        }
                    }
                }
            } else {
                if (items.isEmpty() && resume.isEmpty()) {
                    Text("Keine Geschichten gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    if (resume.isNotEmpty()) {
                        item(key = "resume") { ResumeStrip(resume, onOpen) }
                    }
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is ListItem.Header -> Text(
                                (if (item.expanded) "▾ " else "▸ ") + categoryLabel(item.category) + " (${item.count})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleExpanded(item.key) }.padding(vertical = 10.dp),
                            )
                            is ListItem.Folder -> FolderCard(item) { viewModel.update { f -> f.copy(folder = item.folder.uuid) } }
                            is ListItem.SeriesGroup -> SeriesCard(
                                item,
                                onToggle = { viewModel.toggleExpanded(item.key) },
                                onResume = { onOpen(item.resumePart.uuid) },
                                onFolder = { pickFor = item.parts.map { it.uuid } },
                            )
                            is ListItem.Single -> StoryCard(
                                item.row, item.indent,
                                onOpen = { onOpen(item.row.uuid) },
                                onRate = { viewModel.setRating(item.row, it) },
                                onFavorite = { viewModel.toggleFavorite(item.row) },
                                onContinue = { viewModel.toggleContinue(item.row) },
                                onFolder = { pickFor = listOf(item.row.uuid) },
                                onDelete = { viewModel.delete(item.row) },
                            )
                        }
                    }
                }
            }
        }
        if (!filter.trash && filter.sort == StorySort.TITLE && filter.view != StoryView.FOLDERS && items.size > 15) {
            LetterBar(items, listState, Modifier.align(Alignment.CenterEnd))
        }
    }

    pickFor?.let { ids ->
        FolderPickerDialog(
            storyUuids = ids,
            folders = folders,
            folderItems = folderItems,
            onToggle = { folder, on -> viewModel.setInFolder(folder, ids, on) },
            onCreate = { name -> viewModel.createFolderAndAdd(name, ids) },
            onDismiss = { pickFor = null },
        )
    }
    newFolder?.let { name ->
        NameDialog("Neuer Ordner", name, onDismiss = { newFolder = null }) { viewModel.createFolder(it, filter.folder?.takeIf { f -> f != NO_FOLDER }); newFolder = null }
    }
    renameFolder?.let { name ->
        NameDialog("Ordner umbenennen", name, onDismiss = { renameFolder = null }) { currentFolder?.let { f -> viewModel.renameFolder(f.uuid, it) }; renameFolder = null }
    }
    if (confirmDeleteFolder && currentFolder != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = false },
            title = { Text("Ordner löschen?") },
            text = { Text("Der Ordner „${currentFolder.name}“ wird gelöscht. Die Geschichten bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteFolder(currentFolder); confirmDeleteFolder = false }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDeleteFolder = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ResumeStrip(rows: List<StoryRow>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Weiterlesen", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.uuid }) { r ->
                Card(
                    colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.width(200.dp).bounceClick { onOpen(r.uuid) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        LinearProgressIndicator(progress = { r.readProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${(r.readProgress * 100).toInt()} % gelesen", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(item: ListItem.Folder, onClick: () -> Unit) {
    Card(colors = glassCardColors(MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth().bounceClick(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text("📁 ${item.folder.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${item.storyCount} Geschichten" + if (item.subfolders > 0) " · ${item.subfolders} Unterordner" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SeriesCard(item: ListItem.SeriesGroup, onToggle: () -> Unit, onResume: () -> Unit, onFolder: () -> Unit) {
    Card(
        colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(start = (item.indent * 16).dp).bounceClick(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (item.expanded) "▾ " else "▸ ") + "📚 " + item.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${item.parts.size} Kapitel · ${item.doneCount} gelesen" +
                    (item.parts.sumOf { it.wordCount }.takeIf { it > 0 }?.let { " · ${formatWords(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { item.avgProgress }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onResume) { Text("▶ Weiterlesen (Teil ${partNumber(item)})") }
                TextButton(onClick = onFolder) { Text("📁") }
            }
        }
    }
}

private fun partNumber(item: ListItem.SeriesGroup): Int = item.parts.indexOfFirst { it.uuid == item.resumePart.uuid } + 1

@Composable
private fun StoryCard(
    row: StoryRow,
    indent: Int,
    onOpen: () -> Unit,
    onRate: (Int) -> Unit,
    onFavorite: () -> Unit,
    onContinue: () -> Unit,
    onFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(start = (indent * 16).dp).bounceClick(onClick = onOpen),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            val meta = listOfNotNull(
                categoryLabel(row.category),
                row.author?.takeIf { it.isNotBlank() }?.let { "von $it" },
                row.sourceText?.takeIf { it.isNotBlank() },
                row.wordCount.takeIf { it > 0 }?.let(::formatWords),
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.readProgress >= READ_DONE_AT) {
                Text("✓ gelesen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else if (row.lastReadAt != null) {
                LinearProgressIndicator(progress = { row.readProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StarRow(row.rating, onRate)
                Text(if (row.favorite) "♥" else "♡", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onFavorite).padding(10.dp))
                Text("✏", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (row.continueFlag) 1f else 0.3f),
                    modifier = Modifier.clickable(onClick = onContinue).padding(10.dp))
                Text("📁", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable(onClick = onFolder).padding(10.dp))
                Text("🗑", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable(onClick = onDelete).padding(10.dp))
            }
        }
    }
}

internal fun formatWords(n: Int): String = if (n >= 1000) "${n / 1000} Tsd. Wörter" else "$n Wörter"

@Composable
private fun LetterBar(items: List<ListItem>, listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val firstIndex = remember(items) {
        val map = LinkedHashMap<Char, Int>()
        items.forEachIndexed { i, item ->
            val title = when (item) {
                is ListItem.Single -> if (item.indent == 0) item.row.title else null
                is ListItem.SeriesGroup -> item.name
                else -> null
            }
            val c = title?.trim()?.firstOrNull()?.uppercaseChar()?.let { if (it in 'A'..'Z') it else '#' }
            if (c != null && c !in map) map[c] = i
        }
        map
    }
    Column(
        modifier.fillMaxHeight().padding(vertical = 80.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (c in "#ABCDEFGHIJKLMNOPQRSTUVWXYZ") {
            val idx = firstIndex[c]
            Text(
                c.toString(),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = if (idx != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(24.dp).clickable(enabled = idx != null) {
                    // +1 wegen der Weiterlesen-Leiste, die als erstes Element in der Liste stehen kann.
                    scope.launch { listState.scrollToItem(idx!! + if (listState.layoutInfo.totalItemsCount > items.size) 1 else 0) }
                },
            )
        }
    }
}

@Composable
private fun FolderPickerDialog(
    storyUuids: List<String>,
    folders: List<de.hr.mediathek.data.db.StoryFolderEntity>,
    folderItems: List<de.hr.mediathek.data.db.StoryFolderItemEntity>,
    onToggle: (folderUuid: String, on: Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("In Ordner legen") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(folders, key = { it.uuid }) { f ->
                        val inFolder = folderItems.filter { it.folderUuid == f.uuid }.map { it.storyUuid }.toSet()
                        val all = storyUuids.all { it in inFolder }
                        Row(Modifier.fillMaxWidth().clickable { onToggle(f.uuid, !all) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = all, onCheckedChange = { onToggle(f.uuid, it) })
                            Text(f.name)
                        }
                    }
                }
                if (folders.isEmpty()) Text("Noch keine Ordner.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Neuer Ordner") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { if (name.isNotBlank()) { onCreate(name); name = "" } }, enabled = name.isNotBlank()) { Text("Ordner anlegen und hineinlegen") }
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onOk(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
