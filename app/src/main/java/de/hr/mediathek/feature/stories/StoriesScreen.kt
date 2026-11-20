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
import de.hr.mediathek.core.ui.readable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    onEditSeries: (String) -> Unit = {},
    viewModel: StoriesViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val paged by viewModel.paged.collectAsStateWithLifecycle()
    val lastOpened by viewModel.lastOpened.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    val resume by viewModel.continueReading.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val folderItems by viewModel.folderItems.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var gotoPage by remember { mutableStateOf(false) }
    var firstShown by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<List<String>?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) } // Name im Eingabedialog
    var renameFolder by remember { mutableStateOf<String?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf(false) }
    val currentFolder = folders.firstOrNull { it.uuid == filter.folder }
    val items = paged.items
    val stripShown = resume.isNotEmpty() && paged.page == 0

    // Beim ersten Anzeigen zur zuletzt geöffneten Geschichte springen, danach bei jedem Seitenwechsel nach oben.
    LaunchedEffect(paged.page, paged.loaded) {
        if (!paged.loaded) return@LaunchedEffect
        if (!firstShown) {
            firstShown = true
            val idx = items.indexOfFirst { it is ListItem.Single && it.row.uuid == lastOpened || it is ListItem.SeriesGroup && it.parts.any { p -> p.uuid == lastOpened } }
            if (idx > 0) listState.scrollToItem(idx + if (stripShown) 1 else 0)
        } else {
            listState.scrollToItem(0)
        }
    }

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
                if (paged.loaded && items.isEmpty() && resume.isEmpty()) {
                    Text("Keine Geschichten gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    if (stripShown) {
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
                                highlight = item.parts.any { it.uuid == lastOpened },
                                onToggle = { viewModel.toggleExpanded(item.key) },
                                onResume = { onOpen(item.resumePart.uuid) },
                                onFolder = { pickFor = item.parts.map { it.uuid } },
                                onEdit = { onEditSeries(item.uuid) },
                            )
                            is ListItem.Single -> StoryCard(
                                item.row, item.indent,
                                highlight = item.row.uuid == lastOpened,
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
                if (paged.pageCount > 1) {
                    PageBar(paged.page, paged.pageCount, paged.total, viewModel::setPage) { gotoPage = true }
                }
            }
        }
        if (!filter.trash && filter.sort == StorySort.TITLE && filter.view == StoryView.LIST && paged.pageCount > 1) {
            LetterBar(paged.letterPages, viewModel::setPage, Modifier.align(Alignment.CenterEnd))
        }
    }

    if (gotoPage) {
        GotoPageDialog(paged.page, paged.pageCount, onDismiss = { gotoPage = false }) { viewModel.setPage(it); gotoPage = false }
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
private fun SeriesCard(item: ListItem.SeriesGroup, highlight: Boolean, onToggle: () -> Unit, onResume: () -> Unit, onFolder: () -> Unit, onEdit: () -> Unit) {
    Card(
        colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer),
        border = if (highlight) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
                TextButton(onClick = onEdit) { Text("✎ Bearbeiten") }
            }
        }
    }
}

private fun partNumber(item: ListItem.SeriesGroup): Int = item.parts.indexOfFirst { it.uuid == item.resumePart.uuid } + 1

@Composable
private fun StoryCard(
    row: StoryRow,
    indent: Int,
    highlight: Boolean,
    onOpen: () -> Unit,
    onRate: (Int) -> Unit,
    onFavorite: () -> Unit,
    onContinue: () -> Unit,
    onFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer),
        border = if (highlight) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
private fun LetterBar(letterPages: Map<Char, Int>, onPage: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().padding(top = 80.dp, bottom = 110.dp).readable(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (c in "#ABCDEFGHIJKLMNOPQRSTUVWXYZ") {
            val page = letterPages[c]
            Text(
                c.toString(),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = if (page != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(24.dp).clickable(enabled = page != null) { onPage(page!!) },
            )
        }
    }
}

/** Seitenleiste unten: erste, zurück, "Seite x von y" (antippen zum Springen), weiter, letzte. */
@Composable
private fun PageBar(page: Int, pageCount: Int, total: Int, onPage: (Int) -> Unit, onGoto: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).readable(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { onPage(0) }, enabled = page > 0) { Text("⏮") }
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("◀") }
        Text(
            "Seite ${page + 1} von $pageCount",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clickable(onClick = onGoto).padding(horizontal = 8.dp, vertical = 12.dp),
        )
        TextButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1) { Text("▶") }
        TextButton(onClick = { onPage(pageCount - 1) }, enabled = page < pageCount - 1) { Text("⏭") }
    }
}

@Composable
private fun GotoPageDialog(page: Int, pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf((page + 1).toString()) }
    val n = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zu Seite springen") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter(Char::isDigit).take(5) }, singleLine = true,
                label = { Text("Seite 1 bis $pageCount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { onGo((n ?: 1) - 1) }, enabled = n != null && n in 1..pageCount) { Text("Springen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
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
