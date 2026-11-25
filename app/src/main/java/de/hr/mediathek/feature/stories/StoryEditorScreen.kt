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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.data.db.StoryCategory
import java.text.DateFormat
import java.util.Date

@Composable
fun StoryEditorScreen(onBack: () -> Unit, viewModel: StoryEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val recentSources by viewModel.recentSources.collectAsStateWithLifecycle()
    val recentUrls by viewModel.recentUrls.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.flush() } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.flush(); onBack() }) { Text("← Fertig") }
            Text(
                if (s.saved) "Gespeichert" else "Speichert …",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { viewModel.showDrafts(true) }) { Text("Frühere Fassungen") }
        }
        if (s.loaded) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.title, onValueChange = { v -> viewModel.change { it.copy(title = v) } },
                    label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in StoryCategory.entries) {
                        FilterChip(
                            selected = s.category == c,
                            onClick = { viewModel.change { it.copy(category = c) } },
                            label = { Text(categoryLabel(c)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = s.source, onValueChange = { v -> viewModel.change { it.copy(source = v) } },
                    label = { Text("Quelle / Website") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                QuickPick(recentSources, s.source, { it }) { pick -> viewModel.change { it.copy(source = pick) } }
                OutlinedTextField(
                    value = s.url, onValueChange = { v -> viewModel.change { it.copy(url = v) } },
                    label = { Text("Adresse (URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                QuickPick(recentUrls, s.url, ::shortUrl) { pick -> viewModel.change { it.copy(url = pick) } }
                OutlinedTextField(
                    value = s.published, onValueChange = { v -> viewModel.change { it.copy(published = v) } },
                    label = { Text("Erstellt am (Original, TT.MM.JJJJ HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = s.published.isNotBlank() && de.hr.mediathek.data.story.web.WebDates.parseInput(s.published) == null,
                    supportingText = if (s.published.isNotBlank() && de.hr.mediathek.data.story.web.WebDates.parseInput(s.published) == null) {
                        { Text("Datum nicht erkannt – das bisherige Datum bleibt") }
                    } else null,
                )
                OutlinedTextField(
                    value = s.text, onValueChange = { v -> viewModel.change { it.copy(text = v) } },
                    label = { Text("Text (hier einfügen oder schreiben)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 12,
                )
            }
        }
    }

    s.drafts?.let { drafts ->
        AlertDialog(
            onDismissRequest = { viewModel.showDrafts(false) },
            confirmButton = { TextButton(onClick = { viewModel.showDrafts(false) }) { Text("Schließen") } },
            title = { Text("Frühere Fassungen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (drafts.isEmpty()) Text("Noch keine früheren Fassungen.")
                    for (d in drafts) {
                        OutlinedButton(onClick = { viewModel.restore(d) }, modifier = Modifier.fillMaxWidth()) {
                            Text(DateFormat.getDateTimeInstance().format(Date(d.savedAt)) + " · ${d.text.length} Zeichen")
                        }
                    }
                }
            },
        )
    }
}

/** Kurze Anzeige einer Adresse auf der Schnellauswahl-Taste: ohne Protokoll, "www." und Schrägstrich am Ende. */
private fun shortUrl(url: String): String {
    val short = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    return if (short.length > 34) short.take(33) + "…" else short
}

/** Tasten mit den zuletzt verwendeten Einträgen; ein Tipp übernimmt den Wert, die gewählte Taste ist hervorgehoben. */
@Composable
private fun QuickPick(items: List<String>, current: String, label: (String) -> String, onPick: (String) -> Unit) {
    if (items.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🕘", style = MaterialTheme.typography.titleMedium)
        items.forEach { item ->
            FilterChip(selected = item == current, onClick = { onPick(item) }, label = { Text(label(item), maxLines = 1) })
        }
    }
}
