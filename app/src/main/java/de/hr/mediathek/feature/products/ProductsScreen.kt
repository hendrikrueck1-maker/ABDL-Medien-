package de.hr.mediathek.feature.products

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import de.hr.mediathek.core.ui.AccentButton
import de.hr.mediathek.core.ui.AppCard
import de.hr.mediathek.core.ui.OutlineAccentButton
import de.hr.mediathek.core.ui.theme.LocalAppColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch

fun starsText(stars: Int?): String = if (stars == null) "–" else "★".repeat(stars) + "☆".repeat(5 - stars)

@Composable
fun ProductsScreen(
    onOpen: (String) -> Unit,
    onImport: () -> Unit,
    viewModel: ProductsViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { if (it != null) viewModel.exportCsv(it) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { if (it != null) viewModel.exportPdf(it) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Bedienknöpfe links (linke Hand), dann Titel.
                AccentButton(onClick = { scope.launch { onOpen(viewModel.createEmpty()) } }, color = LocalAppColors.current.gold) { Text("Neu") }
                OutlineAccentButton(onClick = onImport, color = LocalAppColors.current.gold) { Text("Import") }
                Text("Produkte", style = MaterialTheme.typography.headlineMedium, color = LocalAppColors.current.onCard, modifier = Modifier.weight(1f))
            }
        }
        OutlinedTextField(
            value = filter.query, onValueChange = { q -> viewModel.update { it.copy(query = q) } },
            label = { Text("Suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter.favoritesOnly, onClick = { viewModel.update { it.copy(favoritesOnly = !it.favoritesOnly) } }, label = { Text("★ Favoriten") })
            for (s in ProductSort.entries) {
                FilterChip(selected = filter.sort == s, onClick = { viewModel.update { f -> f.copy(sort = s) } }, label = { Text(s.label) })
            }
        }
        if (brands.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in brands) {
                    FilterChip(
                        selected = filter.brandUuid == b.uuid,
                        onClick = { viewModel.update { f -> f.copy(brandUuid = if (f.brandUuid == b.uuid) null else b.uuid) } },
                        label = { Text(b.name) },
                    )
                }
            }
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${items.size} Produkte", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { csvPicker.launch("produkte.csv") }, enabled = items.isNotEmpty()) { Text("CSV") }
            TextButton(onClick = { pdfPicker.launch("produkte.pdf") }, enabled = items.isNotEmpty()) { Text("PDF") }
        }
        if (items.isEmpty()) Text("Noch keine Produkte. Mit \"Neu\" anlegen oder per \"Import\" aus einem PDF holen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.row.uuid }) { item ->
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth().clickable { onOpen(item.row.uuid) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.row.title.ifBlank { "Ohne Titel" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(item.brandName, item.unitPrice?.let { "%.2f € pro Stück".format(java.util.Locale.GERMANY, it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(starsText(item.row.stars), color = LocalAppColors.current.gold)
                        }
                        Text(
                            if (item.row.favorite) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
                            color = LocalAppColors.current.gold,
                            modifier = Modifier.clickable { viewModel.toggleFavorite(item) }.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
    message?.let {
        AlertDialog(onDismissRequest = viewModel::dismissMessage, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } }, text = { Text(it) })
    }
}
