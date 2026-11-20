package de.hr.mediathek.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.Destination
import de.hr.mediathek.core.ui.theme.TileColors
import de.hr.mediathek.core.ui.theme.TilePalette

private val moduleTiles = listOf(
    Destination.Stories,
    Destination.Images,
    Destination.Videos,
    Destination.Products,
    Destination.Tracker,
)

@Composable
private fun tileColors(destination: Destination): TileColors {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    return when (destination) {
        Destination.Stories -> TileColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        Destination.Images -> TileColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        Destination.Videos -> TileColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> if (dark) TilePalette.peachDark else TilePalette.peachLight
    }
}

@Composable
private fun SuggestionCard(
    suggestion: de.hr.mediathek.data.suggest.Suggestion<de.hr.mediathek.data.db.MediaFileEntity>,
    viewModel: HomeViewModel,
    onClick: () -> Unit,
) {
    val video = suggestion.item
    val bitmap = de.hr.mediathek.feature.library.rememberThumbBitmap(viewModel.thumbnailOf(video.uuid))
    val (label, color) = when (suggestion.kind) {
        de.hr.mediathek.data.suggest.SuggestionKind.OFTEN -> stringResource(R.string.suggest_often) to MaterialTheme.colorScheme.primaryContainer
        de.hr.mediathek.data.suggest.SuggestionKind.RARE -> stringResource(R.string.suggest_rare) to MaterialTheme.colorScheme.secondaryContainer
        de.hr.mediathek.data.suggest.SuggestionKind.NEVER -> stringResource(R.string.suggest_never) to MaterialTheme.colorScheme.tertiaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black.copy(alpha = 0.25f))) {
            bitmap?.let {
                androidx.compose.foundation.Image(
                    it.asImageBitmap(),
                    contentDescription = video.displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp))
        Text(
            video.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

@Composable
fun HomeScreen(
    onOpen: (Destination) -> Unit,
    onOpenVideo: (String) -> Unit = {},
    viewModel: HomeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    // Beim Zurückkehren zur Übersicht neu mischen.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(moduleTiles) { destination ->
                val colors = tileColors(destination)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = colors.container,
                        contentColor = colors.content,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onOpen(destination) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Text(
                            text = stringResource(destination.label),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                de.hr.mediathek.feature.tracker.QuickAddRow(onOpenTracker = { onOpen(Destination.Tracker) })
            }
            if (suggestions.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.home_suggestions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(suggestions, key = { "s:" + it.item.uuid }) { suggestion ->
                    SuggestionCard(suggestion, viewModel) { onOpenVideo(suggestion.item.uuid) }
                }
            }
        }
        Text(
            text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
