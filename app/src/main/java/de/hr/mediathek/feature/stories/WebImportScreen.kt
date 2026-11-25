package de.hr.mediathek.feature.stories

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.StoryCategory

/** Geschichten von einer Webseite oder einem Forum holen: Link rein, Vorschau, speichern. */
@Composable
fun WebImportScreen(onBack: () -> Unit, onLogin: (String) -> Unit, viewModel: WebImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.phase == WebPhase.LOADING || state.phase == WebPhase.SAVING
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.retryAfterLogin() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.cancel(); viewModel.reset(); onBack() }) { Text("← Zurück") }
            Text("Von Webseite holen", style = MaterialTheme.typography.titleLarge)
        }

        if (state.phase == WebPhase.IDLE || state.phase == WebPhase.LOADING) {
            Text(
                "Link einfügen oder im Browser auf „Teilen“ tippen und „ABDL Medien“ wählen. Bei Foren werden alle Seiten des " +
                    "Themas gelesen. Titel, Datum und Text kommen in die Geschichten, der Link wird mit gespeichert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text("Link") },
                singleLine = true,
                enabled = state.phase == WebPhase.IDLE,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.load() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.phase == WebPhase.LOADING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(if (state.pagesRead == 0) "Seite wird geladen" else "${state.pagesRead} Seite(n) gelesen")
                    TextButton(onClick = viewModel::cancel) { Text("Abbrechen") }
                }
            } else {
                Button(onClick = viewModel::load, enabled = state.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Laden") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loginNeeded && state.phase == WebPhase.IDLE) {
                Text(
                    "Melde dich mit deinem eigenen Konto an. Die Anmeldung bleibt gespeichert, du musst sie nur einmal machen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { viewModel.loginOpened(); onLogin(state.url.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("Anmelden") }
            }
        }

        if (state.phase == WebPhase.PREVIEW || state.phase == WebPhase.SAVING) {
            val thread = state.thread
            if (thread != null) {
                Text(
                    "${thread.host} · ${thread.pages} Seite(n) · ${state.parts.size} Beitrag/Beiträge",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                thread.warnings.forEach { Text("ⓘ $it", style = MaterialTheme.typography.bodySmall, color = de.hr.mediathek.core.ui.theme.LocalAppColors.current.gold) }
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Titel (bei mehreren Kapiteln: Name der Serie)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in StoryCategory.entries) {
                        FilterChip(selected = state.category == c, onClick = { viewModel.setCategory(c) }, label = { Text(categoryLabel(c)) }, enabled = !busy)
                    }
                }
                val count = state.parts.count { it.selected }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = viewModel::save, enabled = count > 0 && !busy) {
                        Text(if (count == 1) "1 Geschichte speichern" else "$count Kapitel als Serie speichern")
                    }
                    if (state.parts.size > 1) {
                        OutlinedButton(onClick = { viewModel.selectAll(count != state.parts.size) }, enabled = !busy) {
                            Text(if (count == state.parts.size) "Keine" else "Alle")
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(state.parts, key = { it.part.id }) { ui ->
                        Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = ui.selected, onCheckedChange = { viewModel.setSelected(ui.part.id, it) }, enabled = !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text(ui.part.postTitle?.takeIf { it.isNotBlank() } ?: "Beitrag", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            (ui.part.author ?: "Verfasser unbekannt") + " · ${ui.part.text.length} Zeichen",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = ui.dateText,
                                    onValueChange = { viewModel.setDateText(ui.part.id, it) },
                                    label = { Text("Erstellt am (TT.MM.JJJJ HH:MM)") },
                                    singleLine = true,
                                    enabled = !busy && ui.selected,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    ui.part.text.take(400) + if (ui.part.text.length > 400) " …" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (state.phase == WebPhase.SAVING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Wird gespeichert")
                }
            }
        }

        if (state.phase == WebPhase.DONE) {
            val r = state.report
            Card(colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Fertig", style = MaterialTheme.typography.titleMedium)
                    if (r != null) {
                        Text("Neu angelegt: ${r.created} · Aktualisiert: ${r.updated} · Unverändert: ${r.unchanged}")
                        r.duplicates.forEach { Text("Übersprungen: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                        r.errors.forEach { Text("Fehler: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Weiteren Link holen") }
            OutlinedButton(onClick = { viewModel.reset(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Zu den Geschichten") }
        }
    }
}
