package de.hr.mediathek.feature.tracker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.AppCard
import de.hr.mediathek.core.ui.bounceClick
import de.hr.mediathek.core.ui.theme.AppColors
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.data.tracker.Bucket
import de.hr.mediathek.data.tracker.EntryView
import de.hr.mediathek.data.tracker.StatPeriod
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)

fun formatEntryTime(e: EntryView): String {
    val z = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())
    val t = Instant.ofEpochMilli(e.instant).atZone(z)
    return DATE_FMT.format(t) + " " + TIME_FMT.format(t)
}

private fun money(v: Double) = "%.2f €".format(Locale.GERMANY, v)

private fun bucketLabel(b: Bucket, p: StatPeriod): String = when (p) {
    StatPeriod.DAY -> b.start.format(DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY))
    StatPeriod.WEEK -> "KW " + b.start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    StatPeriod.MONTH -> b.start.format(DateTimeFormatter.ofPattern("MM/yy", Locale.GERMANY))
}

/** Modulfarben der Reihe nach für Kacheln und Balken. */
private fun accentAt(c: AppColors, i: Int): Color = listOf(c.rosa, c.hellblau, c.mint, c.gold, c.lavendel)[i % 5]

/**
 * Helle Rückgängig-Leiste: erscheint nach jedem Eintrag und läuft 8 Sekunden. Dunkler Knopf mit Restzeit.
 */
@Composable
fun UndoBar(viewModel: TrackerViewModel) {
    val c = LocalAppColors.current
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val current = undo ?: return
    var left by remember(current.first) { mutableIntStateOf(8) }
    LaunchedEffect(current.first) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.onCard).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.tracker_undo, current.second),
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.onAccent, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).background(c.onAccent)
                .clickable(role = Role.Button, onClick = viewModel::undoLast).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.tracker_undo_button, left), style = MaterialTheme.typography.labelLarge, color = c.onCard) }
    }
}

@Composable
private fun PeriodSwitch(period: StatPeriod, onPeriod: (StatPeriod) -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.clip(CircleShape).background(c.cardInset).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((p, label) in listOf(
            StatPeriod.DAY to R.string.tracker_period_day,
            StatPeriod.WEEK to R.string.tracker_period_week,
            StatPeriod.MONTH to R.string.tracker_period_month,
        )) {
            val active = period == p
            Box(
                Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 44.dp).clip(CircleShape)
                    .background(if (active) c.lavendel else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onPeriod(p) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label), style = MaterialTheme.typography.labelLarge,
                    color = if (active) c.onAccent else c.onCard, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun Modifier.selectableGroup(): Modifier = this.semantics { }

@Composable
fun TrackerScreen(
    onOpenProduct: (String) -> Unit,
    onStats: () -> Unit,
    viewModel: TrackerViewModel = hiltViewModel(),
    stats: TrackerStatsViewModel = hiltViewModel(),
) {
    val c = LocalAppColors.current
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val s by stats.state.collectAsStateWithLifecycle()
    val f by stats.filter.collectAsStateWithLifecycle()
    var noteFor by remember { mutableStateOf<Tile?>(null) }
    var editing by remember { mutableStateOf<EntryView?>(null) }
    var showOther by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(20) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Kopfkarte
            val latest = entries.firstOrNull()
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tracker_title), style = MaterialTheme.typography.headlineMedium, color = c.onCard)
                        Text(
                            stringResource(R.string.tracker_day_start, "%02d:%02d".format(s.dayStartMinutes / 60, s.dayStartMinutes % 60)),
                            style = MaterialTheme.typography.bodyMedium, color = c.onCardVariant,
                        )
                    }
                    if (wide) PeriodSwitch(f.period) { setPeriod(stats, it) }
                }
                if (!wide) PeriodSwitch(f.period) { setPeriod(stats, it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (latest == null) stringResource(R.string.tracker_none)
                        else stringResource(R.string.tracker_current, latest.name, formatEntryTime(latest)),
                        Modifier.weight(1f).clickable(enabled = latest?.productUuid != null) { latest?.productUuid?.let(onOpenProduct) },
                        style = MaterialTheme.typography.bodyMedium, color = c.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onStats) { Text(stringResource(R.string.tracker_stats_more), color = c.lavendel) }
                }
            }

            val quick: @Composable () -> Unit = {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracker_quick), style = MaterialTheme.typography.titleLarge, color = c.onCard)
                    val shownTiles = tiles.take(5)
                    for (rowStart in 0 until 6 step 3) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (idx in rowStart until rowStart + 3) {
                                val t = shownTiles.getOrNull(idx)
                                when {
                                    t != null -> QuickTile(t, accentAt(c, idx), viewModel, Modifier.weight(1f), onTap = { viewModel.tap(t) }, onLongTap = { noteFor = t })
                                    idx == shownTiles.size.coerceAtMost(5) && idx < 6 -> OtherTile(Modifier.weight(1f)) { showOther = true }
                                    else -> Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.tracker_hint), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant)
                }
                UndoBar(viewModel)
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracker_cost_title), style = MaterialTheme.typography.titleLarge, color = c.onCard)
                    val unit = periodUnit(f.period)
                    val per = s.buckets.size.coerceAtLeast(1)
                    Text(stringResource(R.string.tracker_cost_sum, money(s.totalCost)), style = MaterialTheme.typography.titleMedium, color = c.onCard)
                    Text(
                        stringResource(R.string.tracker_cost_avg, unit, money(s.totalCost / per), "%.1f".format(Locale.GERMANY, s.average)),
                        style = MaterialTheme.typography.bodyMedium, color = c.onCardVariant,
                    )
                    if (s.withoutPrice > 0) {
                        Text(stringResource(R.string.tracker_without_price, s.withoutPrice), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant)
                    }
                }
            }
            val charts: @Composable () -> Unit = {
                AppCard(Modifier.fillMaxWidth()) { ChartContent(s.buckets, f.period, s.total) }
                AppCard(Modifier.fillMaxWidth()) { DistributionContent(s, onOpenProduct) }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(14.dp)) { quick() }
                    Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { charts() }
                }
            } else {
                quick()
                charts()
            }

            // Einträge
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tracker_entries), style = MaterialTheme.typography.titleLarge, color = c.onCard)
                for (e in entries.take(shown)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.cardInset)
                            .clickable(onClickLabel = stringResource(R.string.tracker_edit)) { editing = e }
                            .defaultMinSize(minHeight = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall, color = c.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatEntryTime(e) + (e.note?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = c.onCardVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.Edit, stringResource(R.string.tracker_edit), tint = c.onCardVariant)
                    }
                }
                if (entries.size > shown) {
                    TextButton(onClick = { shown += 50 }) { Text(stringResource(R.string.tracker_more), color = c.lavendel) }
                }
            }
        }
    }

    if (showOther) OtherDialog(viewModel, tiles, onDismiss = { showOther = false; viewModel.update { it.copy(query = "", brandUuid = null) } }) {
        viewModel.tap(it)
    }
    noteFor?.let { tile ->
        var note by remember(tile.uuid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            containerColor = c.bar,
            shape = RoundedCornerShape(30.dp),
            confirmButton = { Button(onClick = { viewModel.tap(tile, note); noteFor = null }) { Text("Eintragen") } },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text("Abbrechen") } },
            title = { Text(tile.title) },
            text = { OutlinedTextField(note, { note = it }, label = { Text("Notiz (optional)") }, modifier = Modifier.fillMaxWidth()) },
        )
    }
    editing?.let { e -> EditEntryDialog(e, tiles, onDismiss = { editing = null }, viewModel = viewModel) }
}

/** Tag, Woche und Monat zeigen jeweils einen sinnvollen Zeitraum (14 Tage, 90 Tage, 1 Jahr). */
private fun setPeriod(stats: TrackerStatsViewModel, p: StatPeriod) {
    stats.update {
        it.copy(
            period = p,
            range = when (p) {
                StatPeriod.DAY -> RangeFilter.D14
                StatPeriod.WEEK -> RangeFilter.D90
                StatPeriod.MONTH -> RangeFilter.YEAR
            },
        )
    }
}

@Composable
private fun periodUnit(p: StatPeriod): String = stringResource(
    when (p) {
        StatPeriod.DAY -> R.string.tracker_period_day
        StatPeriod.WEEK -> R.string.tracker_period_week
        StatPeriod.MONTH -> R.string.tracker_period_month
    },
)

@Composable
private fun QuickTile(tile: Tile, color: Color, viewModel: TrackerViewModel, modifier: Modifier, onTap: () -> Unit, onLongTap: () -> Unit) {
    val c = LocalAppColors.current
    val bmp by produceState<ImageBitmap?>(null, tile.uuid) { value = viewModel.photoOf(tile.uuid)?.asImageBitmap() }
    Row(
        modifier.defaultMinSize(minHeight = 88.dp).clip(RoundedCornerShape(24.dp)).background(color)
            .bounceClick(onLongClick = onLongTap, onClick = onTap)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bmp?.let { Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
        Text(tile.title, style = MaterialTheme.typography.labelLarge, color = c.onAccent, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Letzte Kachel: gestrichelter Rand, öffnet die Suche über alle Produkte. */
@Composable
private fun OtherTile(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val stroke = c.onCardVariant
    Column(
        modifier.defaultMinSize(minHeight = 88.dp).clip(RoundedCornerShape(24.dp))
            .drawBehind {
                drawRoundRect(
                    stroke, cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, null, tint = c.onCard)
        Text(stringResource(R.string.tracker_other), style = MaterialTheme.typography.labelLarge, color = c.onCard)
    }
}

@Composable
private fun OtherDialog(viewModel: TrackerViewModel, tiles: List<Tile>, onDismiss: () -> Unit, onPick: (Tile) -> Unit) {
    val c = LocalAppColors.current
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bar,
        shape = RoundedCornerShape(30.dp),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        title = { Text(stringResource(R.string.tracker_other)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter.query, onValueChange = { q -> viewModel.update { it.copy(query = q) } },
                    label = { Text(stringResource(R.string.tracker_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in brands) {
                        FilterChip(
                            selected = filter.brandUuid == b.uuid,
                            onClick = { viewModel.update { f -> f.copy(brandUuid = if (f.brandUuid == b.uuid) null else b.uuid) } },
                            label = { Text(b.name) },
                        )
                    }
                }
                LazyColumn(Modifier.height(320.dp)) {
                    items(tiles, key = { it.uuid }) { t ->
                        Text(
                            t.title,
                            Modifier.fillMaxWidth().clickable { onPick(t); onDismiss() }.defaultMinSize(minHeight = 48.dp).padding(horizontal = 4.dp, vertical = 12.dp),
                            color = c.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    )
}

/** Balkendiagramm: Mint, der aktuelle Zeitraum Rosa, Werte über den Balken, Beschriftung darunter. */
@Composable
private fun ChartContent(buckets: List<Bucket>, period: StatPeriod, total: Int) {
    val c = LocalAppColors.current
    Text(stringResource(R.string.tracker_chart_title), style = MaterialTheme.typography.titleLarge, color = c.onCard)
    Text(stringResource(R.string.tracker_chart_total, total), style = MaterialTheme.typography.bodyMedium, color = c.onCardVariant)
    val max = (buckets.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val desc = buckets.joinToString("; ") { "${bucketLabel(it, period)}: ${it.count}" }
    val chartDesc = stringResource(R.string.tracker_chart_desc, desc)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)).clearAndSetSemantics { contentDescription = chartDesc },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((i, b) in buckets.withIndex()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
                Text("${b.count}", style = MaterialTheme.typography.labelMedium, color = c.onCard)
                Box(
                    Modifier.width(26.dp).height((130f * b.count / max).dp.coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(if (i == buckets.lastIndex) c.rosa else c.mint),
                )
                Text(bucketLabel(b, period), style = MaterialTheme.typography.labelSmall, color = c.onCardVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DistributionContent(s: StatsState, onOpenProduct: (String) -> Unit) {
    val c = LocalAppColors.current
    Text(stringResource(R.string.tracker_distribution), style = MaterialTheme.typography.titleLarge, color = c.onCard)
    val total = s.distribution.sumOf { it.count }.coerceAtLeast(1)
    for ((i, sl) in s.distribution.take(8).withIndex()) {
        val pct = sl.count * 100 / total
        Column(
            Modifier.fillMaxWidth().clickable(enabled = sl.productUuid != null) { sl.productUuid?.let(onOpenProduct) }.defaultMinSize(minHeight = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row {
                Text(sl.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$pct %", style = MaterialTheme.typography.labelLarge, color = c.onCard)
            }
            Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(c.cardInset)) {
                Box(Modifier.fillMaxWidth(sl.count.toFloat() / total).height(12.dp).clip(CircleShape).background(accentAt(c, i)))
            }
        }
    }
}

@Composable
private fun EditEntryDialog(e: EntryView, tiles: List<Tile>, onDismiss: () -> Unit, viewModel: TrackerViewModel) {
    val zone = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(e.instant).atZone(zone)
    var date by remember { mutableStateOf(DATE_FMT.format(start)) }
    var time by remember { mutableStateOf(TIME_FMT.format(start)) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    var productUuid by remember { mutableStateOf(e.productUuid) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.bar,
        shape = RoundedCornerShape(30.dp),
        title = { Text("Eintrag bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("Datum (TT.MM.JJJJ)") }, singleLine = true, isError = error)
                OutlinedTextField(time, { time = it }, label = { Text("Uhrzeit (HH:MM)") }, singleLine = true, isError = error)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(tiles.firstOrNull { it.uuid == productUuid }?.title ?: e.name) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (t in tiles) DropdownMenuItem(text = { Text(t.title) }, onClick = { productUuid = t.uuid; menu = false })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Notiz") })
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = runCatching {
                    val d = LocalDate.parse(date.trim(), DATE_FMT)
                    val t = java.time.LocalTime.parse(time.trim(), TIME_FMT)
                    LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull()
                if (parsed == null) error = true else { viewModel.edit(e.uuid, parsed, productUuid, note); onDismiss() }
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { viewModel.delete(e.uuid); onDismiss() }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
