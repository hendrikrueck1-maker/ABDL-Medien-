package de.hr.mediathek.feature.stories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import de.hr.mediathek.data.story.mdimport.ImportIssue

private const val MAX_SHOWN = 200

@Composable
fun MarkdownImportScreen(onBack: () -> Unit, viewModel: MarkdownImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importFolder(uri)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importZip(uri)
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importJson(uri)
    }
    val reportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.saveReport(uri)
    }
    val busy = state.phase == MdPhase.PREPARING || state.phase == MdPhase.RUNNING

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.reset(); onBack() }, enabled = !busy) { Text("← Zurück") }
            Text("Markdown-Import", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Liest alle *.md-Dateien mit YAML-Frontmatter (title, category, tags, status, author, createdAt …). " +
                "Mehrfaches Importieren ist sicher: Der Dateiname dient als Kennung, vorhandene Geschichten werden aktualisiert, " +
                "gleicher Text wird übersprungen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.phase == MdPhase.IDLE) {
            Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Ordner wählen (content/stories)") }
            OutlinedButton(
                onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ZIP-Datei wählen") }
            OutlinedButton(
                onClick = { jsonPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("JSON-Datei wählen") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (busy) {
            val p = state.progress
            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p == null || state.phase == MdPhase.PREPARING) {
                        Text("${state.label} wird durchsucht …")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text("${p.done} von ${p.total} Dateien", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                        if (p.current.isNotEmpty()) Text(p.current, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(
                            "Bitte den Bildschirm geöffnet lassen. Bereits gespeicherte Geschichten bleiben bei einem Abbruch erhalten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = viewModel::cancel) { Text("Abbrechen") }
                }
            }
        }
        state.report?.let { r ->
            Card(colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (r.cancelled) "Import abgebrochen" else "Import abgeschlossen", style = MaterialTheme.typography.titleMedium)
                    Text("Neu: ${r.created}   Aktualisiert: ${r.updated}   Unverändert: ${r.unchanged}")
                    Text("Duplikate übersprungen: ${r.duplicates.size}   Fehler: ${r.errors.size}")
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reportSaver.launch("import-report.txt") }) { Text("Report speichern") }
                Button(onClick = viewModel::reset) { Text("Fertig") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                issueSection("Fehler", r.errors, error = true)
                issueSection("Duplikate", r.duplicates, error = false)
                issueSection("Hinweise", r.notes, error = false)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.issueSection(title: String, issues: List<ImportIssue>, error: Boolean) {
    if (issues.isEmpty()) return
    item { Text("$title (${issues.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
    items(issues.take(MAX_SHOWN)) { i ->
        Column {
            Text(i.file, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(i.reason, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (issues.size > MAX_SHOWN) item { Text("… ${issues.size - MAX_SHOWN} weitere im gespeicherten Report", style = MaterialTheme.typography.bodySmall) }
}
