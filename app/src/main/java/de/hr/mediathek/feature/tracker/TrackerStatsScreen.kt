package de.hr.mediathek.feature.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.data.tracker.Bucket
import de.hr.mediathek.data.tracker.StatPeriod
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

private fun money(v: Double) = "%.2f €".format(Locale.GERMANY, v)

private fun bucketLabel(b: Bucket, p: StatPeriod): String = when (p) {
    StatPeriod.DAY -> b.start.format(DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY))
    StatPeriod.WEEK -> "KW " + b.start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    StatPeriod.MONTH -> b.start.format(DateTimeFormatter.ofPattern("MM/yy", Locale.GERMANY))
}

@Composable
fun TrackerStatsScreen(onBack: () -> Unit, onOpenProduct: (String) -> Unit, viewModel: TrackerStatsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val f by viewModel.filter.collectAsStateWithLifecycle()
    var productMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text("Statistik", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((p, label) in listOf(StatPeriod.DAY to "Tag", StatPeriod.WEEK to "Woche", StatPeriod.MONTH to "Monat")) {
                    FilterChip(selected = f.period == p, onClick = { viewModel.update { it.copy(period = p) } }, label = { Text(label) })
                }
                for (r in RangeFilter.entries) {
                    FilterChip(selected = f.range == r, onClick = { viewModel.update { it.copy(range = r) } }, label = { Text(r.label) })
                }
            }
            Box {
                OutlinedButton(onClick = { productMenu = true }) { Text(f.productName ?: "Alle Produkte") }
                DropdownMenu(expanded = productMenu, onDismissRequest = { productMenu = false }) {
                    DropdownMenuItem(text = { Text("Alle Produkte") }, onClick = { viewModel.update { it.copy(productName = null) }; productMenu = false })
                    for (n in s.productNames) DropdownMenuItem(text = { Text(n) }, onClick = { viewModel.update { it.copy(productName = n) }; productMenu = false })
                }
            }
            Text("Tagesbeginn", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in listOf(0, 4, 5, 6, 7, 8)) {
                    FilterChip(selected = s.dayStartMinutes == h * 60, onClick = { viewModel.setDayStart(h * 60) }, label = { Text("%d:00".format(h)) })
                }
            }

            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Anzahl", style = MaterialTheme.typography.titleMedium)
                    Text("Summe ${s.total} · Durchschnitt ${"%.1f".format(Locale.GERMANY, s.average)} pro ${when (f.period) { StatPeriod.DAY -> "Tag"; StatPeriod.WEEK -> "Woche"; StatPeriod.MONTH -> "Monat" }}")
                    val max = (s.buckets.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                    Row(Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE)), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        for (b in s.buckets) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                                Text("${b.count}", style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.width(22.dp).height((110f * b.count / max).dp.coerceAtLeast(2.dp)).background(MaterialTheme.colorScheme.primary))
                                Text(bucketLabel(b, f.period), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }

            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Verteilung", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        FilterChip(selected = !f.byBrand, onClick = { viewModel.update { it.copy(byBrand = false) } }, label = { Text("Produkt") })
                        FilterChip(selected = f.byBrand, onClick = { viewModel.update { it.copy(byBrand = true) } }, label = { Text("Marke") }, modifier = Modifier.padding(start = 6.dp))
                    }
                    val max = (s.distribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                    for (sl in s.distribution) {
                        Column(Modifier.clickable(enabled = sl.productUuid != null) { sl.productUuid?.let(onOpenProduct) }) {
                            Text("${sl.label} (${sl.count})", style = MaterialTheme.typography.bodySmall)
                            Box(Modifier.fillMaxWidth(sl.count.toFloat() / max).height(10.dp).background(MaterialTheme.colorScheme.secondary))
                        }
                    }
                }
            }

            Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Kosten", style = MaterialTheme.typography.titleMedium)
                    Text("Summe im Zeitraum: ${money(s.totalCost)}", style = MaterialTheme.typography.titleSmall)
                    if (s.withoutPrice > 0) Text("${s.withoutPrice} Einträge ohne Preis sind nicht enthalten.", style = MaterialTheme.typography.bodySmall)
                    Text("Bei Preisspannen wird der Mittelwert verwendet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for ((name, cost) in s.costsByProduct) Text("$name: ${money(cost)}", style = MaterialTheme.typography.bodySmall)
                    if (s.buckets.isNotEmpty()) {
                        Text("Je Zeitraum", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        for (b in s.buckets.reversed().take(12)) Text("${bucketLabel(b, f.period)}: ${money(b.cost)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
