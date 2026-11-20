package de.hr.mediathek.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val KINDS = listOf("VIDEO" to "Videos", "IMAGE" to "Bilder", "STORY" to "Geschichten", "PRODUCT" to "Produkte")

private fun kindLabel(kind: String) = KINDS.firstOrNull { it.first == kind }?.second ?: kind

@Composable
fun SearchScreen(onOpen: (kind: String, uuid: String) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Suche", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = s.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Suchbegriffe, \"Phrase\", -ausschließen") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = s.kinds.isEmpty(), onClick = viewModel::clearKinds, label = { Text("Alle") })
            for ((kind, label) in KINDS) {
                FilterChip(selected = kind in s.kinds, onClick = { viewModel.toggleKind(kind) }, label = { Text(label) })
            }
        }
        if (s.indexing) {
            Text("Suchindex wird aktualisiert …", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!s.searched && s.history.isNotEmpty()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Zuletzt gesucht", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearHistory) { Text("Verlauf löschen") }
            }
            LazyColumn {
                items(s.history) { h ->
                    Text(h, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { viewModel.setQuery(h) }.padding(vertical = 12.dp))
                }
            }
        } else if (s.searched && s.results.isEmpty()) {
            Text("Keine Treffer.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(s.results, key = { it.kind + it.uuid }) { hit ->
                    Column(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            .clickable { viewModel.remember(); onOpen(hit.kind, hit.uuid) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(kindLabel(hit.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (hit.kind == "STORY" || hit.kind == "PRODUCT") {
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
