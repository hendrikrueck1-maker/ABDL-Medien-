package de.hr.mediathek.feature.products

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.PhotoKind
import de.hr.mediathek.data.product.CriterionCatalog

private fun kindLabel(k: PhotoKind?): String = when (k) {
    PhotoKind.PACKAGE -> "Verpackung"
    PhotoKind.DETAIL -> "Produktfoto"
    PhotoKind.WORN_DRY -> "Trocken"
    PhotoKind.WORN_WET -> "Nass"
    null -> "Entfernt"
}

@Composable
fun ProductImportScreen(onBack: () -> Unit, viewModel: ProductImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var rawText by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.load(uris)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.reset(); onBack() }) { Text("← Zurück") }
            Text("Bewertungen importieren", style = MaterialTheme.typography.titleLarge)
        }
        Button(onClick = {
            picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        }) { Text("PDF oder Word-Dateien wählen") }
        state.savedCount?.let { Text("$it Produkte importiert.", color = MaterialTheme.colorScheme.primary) }
        if (state.loading || state.saving) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(if (state.saving) "Wird gespeichert" else "Dateien werden gelesen")
            }
        }
        if (state.items.isNotEmpty()) {
            val count = state.items.count { it.selected }
            Button(onClick = viewModel::saveSelected, enabled = count > 0 && !state.saving) { Text("$count importieren") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(state.items, key = { it.id }) { item ->
                    Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.selected, onCheckedChange = { viewModel.setSelected(item.id, it) }, enabled = item.error == null)
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                            }
                            if (item.error != null) {
                                Text(item.error, color = MaterialTheme.colorScheme.error)
                            } else {
                                OutlinedTextField(item.title, { viewModel.setTitle(item.id, it) }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
                                Text("Marke: ${item.brandName ?: "nicht erkannt"}", style = MaterialTheme.typography.bodyMedium)
                                item.duplicateOf?.let { Text("Achtung: ein Produkt mit diesem Titel gibt es schon.", color = MaterialTheme.colorScheme.error) }
                                item.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                val parsed = item.parsed
                                if (parsed != null) {
                                    Text("${parsed.criteria.size} Kriterien erkannt", style = MaterialTheme.typography.labelLarge)
                                    for (c in parsed.criteria) {
                                        val label = state.catalog.firstOrNull { it.key == c.key }?.label ?: c.key
                                        Text(
                                            "$label: " + listOfNotNull(c.number?.toString(), c.text, c.note).joinToString(" | "),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (parsed.unassigned.isNotEmpty()) {
                                        Text("Nicht zugeordnet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                                        parsed.unassigned.forEachIndexed { i, line ->
                                            UnassignedRow(
                                                text = "${line.label}: ${line.number ?: ""} ${line.note.orEmpty()}",
                                                target = item.targets[i], catalog = state.catalog.filter { it.key !in CriterionCatalog.DATA_KEYS },
                                                onTarget = { viewModel.setTarget(item.id, i, it) },
                                            )
                                        }
                                    }
                                }
                                if (item.images.isNotEmpty()) {
                                    Text("Bilder (Tippen wechselt die Zuordnung)", style = MaterialTheme.typography.labelLarge)
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        item.images.forEachIndexed { i, img ->
                                            val bmp = remember(img.file) { android.graphics.BitmapFactory.decodeFile(img.file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }
                                            Column(Modifier.clickable { viewModel.cycleImage(item.id, i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(80.dp), contentScale = ContentScale.Crop)
                                                Text("S${img.page}: ${kindLabel(img.kind)}${if (img.kind == PhotoKind.WORN_DRY || img.kind == PhotoKind.WORN_WET) " ${img.rowIndex + 1}" else ""}", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                                OutlinedButton(onClick = { rawText = item.rawText }) { Text("Rohtext anzeigen") }
                            }
                        }
                    }
                }
            }
        }
    }
    rawText?.let { t ->
        AlertDialog(
            onDismissRequest = { rawText = null },
            confirmButton = { TextButton(onClick = { rawText = null }) { Text("Schließen") } },
            title = { Text("Rohtext") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(t.take(20000), style = MaterialTheme.typography.bodySmall) } },
        )
    }
}

@Composable
private fun UnassignedRow(
    text: String, target: UnassignedTarget,
    catalog: List<de.hr.mediathek.data.db.CriterionCatalogEntity>, onTarget: (UnassignedTarget) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) {
            Text(
                when (target) {
                    UnassignedTarget.Discard -> "Verwerfen"
                    UnassignedTarget.Custom -> "Zusatzkriterium"
                    is UnassignedTarget.Criterion -> catalog.firstOrNull { it.key == target.key }?.label ?: target.key
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Als Zusatzkriterium") }, onClick = { onTarget(UnassignedTarget.Custom); open = false })
            DropdownMenuItem(text = { Text("Verwerfen") }, onClick = { onTarget(UnassignedTarget.Discard); open = false })
            for (c in catalog) DropdownMenuItem(text = { Text(c.label) }, onClick = { onTarget(UnassignedTarget.Criterion(c.key)); open = false })
        }
    }
}
