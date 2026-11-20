package de.hr.mediathek.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.AccentButton
import de.hr.mediathek.core.ui.AppBackgroundViewModel
import de.hr.mediathek.core.ui.AppCard
import de.hr.mediathek.core.ui.Destination
import de.hr.mediathek.core.ui.OutlineAccentButton
import de.hr.mediathek.core.ui.bounceClick
import de.hr.mediathek.core.ui.theme.AppColors
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.suggest.Suggestion
import de.hr.mediathek.data.suggest.SuggestionKind
import de.hr.mediathek.feature.library.rememberThumbBitmap
import de.hr.mediathek.data.db.RecentStory
import de.hr.mediathek.feature.tracker.Tile
import de.hr.mediathek.feature.tracker.TrackerViewModel
import de.hr.mediathek.feature.tracker.UndoBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val areaTiles = listOf(Destination.Stories, Destination.Images, Destination.Videos, Destination.Products)

private fun AppColors.colorOf(d: Destination): Color = when (d) {
    Destination.Images -> hellblau
    Destination.Videos -> mint
    Destination.Products -> gold
    Destination.Tracker -> lavendel
    else -> rosa
}

private fun descOf(d: Destination): Int = when (d) {
    Destination.Stories -> R.string.home_area_stories_desc
    Destination.Images -> R.string.home_area_images_desc
    Destination.Videos -> R.string.home_area_videos_desc
    else -> R.string.home_area_products_desc
}

@Composable
fun HomeScreen(
    onOpen: (Destination) -> Unit,
    onOpenVideo: (String) -> Unit = {},
    onOpenStory: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    tracker: TrackerViewModel = hiltViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val tiles by tracker.tiles.collectAsStateWithLifecycle()
    val entries by tracker.entries.collectAsStateWithLifecycle()
    // Gleiche Instanz wie der App-Hintergrund (Activity-weit), damit der Button das Bild tauscht.
    val background: AppBackgroundViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    LaunchedEffect(Unit) { viewModel.refresh() }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val today = LocalDate.now()
    val todayCount = entries.count { Instant.ofEpochMilli(it.instant).atZone(ZoneId.of(it.zoneId)).toLocalDate() == today }
    val date = today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN))
    val c = LocalAppColors.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 20.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 12.dp),
    ) {
        HeaderCard(date, wide, onNewBackground = background::reload)
        // Reihenfolge: Bereiche, Vorschläge (fünf nebeneinander), Zuletzt geöffnet (nur Geschichten), Aktuell.
        AreasGrid(Modifier.fillMaxWidth(), onOpen, wide = wide)
        SuggestionsCard(suggestions, viewModel, wide = wide, onOpenVideo)
        RecentCard(recent, onOpenStory)
        CurrentCard(tiles.filter { it.favorite }.take(3), todayCount, tracker, wide = wide, onMore = { onOpen(Destination.Tracker) })
        Text(
            stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = c.onCardVariant,
        )
    }
}

@Composable
private fun HeaderCard(date: String, wide: Boolean, onNewBackground: () -> Unit) {
    val c = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth(), bar = true, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_title), style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, color = c.onCard)
                Text(date, style = MaterialTheme.typography.bodyMedium, color = c.onCardVariant)
            }
            if (wide) {
                OutlineAccentButton(onNewBackground, color = c.hellblau) { Text(stringResource(R.string.home_new_background)) }
            } else {
                Surface(
                    onClick = onNewBackground, shape = CircleShape, color = c.card, contentColor = c.hellblau,
                    border = BorderStroke(2.dp, c.hellblau), modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.home_new_background_short))
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentCard(favorites: List<Tile>, todayCount: Int, tracker: TrackerViewModel, wide: Boolean, onMore: () -> Unit) {
    val c = LocalAppColors.current
    val colors = listOf(c.rosa, c.hellblau, c.mint)
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_current), style = MaterialTheme.typography.titleLarge, color = c.onCard, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.home_current_today, todayCount), style = MaterialTheme.typography.labelMedium, color = c.onCardVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            favorites.forEachIndexed { i, t ->
                Box(
                    Modifier
                        .weight(1f).height(if (wide) 68.dp else 60.dp)
                        .clip(RoundedCornerShape(22.dp)).background(colors[i % colors.size])
                        .bounceClick { tracker.tap(t) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ ${t.title}", style = MaterialTheme.typography.labelLarge, color = c.onAccent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (wide) {
                Box(
                    Modifier
                        .weight(1f).height(68.dp).clip(RoundedCornerShape(22.dp))
                        .border(2.dp, c.muted, RoundedCornerShape(22.dp))
                        .bounceClick(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.home_more), style = MaterialTheme.typography.labelLarge, color = c.onCardVariant) }
            }
        }
        UndoBar(tracker)
    }
}

@Composable
private fun SuggestionsCard(
    suggestions: List<Suggestion<MediaFileEntity>>,
    viewModel: HomeViewModel,
    wide: Boolean,
    onOpenVideo: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    val c = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_suggestions), style = MaterialTheme.typography.titleLarge, color = c.onCard)
        val cols = 5
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          suggestions.chunked(cols).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in rowItems) {
                val video = s.item
                val bitmap = rememberThumbBitmap(viewModel.thumbnailOf(video.uuid))
                val (label, color) = when (s.kind) {
                    SuggestionKind.OFTEN -> stringResource(R.string.suggest_often) to c.rosa
                    SuggestionKind.RARE -> stringResource(R.string.suggest_rare) to c.hellblau
                    SuggestionKind.NEVER -> stringResource(R.string.suggest_never) to c.mint
                }
                Column(Modifier.weight(1f).bounceClick { onOpenVideo(video.uuid) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(16.dp)).background(c.cardInset)
                            .border(2.dp, color, RoundedCornerShape(16.dp)),
                    ) {
                        bitmap?.let {
                            Image(it.asImageBitmap(), video.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    Text(video.displayName, style = MaterialTheme.typography.bodySmall, color = c.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(cols - rowItems.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
          }
        }
    }
}

@Composable
private fun AreasGrid(modifier: Modifier, onOpen: (Destination) -> Unit, wide: Boolean) {
    val c = LocalAppColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 12.dp)) {
        areaTiles.chunked(if (wide) 4 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 12.dp)) {
                row.forEach { d ->
                    val color = c.colorOf(d)
                    Surface(
                        shape = RoundedCornerShape(if (wide) 26.dp else 22.dp), color = c.card, contentColor = c.onCard,
                        modifier = Modifier.weight(1f).bounceClick { onOpen(d) },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(52.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                Icon(d.icon, null, tint = c.onAccent, modifier = Modifier.size(28.dp))
                            }
                            Text(stringResource(d.label), style = MaterialTheme.typography.titleMedium, color = c.onCard)
                            Text(stringResource(descOf(d)), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(recent: List<RecentStory>, onOpenStory: (String) -> Unit) {
    if (recent.isEmpty()) return
    val c = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleSmall, color = c.onCard)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in recent) {
                    Box(
                        Modifier.clip(CircleShape).background(c.bar).bounceClick { onOpenStory(v.uuid) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) { Text(v.title, style = MaterialTheme.typography.labelMedium, color = c.onCard, maxLines = 1) }
                }
            }
        }
    }
}
