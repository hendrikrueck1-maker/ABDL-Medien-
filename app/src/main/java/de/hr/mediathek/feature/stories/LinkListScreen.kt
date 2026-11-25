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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.web.LinkImportState
import de.hr.mediathek.data.story.web.LinkImportStore

private const val MAX_FAILED_SHOWN = 100
private const val PREVIEW_LINKS = 20

/** Viele Links auf einmal importieren. Läuft als Hintergrundauftrag mit Benachrichtigung weiter. */
@Composable
fun LinkListScreen(onBack: () -> Unit, viewModel: LinkListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val job by viewModel.job.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val c = LocalAppColors.current
    val active = job != null
    val showResult = !active && progress.total > 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Zurück bricht nichts ab: der Auftrag läuft im Hintergrund weiter.
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text("Link-Liste importieren", style = MaterialTheme.typography.titleLarge)
        }

        if (active) {
            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val waiting = job?.status == JobStatus.QUEUED
                    Text(
                        if (waiting) "Wartet auf den Start … (${progress.done} von ${progress.total})" else "${progress.done} von ${progress.total}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                    if (progress.current.isNotEmpty()) {
                        Text(progress.current, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = c.onCardVariant)
                    }
                    Counts(progress)
                    progress.message?.let { Text("ⓘ $it", style = MaterialTheme.typography.bodySmall, color = c.gold) }
                    Text(
                        "Läuft im Hintergrund weiter (Benachrichtigung), auch wenn du die Seite oder die App verlässt.",
                        style = MaterialTheme.typography.bodySmall, color = c.onCardVariant,
                    )
                    OutlinedButton(onClick = viewModel::cancel) { Text("Anhalten") }
                }
            }
        }

        if (showResult) {
            Card(colors = glassCardColors(MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (progress.unfinished) "Angehalten: ${progress.done} von ${progress.total} Links bearbeitet"
                        else "Fertig: ${progress.total} Links bearbeitet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Counts(progress)
                    progress.message?.let { Text("ⓘ $it", style = MaterialTheme.typography.bodySmall, color = c.gold) }
                    if (progress.unfinished) Button(onClick = viewModel::resume, modifier = Modifier.fillMaxWidth()) { Text("Fortsetzen") }
                }
            }
            if (progress.failed.isNotEmpty()) {
                Text("Nicht geklappt (${progress.failed.size}):", style = MaterialTheme.typography.titleSmall)
                for (f in progress.failed.take(MAX_FAILED_SHOWN)) {
                    Text("${f.url}\n→ ${f.reason}", style = MaterialTheme.typography.bodySmall, color = c.warnung)
                }
                if (progress.failed.size > MAX_FAILED_SHOWN) Text("… und ${progress.failed.size - MAX_FAILED_SHOWN} weitere", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::retryFailed) { Text("Noch einmal versuchen") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(viewModel.failedLinksText())) }) { Text("Kopieren") }
                }
            }
            OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Ergebnis schließen, neue Liste") }
        }

        if (!active && !showResult) InputPart(state, viewModel)
    }
}

@Composable
private fun InputPart(state: LinkListUi, viewModel: LinkListViewModel) {
    val c = LocalAppColors.current
    Text(
        "Füge die Links ein oder sammle sie aus einem Forum-Bereich. Die App holt sie im Hintergrund nacheinander (etwa eine Sekunde pro Seite) " +
            "und speichert Titel, Datum und Text. Links, die schon in der App sind, werden übersprungen.",
        style = MaterialTheme.typography.bodySmall,
        color = c.onCardVariant,
    )
    Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Links automatisch sammeln", style = MaterialTheme.typography.titleSmall)
            Text(
                "Link der Übersichtsseite eines Forum-Bereichs einfügen (die Seite mit der Liste aller Themen). " +
                    "Die App liest alle Seiten durch, beginnt dabei immer bei Seite 1 und trägt alle Themen-Links in die Liste ein.",
                style = MaterialTheme.typography.bodySmall,
                color = c.onCardVariant,
            )
            OutlinedTextField(
                value = state.collectUrl,
                onValueChange = viewModel::setCollectUrl,
                label = { Text("Link der Übersichtsseite") },
                singleLine = true,
                enabled = !state.collecting,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.collecting) {
                Text("Seite ${state.collectPages} gelesen · ${state.collectLinks} Links gefunden")
                OutlinedButton(onClick = viewModel::cancelCollect) { Text("Abbrechen") }
            } else {
                Button(onClick = viewModel::collect, enabled = state.collectUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Links sammeln") }
            }
            state.collectMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    OutlinedTextField(
        value = state.input,
        onValueChange = viewModel::setInput,
        label = { Text("Links einfügen (pro Zeile einer)") },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = viewModel::addInput, enabled = state.input.isNotBlank()) { Text("Zur Liste hinzufügen") }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.gold) }

    Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (state.links.size == 1) "1 Link in der Liste" else "${state.links.size} Links in der Liste", style = MaterialTheme.typography.titleSmall)
            for (l in state.links.take(PREVIEW_LINKS)) {
                Text(l, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onCardVariant)
            }
            if (state.links.size > PREVIEW_LINKS) Text("… und ${state.links.size - PREVIEW_LINKS} weitere", style = MaterialTheme.typography.bodySmall)
            if (state.links.isNotEmpty()) TextButton(onClick = viewModel::clearLinks) { Text("Liste leeren") }
        }
    }
    Text("Kategorie für alle:", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (cat in StoryCategory.entries) {
            FilterChip(selected = state.category == cat, onClick = { viewModel.setCategory(cat) }, label = { Text(categoryLabel(cat)) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = state.skipShort, onCheckedChange = viewModel::setSkipShort)
        Text(
            "Kurze Beiträge auslassen (unter ${LinkImportStore.DEFAULT_MIN_CHARS} Zeichen, z. B. „Danke für die Kommentare“)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
    Button(onClick = viewModel::start, enabled = state.links.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (state.links.size == 1) "1 Link im Hintergrund importieren" else "${state.links.size} Links im Hintergrund importieren")
    }
}

@Composable
private fun Counts(s: LinkImportState) {
    Text(
        "Neu: ${s.created} · Aktualisiert: ${s.updated} · Schon vorhanden: ${s.skipped} · Gleicher Text: ${s.duplicates} · " +
            "Kurz ausgelassen: ${s.tooShort} · Fehler: ${s.failed.size}",
        style = MaterialTheme.typography.bodySmall,
    )
}
