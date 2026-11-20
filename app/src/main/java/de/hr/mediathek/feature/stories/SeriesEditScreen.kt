package de.hr.mediathek.feature.stories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors

@Composable
fun SeriesEditScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: SeriesEditViewModel = hiltViewModel()) {
    val parts by viewModel.parts.collectAsStateWithLifecycle()
    val savedName by viewModel.name.collectAsStateWithLifecycle()
    val dissolved by viewModel.dissolved.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var nameLoaded by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDissolve by remember { mutableStateOf(false) }

    LaunchedEffect(savedName) { if (!nameLoaded && savedName.isNotEmpty()) { name = savedName; nameLoaded = true } }
    LaunchedEffect(dissolved) { if (dissolved) onBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Serie bearbeiten", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; viewModel.saveName(it) },
            label = { Text("Name der Serie") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAdd = true }) { Text("+ Geschichten hinzufügen") }
            OutlinedButton(onClick = viewModel::sortByTitle, enabled = parts.size > 1) { Text("Nach Titel ordnen") }
        }
        Text("${parts.size} Kapitel. Mit ▲ ▼ änderst du die Reihenfolge, mit ✕ nimmst du ein Kapitel aus der Serie (die Geschichte bleibt erhalten).", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(parts, key = { _, p -> p.uuid }) { index, p ->
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                        Text(p.title, modifier = Modifier.weight(1f).clickable { onOpen(p.uuid) }.padding(vertical = 12.dp), maxLines = 2)
                        TextButton(onClick = { viewModel.move(p, true) }, enabled = index > 0) { Text("▲") }
                        TextButton(onClick = { viewModel.move(p, false) }, enabled = index < parts.lastIndex) { Text("▼") }
                        TextButton(onClick = { viewModel.remove(p) }) { Text("✕") }
                    }
                }
            }
        }
        TextButton(onClick = { confirmDissolve = true }) { Text("Serie auflösen", color = MaterialTheme.colorScheme.error) }
    }

    if (showAdd) AddPartsDialog(viewModel, onDismiss = { showAdd = false })
    if (confirmDissolve) {
        AlertDialog(
            onDismissRequest = { confirmDissolve = false },
            title = { Text("Serie auflösen?") },
            text = { Text("Die Serie wird entfernt. Alle ${parts.size} Geschichten bleiben erhalten, sind aber nicht mehr zusammengefasst.") },
            confirmButton = { TextButton(onClick = { confirmDissolve = false; viewModel.dissolve() }) { Text("Auflösen") } },
            dismissButton = { TextButton(onClick = { confirmDissolve = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun AddPartsDialog(viewModel: SeriesEditViewModel, onDismiss: () -> Unit) {
    val all by viewModel.candidates.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val shown = remember(all, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) all else all.filter { it.row.title.lowercase().contains(q) || it.row.author?.lowercase()?.contains(q) == true }).take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Geschichten hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Suchen") }, modifier = Modifier.fillMaxWidth())
                Text("Die gewählten kommen ans Ende der Serie. Liegt eine Geschichte schon in einer anderen Serie, wird sie dorthin herausgenommen.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shown, key = { it.row.uuid }) { c ->
                        val on = c.row.uuid in selected
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) selected.remove(c.row.uuid) else selected.add(c.row.uuid) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (it) selected.add(c.row.uuid) else selected.remove(c.row.uuid) })
                            Column {
                                Text(c.row.title, maxLines = 2)
                                c.currentSeries?.let { Text("in Serie: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                if (all.size > shown.size) Text("Nur die ersten 200 Treffer; Suche eingrenzen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.add(selected.toList()); onDismiss() }, enabled = selected.isNotEmpty()) { Text("${selected.size} hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
