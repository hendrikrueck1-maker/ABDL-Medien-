package de.hr.mediathek.feature.stories

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.web.WebStories

private const val MAX_FAILED_SHOWN = 100

/** Viele Links auf einmal einfügen und nacheinander importieren. */
@Composable
fun LinkListScreen(onBack: () -> Unit, viewModel: LinkListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    // Während des Imports bleibt der Bildschirm an, sonst unterbricht Android die Verbindung.
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.cancel(); viewModel.reset(); onBack() }) { Text("← Zurück") }
            Text("Link-Liste importieren", style = MaterialTheme.typography.titleLarge)
        }

        if (!state.running && !state.finished) {
            Text(
                "Füge die Links ein, pro Zeile einer. Die App holt sie nacheinander (etwa eine Sekunde pro Seite) und speichert Titel, " +
                    "Datum und Text. Links, die schon in der App sind, werden übersprungen. Bei einer Unterbrechung einfach dieselbe Liste noch einmal starten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = { Text("Links") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            val count = WebStories.extractLinks(state.text).size
            Text("$count Link(s) erkannt", style = MaterialTheme.typography.labelMedium)
            Text("Kategorie für alle:", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in StoryCategory.entries) {
                    FilterChip(selected = state.category == c, onClick = { viewModel.setCategory(c) }, label = { Text(categoryLabel(c)) })
                }
            }
            Button(onClick = viewModel::start, enabled = count > 0, modifier = Modifier.fillMaxWidth()) { Text("$count Link(s) importieren") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        if (state.running) {
            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.done} von ${state.total}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                    Text(state.current, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Counts(state)
                    Text("Bitte die App geöffnet lassen.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = viewModel::cancel) { Text("Abbrechen") }
                }
            }
        }

        if (state.finished) {
            Card(colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Fertig: ${state.done} von ${state.total} Links bearbeitet", style = MaterialTheme.typography.titleMedium)
                    Counts(state)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (state.failed.isNotEmpty()) {
                Text("Nicht geklappt (${state.failed.size}):", style = MaterialTheme.typography.titleSmall)
                for (f in state.failed.take(MAX_FAILED_SHOWN)) {
                    Text("${f.url}\n→ ${f.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (state.failed.size > MAX_FAILED_SHOWN) Text("… und ${state.failed.size - MAX_FAILED_SHOWN} weitere", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(viewModel.failedLinksText())) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Fehlgeschlagene Links kopieren")
                }
            }
            Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Neue Liste") }
        }
    }
}

@Composable
private fun Counts(state: LinkListUi) {
    Text("Neu: ${state.created} · Schon vorhanden: ${state.skipped} · Gleicher Text: ${state.duplicates} · Fehler: ${state.failed.size}")
}
