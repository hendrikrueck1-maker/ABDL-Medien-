@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package de.hr.mediathek.feature.stories

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AssistChip
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
    val backupMissing by viewModel.backupMissing.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var mergeName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var gotoPage by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var firstShown by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<List<String>?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) } // Name im Eingabedialog
    var renameFolder by remember { mutableStateOf<String?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf(false) }
    var confirmBulk by remember { mutableStateOf<List<String>?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val lastBulk by viewModel.lastBulk.collectAsStateWithLifecycle()
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
            // Bedienknöpfe links (Bedienung mit der linken Hand), Titel dahinter.
            // Im Hochformat reicht der Platz nicht: dann steht der Titel in einer eigenen Zeile darüber.
            val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
            val title = if (filter.trash) "Papierkorb" else "Geschichten"
            if (narrow) Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!filter.trash) {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Geschichten zusammenführen …") }, onClick = { menu = false; viewModel.startSelection() })
                            DropdownMenuItem(
                                text = { Text(if (filterActive(filter)) "Angezeigte Geschichten löschen …" else "Alle Geschichten löschen …") },
                                onClick = { menu = false; confirmBulk = viewModel.visibleStoryUuids() },
                            )
                            DropdownMenuItem(text = { Text("Duplikate suchen") }, onClick = { menu = false; onDuplicates() })
                            DropdownMenuItem(text = { Text("Serien erkennen") }, onClick = { menu = false; onSeriesSuggest() })
                            DropdownMenuItem(text = { Text("Statistik und Export") }, onClick = { menu = false; onTools() })
                            DropdownMenuItem(text = { Text("Papierkorb") }, onClick = { menu = false; viewModel.update { it.copy(trash = true) } })
                        }
                    }
                    Button(onClick = { scope.launch { onEdit(viewModel.createEmpty()) } }) { Text("Neu") }
                    OutlinedButton(onClick = onImport) { Text("Importieren") }
                } else {
                    TextButton(onClick = { viewModel.update { it.copy(trash = false) } }) { Text("← Zurück") }
                }
                if (!narrow) Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.weight(1f))
            }
            selection?.let { sel ->
                // Auswahl zum Zusammenführen: Antippen wählt aus statt zu öffnen.
                Text(
                    "Zusammenführen: Geschichten in der Reihenfolge der Teile antippen (Teil 1 zuerst). Auch die Suche oben hilft beim Finden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { mergeName = firstTitleOf(sel, items) }, enabled = sel.size >= 2) { Text("Zusammenführen") }
                    OutlinedButton(onClick = viewModel::cancelSelection) { Text("Abbrechen") }
                    Text(
                        if (sel.size < 2) "${sel.size} von mind. 2 gewählt" else "${sel.size} ausgewählt",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!filter.trash && backupMissing) {
                Text(
                    "ⓘ Geschichten werden nicht automatisch gesichert: In den Einstellungen unter „Medienquellen und Arbeitsordner“ einen Arbeitsordner wählen " +
                        "oder regelmäßig über ⋮ → „Statistik und Export“ sichern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = de.hr.mediathek.core.ui.theme.LocalAppColors.current.gold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (!filter.trash) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { q -> viewModel.update { it.copy(query = q) } },
                    label = { Text("Suchen in Titel, Autor und Quelle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                val activeFilters = listOf(filter.favoritesOnly, filter.continueOnly, filter.partsMissingOnly, filter.maxWords != null, filter.readState != ReadState.ALL).count { it }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = filter.category == null, onClick = { viewModel.update { it.copy(category = null) } }, label = { Text("Alle") })
                    for (c in StoryCategory.entries) {
                        FilterChip(
                            selected = filter.category == c,
                            onClick = { viewModel.update { f -> f.copy(category = if (f.category == c) null else c) } },
                            label = { Text(categoryLabel(c)) },
                        )
                    }
                    OutlinedButton(onClick = { showView = true }) { Text("Ansicht: ${filter.view.label}") }
                    if (activeFilters > 0) Button(onClick = { showFilter = true }) { Text("Filter ($activeFilters)") }
                    else OutlinedButton(onClick = { showFilter = true }) { Text("Filter") }
                }
                if (filter.maxWords != null && selection == null) {
                    // Kurze Geschichten aufräumen: gefilterte Liste direkt löschen können.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmBulk = viewModel.visibleStoryUuids() }) { Text("🗑 Alle angezeigten löschen") }
                        Text("Unter ${filter.maxWords} Wörtern", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                if (lastBulk.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::undoBulkDelete) { Text("Rückgängig") }
                        TextButton(onClick = viewModel::dismissBulk) { Text("✕") }
                        Text("${lastBulk.size} Geschichten im Papierkorb", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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
                } else {
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::restoreAll) { Text("Alle wiederherstellen") }
                        OutlinedButton(onClick = { confirmEmptyTrash = true }) { Text("Papierkorb leeren") }
                    }
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
                                highlight = item.parts.any { it.uuid == lastOpened } || selection?.let { sel -> item.parts.all { it.uuid in sel } } == true,
                                onToggle = { if (selection != null) viewModel.toggleSelected(item.parts.map { it.uuid }) else viewModel.toggleExpanded(item.key) },
                                onResume = { onOpen(item.resumePart.uuid) },
                                onFolder = { pickFor = item.parts.map { it.uuid } },
                                onEdit = { onEditSeries(item.uuid) },
                                onLongPress = { if (selection == null) viewModel.startSelection(item.parts.map { it.uuid }) },
                            )
                            is ListItem.Single -> StoryCard(
                                item.row, item.indent, item.partNo, item.seriesName,
                                highlight = item.row.uuid == lastOpened,
                                selectedIndex = selection?.indexOf(item.row.uuid)?.takeIf { it >= 0 }?.plus(1),
                                selecting = selection != null,
                                onOpen = { if (selection != null) viewModel.toggleSelected(listOf(item.row.uuid)) else onOpen(item.row.uuid) },
                                onCategory = { viewModel.setCategory(item.row, it) },
                                onPartsMissing = { viewModel.togglePartsMissing(item.row) },
                                onRate = { viewModel.setRating(item.row, it) },
                                onFavorite = { viewModel.toggleFavorite(item.row) },
                                onContinue = { viewModel.toggleContinue(item.row) },
                                onFolder = { pickFor = listOf(item.row.uuid) },
                                onDelete = { viewModel.delete(item.row) },
                                onLongPress = { if (selection == null) viewModel.startSelection(listOf(item.row.uuid)) },
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
    mergeName?.let { initial ->
        NameDialog("Name der Serie", initial, onDismiss = { mergeName = null }) { name ->
            mergeName = null
            scope.launch { viewModel.mergeSelected(name)?.let(onEditSeries) }
        }
    }
    if (showView) StoryViewDialog(filter, onDismiss = { showView = false }) { f -> viewModel.update { f } }
    if (showFilter) StoryFilterDialog(filter, onDismiss = { showFilter = false }) { f -> viewModel.update { f } }
    newFolder?.let { name ->
        NameDialog("Neuer Ordner", name, onDismiss = { newFolder = null }) { viewModel.createFolder(it, filter.folder?.takeIf { f -> f != NO_FOLDER }); newFolder = null }
    }
    renameFolder?.let { name ->
        NameDialog("Ordner umbenennen", name, onDismiss = { renameFolder = null }) { currentFolder?.let { f -> viewModel.renameFolder(f.uuid, it) }; renameFolder = null }
    }
    confirmBulk?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmBulk = null },
            title = { Text(if (filterActive(filter)) "Angezeigte Geschichten löschen?" else "Alle Geschichten löschen?") },
            text = {
                Text(
                    if (ids.isEmpty()) "Die Liste ist leer – es gibt nichts zu löschen."
                    else "${ids.size} Geschichten kommen in den Papierkorb. Dort bleiben sie 30 Tage und lassen sich wiederherstellen.",
                )
            },
            confirmButton = {
                if (ids.isNotEmpty()) TextButton(onClick = { viewModel.deleteVisible(ids); confirmBulk = null }) { Text("${ids.size} löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = null }) { Text("Abbrechen") } },
        )
    }
    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Papierkorb leeren?") },
            text = { Text("${trash.size} Geschichten werden endgültig gelöscht. Das lässt sich nicht rückgängig machen.") },
            confirmButton = { TextButton(onClick = { viewModel.emptyTrash(); confirmEmptyTrash = false }) { Text("Endgültig löschen") } },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Abbrechen") } },
        )
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
private fun SeriesCard(
    item: ListItem.SeriesGroup,
    highlight: Boolean,
    onToggle: () -> Unit,
    onResume: () -> Unit,
    onFolder: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer),
        border = if (highlight) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(start = (item.indent * 16).dp).bounceClick(onLongClick = onLongPress, onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PartBadge("📚 Serie · ${item.parts.size} Teile")
                Text(if (item.expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                item.name + if (item.parts.any { it.partsMissing }) "  🧩" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${item.doneCount} von ${item.parts.size} gelesen" +
                    (item.parts.sumOf { it.wordCount }.takeIf { it > 0 }?.let { " · ${formatWords(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { item.avgProgress }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Button(onClick = onResume) { Text("▶ Weiterlesen (Teil ${partNumber(item)})", maxLines = 1) }
                TextButton(onClick = onEdit) { Text("✎ Bearbeiten", maxLines = 1, softWrap = false) }
                TextButton(onClick = onFolder) { Text("📁", style = MaterialTheme.typography.titleLarge) }
            }
            if (!item.expanded) Text(
                "▸ Antippen zeigt die ${item.parts.size} Teile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun partNumber(item: ListItem.SeriesGroup): Int = item.parts.indexOfFirst { it.uuid == item.resumePart.uuid } + 1

@Composable
private fun StoryCard(
    row: StoryRow,
    indent: Int,
    partNo: Int?,
    seriesName: String?,
    highlight: Boolean,
    selectedIndex: Int?,
    selecting: Boolean,
    onOpen: () -> Unit,
    onCategory: (StoryCategory) -> Unit,
    onPartsMissing: () -> Unit,
    onRate: (Int) -> Unit,
    onFavorite: () -> Unit,
    onContinue: () -> Unit,
    onFolder: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
) {
    val selected = selectedIndex != null
    // Teile einer aufgeklappten Serie: weiter eingerückt, in der Farbe der Serie und mit Balken links,
    // damit sie sich deutlich von einzelnen Geschichten abheben.
    val inSeries = partNo != null
    val stripe = MaterialTheme.colorScheme.secondary
    Card(
        colors = glassCardColors(if (inSeries) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        border = if (highlight || selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(start = if (inSeries) 28.dp else (indent * 16).dp).bounceClick(onLongClick = onLongPress, onClick = onOpen),
    ) {
        Column(
            Modifier
                .then(if (inSeries) Modifier.drawBehind { drawRect(stripe, size = Size(6.dp.toPx(), size.height)) } else Modifier)
                .padding(start = if (inSeries) 18.dp else 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val prefix = selectedIndex?.let { "$it. " } ?: if (selecting) "○ " else ""
            val missing = if (row.partsMissing) "  🧩" else ""
            if (inSeries) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prefix.isNotEmpty()) Text(prefix, style = MaterialTheme.typography.titleMedium)
                    PartBadge("Teil $partNo")
                    val rest = partTitle(row.title, seriesName)
                    if (rest.isNotEmpty() || missing.isNotEmpty()) Text(rest + missing, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            } else {
                Text(prefix + row.title + missing, style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(row.category, onCategory)
                val meta = listOfNotNull(
                    row.author?.takeIf { it.isNotBlank() }?.let { "von $it" },
                    row.sourceText?.takeIf { it.isNotBlank() },
                    row.wordCount.takeIf { it > 0 }?.let(::formatWords),
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            if (row.readProgress >= READ_DONE_AT) {
                Text("✓ gelesen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else if (row.lastReadAt != null) {
                LinearProgressIndicator(progress = { row.readProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (!selecting) {
                // Sterne und Aktionen brechen im Hochformat in zwei Zeilen um, damit nichts (z. B. 🗑) aus dem Bild rutscht.
                FlowRow(verticalArrangement = Arrangement.Center) {
                    StarRow(row.rating, onRate)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardAction(if (row.favorite) "♥" else "♡", "Favorit", active = true, tint = MaterialTheme.colorScheme.primary, onClick = onFavorite)
                        CardAction("✏", "Weiterschreiben", active = row.continueFlag, tint = MaterialTheme.colorScheme.primary, onClick = onContinue)
                        CardAction("🧩", "Teile fehlen noch", active = row.partsMissing, onClick = onPartsMissing)
                        CardAction("📁", "In Ordner", active = true, onClick = onFolder)
                        CardAction("🗑", "Löschen (Papierkorb)", active = true, onClick = onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(symbol: String, label: String, active: Boolean, tint: Color? = null, onClick: () -> Unit) {
    Text(
        symbol,
        style = MaterialTheme.typography.titleLarge,
        color = tint ?: Color.Unspecified,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .graphicsLayer { alpha = if (active) 1f else 0.3f }
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(10.dp),
    )
}

/** Kleines Etikett „Teil 2“ bzw. „📚 Serie · 3 Teile“ in der Serienfarbe. */
@Composable
private fun PartBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Titel eines Serienteils ohne den Seriennamen und ohne „Teil N“, damit nur das Unterscheidende bleibt. */
internal fun partTitle(title: String, seriesName: String?): String {
    var t = title.trim()
    if (!seriesName.isNullOrBlank() && t.startsWith(seriesName.trim(), ignoreCase = true)) t = t.substring(seriesName.trim().length)
    t = t.replace(Regex("(?i)\\b(Teil|Kapitel|Part)\\s*\\d+\\b"), "")
    return t.trim(' ', '-', '–', '—', ':', '·', ',', '.', '(', ')').trim()
}

/** Kategorie als Chip; Antippen öffnet die Auswahl Männer / Frauen / Gemischt. */
@Composable
internal fun CategoryChip(category: StoryCategory, onChange: (StoryCategory) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(categoryLabel(category) + " ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in StoryCategory.entries) {
                DropdownMenuItem(
                    text = { Text((if (c == category) "✓ " else "") + categoryLabel(c)) },
                    onClick = { open = false; if (c != category) onChange(c) },
                )
            }
        }
    }
}

/** Vorschlag für den Seriennamen: Titel der zuerst gewählten Geschichte ohne „Teil 1“. */
private fun firstTitleOf(selection: List<String>, items: List<ListItem>): String {
    val first = selection.firstOrNull() ?: return ""
    val title = items.firstNotNullOfOrNull { i ->
        when (i) {
            is ListItem.Single -> i.row.title.takeIf { i.row.uuid == first }
            is ListItem.SeriesGroup -> i.name.takeIf { i.parts.any { it.uuid == first } }
            else -> null
        }
    }.orEmpty()
    return title.replace(Regex("\\s*[–-]?\\s*(Teil|Kapitel|Part)\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
}

internal fun formatWords(n: Int): String = if (n >= 1000) "${n / 1000} Tsd. Wörter" else "$n Wörter"

@Composable
private fun LetterBar(letterPages: Map<Char, Int>, onPage: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().padding(top = 80.dp, bottom = 110.dp),
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
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
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


/** Ansicht: Darstellung (Liste, Baum, Ordner) und Sortierung. */
@Composable
private fun StoryViewDialog(filter: StoryFilter, onDismiss: () -> Unit, onChange: (StoryFilter) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        title = { Text("Ansicht") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Darstellung", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in StoryView.entries) FilterChip(selected = filter.view == v, onClick = { onChange(filter.copy(view = v)) }, label = { Text(v.label) })
                }
                Text("Sortierung", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in StorySort.entries) FilterChip(selected = filter.sort == o, onClick = { onChange(filter.copy(sort = o)) }, label = { Text(o.label) })
                }
            }
        },
    )
}

/** Filter: Favoriten, Weiterschreiben, Lesestand; "Zurücksetzen" löscht alle drei. */
@Composable
private fun StoryFilterDialog(filter: StoryFilter, onDismiss: () -> Unit, onChange: (StoryFilter) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        dismissButton = {
            TextButton(onClick = { onChange(filter.copy(favoritesOnly = false, continueOnly = false, readState = ReadState.ALL)) }) { Text("Zurücksetzen") }
        },
        title = { Text("Filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter.favoritesOnly, onClick = { onChange(filter.copy(favoritesOnly = !filter.favoritesOnly)) }, label = { Text("♥ Favoriten") })
                    FilterChip(selected = filter.continueOnly, onClick = { onChange(filter.copy(continueOnly = !filter.continueOnly)) }, label = { Text("✏ Weiterschreiben") })
                    FilterChip(selected = filter.partsMissingOnly, onClick = { onChange(filter.copy(partsMissingOnly = !filter.partsMissingOnly)) }, label = { Text("🧩 Teile fehlen") })
                }
                FilterChip(
                    selected = filter.maxWords != null,
                    onClick = { onChange(filter.copy(maxWords = if (filter.maxWords == null) SHORT_STORY_WORDS else null)) },
                    label = { Text("✂ Kurz: unter $SHORT_STORY_WORDS Wörter") },
                )
                Text("Lesestand", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in ReadState.entries) {
                        FilterChip(selected = filter.readState == r, onClick = { onChange(filter.copy(readState = r)) }, label = { Text(r.label) })
                    }
                }
            }
        },
    )
}

/** Grenze für „kurze Geschichten“ (Bruchstücke, die man aufräumen möchte). */
internal const val SHORT_STORY_WORDS = 200

/** Schränkt irgendetwas die Liste ein? Dann löscht „Angezeigte löschen“ nur diese, sonst alle. */
internal fun filterActive(f: StoryFilter): Boolean =
    f.query.isNotBlank() || f.category != null || f.favoritesOnly || f.continueOnly || f.partsMissingOnly || f.maxWords != null ||
        f.readState != ReadState.ALL || f.folder != null || f.view == StoryView.FOLDERS
