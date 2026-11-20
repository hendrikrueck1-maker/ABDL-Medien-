package de.hr.mediathek.feature.stories

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.StoryCategory
import kotlinx.coroutines.launch

@Composable
fun StoriesScreen(
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onImport: () -> Unit,
    viewModel: StoriesViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val list by viewModel.stories.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (filter.trash) "Papierkorb" else "Geschichten",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            if (!filter.trash) {
                OutlinedButton(onClick = onImport) { Text("Importieren") }
                Button(onClick = { scope.launch { onEdit(viewModel.createEmpty()) } }) { Text("Neu") }
            }
            TextButton(onClick = { viewModel.update { it.copy(trash = !it.trash) } }) {
                Text(if (filter.trash) "Zurück" else "🗑")
            }
        }
        if (!filter.trash) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { q -> viewModel.update { it.copy(query = q) } },
                label = { Text("Suchen in Titel und Quelle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter.category == null, onClick = { viewModel.update { it.copy(category = null) } }, label = { Text("Alle") })
                for (c in StoryCategory.entries) {
                    FilterChip(
                        selected = filter.category == c,
                        onClick = { viewModel.update { f -> f.copy(category = if (f.category == c) null else c) } },
                        label = { Text(categoryLabel(c)) },
                    )
                }
                FilterChip(selected = filter.favoritesOnly, onClick = { viewModel.update { it.copy(favoritesOnly = !it.favoritesOnly) } }, label = { Text("♥ Favoriten") })
                FilterChip(selected = filter.continueOnly, onClick = { viewModel.update { it.copy(continueOnly = !it.continueOnly) } }, label = { Text("✏ Weiterschreiben") })
                for (s in StorySort.entries) {
                    FilterChip(selected = filter.sort == s, onClick = { viewModel.update { f -> f.copy(sort = s) } }, label = { Text(s.label) })
                }
            }
        }
        if (list.isEmpty()) {
            Text(
                if (filter.trash) "Der Papierkorb ist leer. Gelöschte Geschichten bleiben hier 30 Tage." else "Keine Geschichten gefunden.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.uuid }) { row ->
                Card(
                    colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !filter.trash) { onOpen(row.uuid) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            categoryLabel(row.category) + (row.sourceText?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (filter.trash) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.restore(row) }) { Text("Wiederherstellen") }
                                OutlinedButton(onClick = { viewModel.purge(row) }) { Text("Endgültig löschen") }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StarRow(row.rating) { viewModel.setRating(row, it) }
                                Text(
                                    if (row.favorite) "♥" else "♡",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { viewModel.toggleFavorite(row) }.padding(10.dp),
                                )
                                Text(
                                    "✏",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (row.continueFlag) 1f else 0.3f),
                                    modifier = Modifier.clickable { viewModel.toggleContinue(row) }.padding(10.dp),
                                )
                                Text(
                                    "🗑",
                                    modifier = Modifier.clickable { viewModel.delete(row) }.padding(10.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
