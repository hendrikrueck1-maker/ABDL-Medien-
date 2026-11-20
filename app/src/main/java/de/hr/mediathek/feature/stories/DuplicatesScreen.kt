package de.hr.mediathek.feature.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.story.organize.DiffKind
import de.hr.mediathek.data.story.organize.DupKind
import java.text.DateFormat
import java.util.Date

@Composable
fun DuplicatesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: DuplicatesViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Duplikate", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = viewModel::scan, enabled = !s.scanning) { Text("Neu suchen") }
        }
        if (s.scanning) {
            Text("Vergleiche Texte … ${s.done} von ${s.total}")
            if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
        }
        s.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.clearMessage() })
        }
        if (s.scanned && s.groups.isEmpty() && s.message == null) {
            Text("Keine Duplikate gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.groups.isNotEmpty()) {
            Text(
                "${s.groups.size} Gruppen. Wähle je Gruppe die Fassung, die bleiben soll; die anderen kommen in den Papierkorb (30 Tage wiederherstellbar). Bewertung, Favorit, Serie und Ordner werden übernommen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.groups, key = { g -> g.members.joinToString { it.uuid } }) { g ->
                Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (g.kind == DupKind.IDENTICAL) "Identischer Text (${g.members.size}×)"
                            else "Sehr ähnlich, ca. ${(g.similarity * 100).toInt()} % (${g.members.size}×)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        for (m in g.members) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { viewModel.setKeep(g, m.uuid) }) {
                                RadioButton(selected = g.keep == m.uuid, onClick = { viewModel.setKeep(g, m.uuid) })
                                Column(Modifier.weight(1f)) {
                                    Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                                    Text(
                                        listOfNotNull(
                                            categoryLabel(m.category),
                                            m.author?.takeIf { it.isNotBlank() }?.let { "von $it" },
                                            m.sourceText?.takeIf { it.isNotBlank() },
                                            m.wordCount.takeIf { it > 0 }?.let(::formatWords),
                                            if (m.rating > 0) "★".repeat(m.rating) else null,
                                            if (m.favorite) "♥" else null,
                                            if (m.seriesUuid != null) "in Serie" else null,
                                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.createdAt)),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onOpen(m.uuid) }) { Text("Lesen") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(onClick = { viewModel.merge(g) }) { Text("Zusammenführen") }
                            OutlinedButton(onClick = { viewModel.openCompare(g) }) { Text("Vergleichen") }
                            TextButton(onClick = { viewModel.ignore(g) }) { Text("Kein Duplikat") }
                        }
                    }
                }
            }
        }
    }
    s.compareGroup?.let { g ->
        Dialog(onDismissRequest = viewModel::closeCompare, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vergleich", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::closeCompare) { Text("Schließen") }
                }
                if (g.members.size > 2) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("A:")
                        g.members.forEachIndexed { i, m ->
                            FilterChip(selected = s.compareA == i, onClick = { if (i != s.compareB) viewModel.pick(i, s.compareB) }, label = { Text(m.title.take(24)) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("B:")
                        g.members.forEachIndexed { i, m ->
                            FilterChip(selected = s.compareB == i, onClick = { if (i != s.compareA) viewModel.pick(s.compareA, i) }, label = { Text(m.title.take(24)) })
                        }
                    }
                } else {
                    Text("A: ${g.members[0].title}", style = MaterialTheme.typography.bodyMedium)
                    Text("B: ${g.members[1].title}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(Color(0xFFFFD6D6), "nur in A")
                    Legend(Color(0xFFD3F5DF), "nur in B")
                }
                val diff = s.diff
                if (diff == null) {
                    Text("Vergleiche …")
                } else if (diff.all { it.kind == DiffKind.SAME }) {
                    Text("Die Texte sind (bis auf Leerraum) gleich.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(diff.size) { i ->
                            val r = diff[i]
                            when (r.kind) {
                                DiffKind.SAME -> Text("… ${r.collapsed} gleiche Absätze …", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                DiffKind.ONLY_LEFT -> DiffBlock(r.text, Color(0xFFFFD6D6))
                                DiffKind.ONLY_RIGHT -> DiffBlock(r.text, Color(0xFFD3F5DF))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("   ", modifier = Modifier.background(color, RoundedCornerShape(4.dp)))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DiffBlock(text: String, color: Color) {
    Text(
        text,
        color = Color(0xFF222222),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(10.dp)).padding(10.dp),
    )
}
