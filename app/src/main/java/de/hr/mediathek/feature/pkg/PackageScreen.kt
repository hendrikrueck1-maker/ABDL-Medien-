package de.hr.mediathek.feature.pkg

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.data.db.PackageSourceRow
import de.hr.mediathek.data.pkg.PackageLogic

private fun gb(bytes: Long) = "%.1f GB".format(java.util.Locale.GERMANY, bytes / PackageLogic.GB.toDouble())

@Composable
fun PackageScreen(onBack: () -> Unit, viewModel: PackageViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<PackageSourceRow?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.startExport(uri)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.startImport(uri)
    }
    val o = s.options

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text("Mobil-Paket", style = MaterialTheme.typography.titleLarge)
        }
        s.message?.let {
            Card(colors = glassCardColors(MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
        }

        // --- Export ---
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paket erstellen (Tablet)", style = MaterialTheme.typography.titleMedium)
                Text("Kopiert die gewählten Medien samt Bewertungen auf eine SD-Karte oder einen USB-Stick, zum Einbinden auf dem Handy.", style = MaterialTheme.typography.bodyMedium)
                Check("Favoriten-Videos", o.favVideos) { c -> viewModel.update { it.copy(favVideos = c) } }
                Check("Favoriten-Bilder", o.favImages) { c -> viewModel.update { it.copy(favImages = c) } }
                Check("Videos ab Bewertung", o.ratedVideos) { c -> viewModel.update { it.copy(ratedVideos = c) } }
                Check("Bilder ab Bewertung", o.ratedImages) { c -> viewModel.update { it.copy(ratedImages = c) } }
                if (o.ratedVideos || o.ratedImages) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ab")
                        for (n in 1..5) FilterChip(selected = o.minRating == n, onClick = { viewModel.update { it.copy(minRating = n) } }, label = { Text("$n ★") })
                    }
                }
                Check("Ungesehene Videos", o.unseenVideos) { c -> viewModel.update { it.copy(unseenVideos = c) } }
                if (s.playlists.isNotEmpty()) {
                    Text("Playlists", style = MaterialTheme.typography.labelLarge)
                    for (p in s.playlists) {
                        Check(p.name, p.uuid in o.playlists) { c ->
                            viewModel.update { it.copy(playlists = if (c) it.playlists + p.uuid else it.playlists - p.uuid) }
                        }
                    }
                }
                Text("Größenlimit", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in listOf(5, 8, 10)) FilterChip(selected = o.limitGb == n, onClick = { viewModel.update { it.copy(limitGb = n) } }, label = { Text("$n GB") })
                }
                val p = s.preview
                Text(
                    if (p.loading) "Auswahl wird berechnet …" else "${p.count} Dateien, ${gb(p.bytes)}" + if (p.dropped > 0) " (${p.dropped} wegen des Limits weggelassen)" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (p.overLimit) {
                    Text("Die Auswahl ist größer als das Limit.", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { viewModel.update { it.copy(truncate = true) } }) { Text("Auf Limit kürzen") }
                }
                if (o.truncate) TextButton(onClick = { viewModel.update { it.copy(truncate = false) } }) { Text("Kürzen aufheben") }
                Button(
                    onClick = { exportPicker.launch(null) },
                    enabled = !p.loading && p.count > 0 && !p.overLimit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Zielordner wählen und starten") }
                JobInfo(s.exportJob, viewModel::cancel)
            }
        }

        // --- Import ---
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paket einbinden (Handy)", style = MaterialTheme.typography.titleMedium)
                Text("Wähle den Paketordner auf der Karte. Die Medien werden geprüft, die Bewertungen zusammengeführt und die Dateien verknüpft, ohne zu kopieren.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { importPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Paketordner wählen") }
                JobInfo(s.importJob, viewModel::cancel)
            }
        }

        if (s.packages.isNotEmpty()) {
            Text("Eingebundene Pakete", style = MaterialTheme.typography.titleMedium)
            for (row in s.packages) {
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.label.removePrefix("Paket: "), style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { viewModel.startImport(android.net.Uri.parse(row.treeUri)) }) { Text("Aktualisieren") }
                            TextButton(onClick = { removeTarget = row }) { Text("Entfernen") }
                        }
                    }
                }
            }
        }
    }

    removeTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Paket entfernen?") },
            text = { Text("Die Einträge mit Bewertungen bleiben in der App und werden offline. Auf Wunsch werden zusätzlich die Dateien im Paketordner gelöscht.") },
            confirmButton = { TextButton(onClick = { viewModel.removePackage(row, false); removeTarget = null }) { Text("Nur entkoppeln") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.removePackage(row, true); removeTarget = null }) { Text("Dateien löschen") }
                    TextButton(onClick = { removeTarget = null }) { Text("Abbrechen") }
                }
            },
        )
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun JobInfo(job: JobEntity?, onCancel: (JobEntity) -> Unit) {
    if (job == null) return
    when (job.status) {
        JobStatus.QUEUED, JobStatus.RUNNING -> {
            Text(job.phase ?: "Wartet …", style = MaterialTheme.typography.bodyMedium)
            val p = job.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { onCancel(job) }) { Text("Abbrechen") }
        }
        JobStatus.COMPLETED -> Text(job.phase ?: "Fertig", style = MaterialTheme.typography.bodyMedium)
        JobStatus.FAILED -> Text(job.lastError ?: "Fehlgeschlagen", color = MaterialTheme.colorScheme.error)
        else -> Text("Abgebrochen. Ein neuer Start setzt angefangene Dateien fort.", style = MaterialTheme.typography.bodyMedium)
    }
}
