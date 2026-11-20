package de.hr.mediathek.feature.stories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun SeriesSuggestScreen(onBack: () -> Unit, viewModel: SeriesSuggestViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Serien erkennen", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = viewModel::load, enabled = !s.loading) { Text("Neu suchen") }
        }
        if (s.loading) CircularProgressIndicator()
        s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (!s.loading && s.items.isEmpty() && s.message == null) {
            Text("Keine neuen Serien gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.items.isNotEmpty()) {
            Text(
                "Diese Geschichten gehören laut Titel zusammen (Teil/Kapitel/Nummer). Prüfe die Vorschläge, ändere bei Bedarf den Namen und bestätige.",
                style = MaterialTheme.typography.bodySmall,
            )
            val selected = s.items.count { it.selected }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::applySelected, enabled = selected > 0) { Text("$selected übernehmen") }
                OutlinedButton(onClick = { viewModel.selectAll(selected < s.items.size) }) { Text(if (selected < s.items.size) "Alle wählen" else "Keine wählen") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(s.items, key = { _, it -> it.id }) { index, item ->
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.selected, onCheckedChange = { viewModel.toggle(index) })
                            OutlinedTextField(
                                value = item.name,
                                onValueChange = { viewModel.rename(index, it) },
                                singleLine = true,
                                label = { Text(if (item.proposal.existingSeriesUuid != null) "Zu vorhandener Serie" else "Serienname") },
                                enabled = item.proposal.existingSeriesUuid == null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        for (p in item.proposal.parts) Text("${p.order}. ${p.title}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
