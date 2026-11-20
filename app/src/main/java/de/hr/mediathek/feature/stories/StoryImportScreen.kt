package de.hr.mediathek.feature.stories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.StoryFileKind
import de.hr.mediathek.data.story.TextEncoding

@Composable
fun StoryImportScreen(onBack: () -> Unit, viewModel: StoryImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.loadFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.loadFolder(uri)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.reset(); onBack() }) { Text("← Zurück") }
            Text("Geschichten importieren", style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                filePicker.launch(
                    arrayOf(
                        "text/plain",
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/octet-stream",
                    ),
                )
            }) { Text("Dateien wählen") }
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Ordner wählen") }
        }
        state.importedCount?.let { Text("$it Geschichten importiert.", color = MaterialTheme.colorScheme.primary) }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Dateien werden gelesen")
            }
        }
        if (state.items.isNotEmpty()) {
            Text("Kategorie für alle:", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in StoryCategory.entries) {
                    FilterChip(selected = state.category == c, onClick = { viewModel.setCategory(c) }, label = { Text(categoryLabel(c)) })
                }
            }
            val count = state.items.count { it.selected }
            Button(onClick = viewModel::importSelected, enabled = count > 0) { Text("$count importieren") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(state.items, key = { it.id }) { item ->
                    Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.selected, onCheckedChange = { viewModel.setSelected(item.id, it) }, enabled = item.error == null)
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                            }
                            if (item.error != null) {
                                Text(item.error!!, color = MaterialTheme.colorScheme.error)
                            } else {
                                item.duplicateOf?.let {
                                    Text("Achtung: gleicher Text wie \"$it\"", color = MaterialTheme.colorScheme.error)
                                }
                                item.encoding?.let { enc ->
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (e in TextEncoding.entries) {
                                            FilterChip(selected = enc == e, onClick = { viewModel.setEncoding(item.id, e) }, label = { Text(e.label) })
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Switch(checked = item.smooth, onCheckedChange = { viewModel.setSmooth(item.id, it) })
                                    Text("Zeilenumbrüche glätten")
                                }
                                if (item.kind == StoryFileKind.PDF) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Switch(checked = item.removeRepeating, onCheckedChange = { viewModel.setRemoveRepeating(item.id, it) })
                                        Text("Kopf-/Fußzeilen und Seitenzahlen entfernen")
                                    }
                                    if (item.removed.isNotEmpty()) {
                                        Text(
                                            "Entfernt: " + item.removed.distinct().take(4).joinToString(" | "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    item.text.take(500) + if (item.text.length > 500) " …" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("${item.text.length} Zeichen", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
