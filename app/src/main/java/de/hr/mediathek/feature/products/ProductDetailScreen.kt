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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.db.PhotoKind
import de.hr.mediathek.data.db.ProductPhotoEntity
import de.hr.mediathek.data.product.CriterionCatalog
import de.hr.mediathek.data.product.CriterionInput

@Composable
fun ProductDetailScreen(onBack: () -> Unit, viewModel: ProductDetailViewModel = hiltViewModel()) {
    val f by viewModel.form.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { viewModel.flush() } }
    LaunchedEffect(f.deleted) { if (f.deleted) onBack() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.flush(); onBack() }) { Text("← Fertig") }
            Text(if (f.saved) "Gespeichert" else "Speichert …", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { viewModel.change { it.copy(favorite = !it.favorite) } }) { Text(if (f.favorite) "★ Favorit" else "☆ Favorit") }
            TextButton(onClick = { confirmDelete = true }) { Text("🗑") }
        }
        if (!f.loaded) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.title, onValueChange = viewModel::setTitle, label = { Text("Titel (Marke + Produkt + Größe)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Marke", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in brands) {
                    FilterChip(selected = f.brandUuid == b.uuid, onClick = { viewModel.setBrand(if (f.brandUuid == b.uuid) null else b.uuid) }, label = { Text(b.name) })
                }
            }
            OutlinedTextField(f.sizeLabel, { v -> viewModel.change { it.copy(sizeLabel = v) } }, label = { Text("Größe") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            val stars = viewModel.effectiveStars(f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gesamt: ${starsText(stars)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { viewModel.change { it.copy(starsManual = !it.starsManual, stars = stars) } }) {
                    Text(if (f.starsManual) "Automatisch" else "Manuell")
                }
            }
            if (f.starsManual) {
                Row { for (i in 1..5) Text(if (i <= (f.stars ?: 0)) "★" else "☆", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.change { it.copy(stars = i) } }.padding(8.dp)) }
            }

            // Kriterien nach Gruppen (ohne die Datenfelder, die weiter unten eigene Felder haben)
            val groups = catalog.filter { it.key !in CriterionCatalog.DATA_KEYS }.groupBy { it.groupName }
            for ((group, list) in groups) {
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group, style = MaterialTheme.typography.titleMedium)
                        for (c in list) {
                            val input = f.inputs[c.key] ?: CriterionInput()
                            Text(c.label, style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = input.number?.toString().orEmpty(),
                                    onValueChange = { v ->
                                        val digits = v.filter { it.isDigit() }.take(2)
                                        val n = digits.toIntOrNull()
                                        if (digits.isEmpty()) viewModel.setInput(c.key, input.copy(number = null))
                                        else if (n != null && n in 1..10) viewModel.setInput(c.key, input.copy(number = n))
                                    },
                                    label = { Text("1–10") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(0.7f),
                                )
                                OutlinedTextField(
                                    value = input.text, onValueChange = { v -> viewModel.setInput(c.key, input.copy(text = v)) },
                                    label = { Text("Text") }, singleLine = true, modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedTextField(
                                value = input.note, onValueChange = { v -> viewModel.setInput(c.key, input.copy(note = v)) },
                                label = { Text("Anmerkung") }, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (f.custom.isNotEmpty()) {
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Zusatzkriterien", style = MaterialTheme.typography.titleMedium)
                        for ((label, input) in f.custom) Text("$label: ${input.number ?: ""} ${input.note}")
                    }
                }
            }

            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Daten", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(f.rawPrice, { v -> viewModel.change { it.copy(rawPrice = v) } }, label = { Text("Preis (z. B. 1,50€ oder 15 € / 10 Stk)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(f.rawUnit, { v -> viewModel.change { it.copy(rawUnit = v) } }, label = { Text("Preis pro Stück") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        f.packSize, { v -> viewModel.change { it.copy(packSize = v.filter { c -> c.isDigit() }.take(4)) } },
                        label = { Text("Inhalt pro Packung (Stück)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                    viewModel.pricePreview(f)?.let { Text("Erkannt: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(f.weightEmpty, { v -> viewModel.change { it.copy(weightEmpty = v) } }, label = { Text("Gewicht leer (g)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(f.weightFull, { v -> viewModel.change { it.copy(weightFull = v) } }, label = { Text("Gewicht voll (g)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(f.sizes, { v -> viewModel.change { it.copy(sizes = v) } }, label = { Text("Anzahl der Größen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Würde ich wieder kaufen?", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = f.wouldBuy == true, onClick = { viewModel.change { it.copy(wouldBuy = if (it.wouldBuy == true) null else true) } }, label = { Text("Ja") })
                        FilterChip(selected = f.wouldBuy == false, onClick = { viewModel.change { it.copy(wouldBuy = if (it.wouldBuy == false) null else false) } }, label = { Text("Nein") })
                    }
                    OutlinedTextField(f.overallNote, { v -> viewModel.change { it.copy(overallNote = v) } }, label = { Text("Gesamtbewertung (Anmerkung)") }, modifier = Modifier.fillMaxWidth())
                }
            }

            PhotoSection(viewModel, photos)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
            title = { Text("Produkt löschen?") },
            text = { Text("Einträge im Tracker bleiben mit dem gespeicherten Namen erhalten.") },
        )
    }
}

@Composable
private fun PhotoSection(viewModel: ProductDetailViewModel, photos: List<ProductPhotoEntity>) {
    var pendingKind by remember { mutableStateOf<Pair<PhotoKind, Int>?>(null) }
    var viewing by remember { mutableStateOf<ProductPhotoEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = pendingKind
        if (uri != null && p != null) viewModel.addPhoto(p.first, p.second, uri)
        pendingKind = null
    }
    Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fotos", style = MaterialTheme.typography.titleMedium)
            for ((kind, label) in listOf(PhotoKind.PACKAGE to "Verpackung", PhotoKind.DETAIL to "Produktfotos")) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in photos.filter { it.kind == kind }) PhotoThumb(viewModel, p) { viewing = p }
                    OutlinedButton(onClick = { pendingKind = kind to 0; picker.launch(arrayOf("image/*")) }) { Text("+") }
                }
            }
            Text("Tragefotos (trocken | nass)", style = MaterialTheme.typography.labelLarge)
            val rows = (photos.filter { it.kind == PhotoKind.WORN_DRY || it.kind == PhotoKind.WORN_WET }.map { it.rowIndex }.distinct().sorted())
            for (r in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in listOf(PhotoKind.WORN_DRY, PhotoKind.WORN_WET)) {
                        val p = photos.firstOrNull { it.kind == kind && it.rowIndex == r }
                        if (p != null) PhotoThumb(viewModel, p) { viewing = p } else Text("–", modifier = Modifier.size(96.dp))
                    }
                }
            }
            val next = (rows.maxOrNull() ?: -1) + 1
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pendingKind = PhotoKind.WORN_DRY to next; picker.launch(arrayOf("image/*")) }) { Text("+ trocken") }
                OutlinedButton(onClick = { pendingKind = PhotoKind.WORN_WET to (rows.maxOrNull() ?: 0); picker.launch(arrayOf("image/*")) }) { Text("+ nass") }
            }
        }
    }
    viewing?.let { photo ->
        val bmp by produceState<ImageBitmap?>(null, photo.filePath) { value = viewModel.loadPhoto(photo.filePath, 1600)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { viewing = null },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Schließen") } },
            dismissButton = { TextButton(onClick = { viewModel.removePhoto(photo.uuid); viewing = null }) { Text("Foto löschen") } },
            text = {
                val b = bmp
                if (b != null) Image(b, null, Modifier.fillMaxWidth().height(420.dp), contentScale = ContentScale.Fit)
                else Text("Bild nicht verfügbar (Karte eingelegt?)")
            },
        )
    }
}

@Composable
private fun PhotoThumb(viewModel: ProductDetailViewModel, photo: ProductPhotoEntity, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, photo.filePath) { value = viewModel.loadPhoto(photo.filePath, 300)?.asImageBitmap() }
    val b = bmp
    if (b != null) {
        Image(b, "Produktfoto", Modifier.size(96.dp).clickable(onClick = onClick), contentScale = ContentScale.Crop)
    } else {
        Text("…", Modifier.size(96.dp).clickable(onClick = onClick))
    }
}
