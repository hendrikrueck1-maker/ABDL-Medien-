package de.hr.mediathek.feature.stories

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import de.hr.mediathek.data.story.organize.ExportScope

@Composable
fun StoryToolsScreen(onBack: () -> Unit, viewModel: StoryToolsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var scope by remember { mutableStateOf<ExportScope>(ExportScope.All) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri, scope)
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri, scope, StoryToolsViewModel.Format.JSON)
    }
    val linksLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.export(uri, scope, StoryToolsViewModel.Format.LINKS)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Statistik und Export", style = MaterialTheme.typography.headlineMedium)
        }
        Card(colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Deine Geschichten", style = MaterialTheme.typography.titleMedium)
                Text("${stats.stories} Geschichten in ${stats.series} Serien")
                Text("Männer ${stats.men} · Frauen ${stats.women} · Gemischt ${stats.mixed}")
                LinearProgressIndicator(
                    progress = { if (stats.stories == 0) 0f else stats.done.toFloat() / stats.stories },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Gelesen ${stats.done} · Angefangen ${stats.started} · Ungelesen ${stats.unread}")
                if (stats.words > 0) {
                    Text("Insgesamt etwa ${stats.words / 1000} Tsd. Wörter")
                    Text("Schon gelesen: etwa ${stats.wordsRead / 1000} Tsd. Wörter (ca. ${formatMinutes(stats.minutesRead)})")
                    Text("Noch ungelesen: ca. ${formatMinutes(stats.minutesLeft)} Lesezeit")
                }
            }
        }
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export als ZIP mit Markdown-Dateien", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Du wählst, wo die Datei gespeichert wird (z. B. auf der SD-Karte). Die Dateien lassen sich über den Markdown-Import wieder einlesen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { scope = ExportScope.All; launcher.launch("geschichten.zip") },
                    enabled = !export.running,
                ) { Text("Alle Geschichten exportieren") }
                OutlinedButton(
                    onClick = { scope = ExportScope.All; jsonLauncher.launch("geschichten.json") },
                    enabled = !export.running,
                ) { Text("Alle als JSON-Datei exportieren (mit Datum und Link)") }
                Text(
                    "Handy → Tablet: Die JSON-Datei überträgt alles (Bewertung, Favorit, „weiterschreiben“, Datum). " +
                        "Alternativ nur die Links exportieren und am Tablet in die Link-Liste einfügen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { scope = ExportScope.All; linksLauncher.launch("geschichten-links.txt") },
                    enabled = !export.running,
                ) { Text("Nur die Links exportieren (Textdatei)") }
                for (f in folders) {
                    OutlinedButton(
                        onClick = { scope = ExportScope.Folder(f.uuid); launcher.launch("${f.name}.zip") },
                        enabled = !export.running,
                    ) { Text("Ordner „${f.name}“ exportieren") }
                }
                if (sources.isNotEmpty()) {
                    Text("Nur eine Seite / Quelle", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Exportiert nur die Geschichten dieser Seite – als ZIP (Markdown) oder JSON.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for ((key, count) in sources) {
                    val label = key.ifEmpty { "Ohne Quelle (eigene)" }
                    val file = safeFileName(key.ifEmpty { "ohne-quelle" })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope = ExportScope.Source(key); launcher.launch("$file.zip") },
                            enabled = !export.running,
                        ) { Text("ZIP") }
                        OutlinedButton(
                            onClick = { scope = ExportScope.Source(key); jsonLauncher.launch("$file.json") },
                            enabled = !export.running,
                        ) { Text("JSON") }
                        Text("$label ($count)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                if (export.running) {
                    Text("Exportiere … ${export.done} von ${export.total}")
                    if (export.total > 0) LinearProgressIndicator(progress = { export.done.toFloat() / export.total }, modifier = Modifier.fillMaxWidth())
                }
                export.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

private fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "geschichten" }

private fun formatMinutes(m: Long): String = if (m >= 120) "${m / 60} Std." else "$m Min."
