package de.hr.mediathek.feature.videos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import de.hr.mediathek.R
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.db.SeenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VideosScreen(onOpen: (String) -> Unit, viewModel: VideosViewModel = hiltViewModel()) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.videos.collectAsLazyPagingItems()
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            stringResource(R.string.nav_videos),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter.search,
                onValueChange = { text -> viewModel.update { it.copy(search = text) } },
                label = { Text(stringResource(R.string.video_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                OutlinedButton(onClick = { sortMenu = true }) { Text(stringResource(sortLabel(filter.sort))) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    VideoSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sortLabel(sort))) },
                            onClick = {
                                sortMenu = false
                                if (sort == VideoSort.RANDOM && filter.sort == VideoSort.RANDOM) viewModel.reshuffle()
                                viewModel.update { it.copy(sort = sort) }
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter.favoriteOnly,
                onClick = { viewModel.update { it.copy(favoriteOnly = !it.favoriteOnly) } },
                label = { Text(stringResource(R.string.video_filter_favorites)) },
            )
            FilterChip(
                selected = filter.minRating >= 4,
                onClick = { viewModel.update { it.copy(minRating = if (it.minRating >= 4) 0 else 4) } },
                label = { Text(stringResource(R.string.video_filter_rating)) },
            )
            FilterChip(
                selected = filter.seen == SeenFilter.UNSEEN,
                onClick = { viewModel.update { it.copy(seen = if (it.seen == SeenFilter.UNSEEN) SeenFilter.ALL else SeenFilter.UNSEEN) } },
                label = { Text(stringResource(R.string.video_filter_unseen)) },
            )
            FilterChip(
                selected = filter.seen == SeenFilter.IN_PROGRESS,
                onClick = { viewModel.update { it.copy(seen = if (it.seen == SeenFilter.IN_PROGRESS) SeenFilter.ALL else SeenFilter.IN_PROGRESS) } },
                label = { Text(stringResource(R.string.video_filter_in_progress)) },
            )
            FilterChip(
                selected = filter.onlineOnly,
                onClick = { viewModel.update { it.copy(onlineOnly = !it.onlineOnly) } },
                label = { Text(stringResource(R.string.video_filter_online)) },
            )
            listOf("mp4", "mkv", "avi", "mov").forEach { ext ->
                FilterChip(
                    selected = filter.format == ext,
                    onClick = { viewModel.update { it.copy(format = if (it.format == ext) null else ext) } },
                    label = { Text(ext) },
                )
            }
        }

        if (items.itemCount == 0) {
            Text(
                stringResource(R.string.video_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = items.itemCount, key = { index -> items.peek(index)?.uuid ?: index }) { index ->
                val video = items[index]
                if (video != null) {
                    VideoCard(video, viewModel, onOpen)
                }
            }
        }
    }
}

private fun sortLabel(sort: VideoSort): Int = when (sort) {
    VideoSort.ADDED_DESC -> R.string.sort_added
    VideoSort.NAME_ASC -> R.string.sort_name_asc
    VideoSort.NAME_DESC -> R.string.sort_name_desc
    VideoSort.DATE_DESC -> R.string.sort_date
    VideoSort.RATING_DESC -> R.string.sort_rating
    VideoSort.SIZE_DESC -> R.string.sort_size
    VideoSort.DURATION_DESC -> R.string.sort_duration
    VideoSort.RANDOM -> R.string.sort_random
}

@Composable
private fun VideoCard(video: MediaFileEntity, viewModel: VideosViewModel, onOpen: (String) -> Unit) {
    val offline = video.status != MediaStatus.ONLINE
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (offline) 0.5f else 1f)
            // Offline-Dateien bleiben sichtbar und bewertbar, lassen sich aber nicht abspielen.
            .clickable(enabled = !offline) { onOpen(video.uuid) },
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumbnail(video.uuid, viewModel)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    video.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    metaLine(video),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(video.rating.coerceIn(0, 5)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFFC107), modifier = Modifier.height(16.dp))
                    }
                    if (video.favorite) {
                        Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.video_favorite), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(16.dp))
                    }
                    if (offline) Text(stringResource(R.string.video_offline), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                    if (video.seenState == SeenState.SEEN) Text(stringResource(R.string.video_seen), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

private fun metaLine(video: MediaFileEntity): String {
    val parts = ArrayList<String>()
    video.durationMs?.let { parts += formatDuration(it) }
    if (video.width != null && video.height != null) parts += "${video.width}×${video.height}"
    parts += formatSize(video.size)
    return parts.joinToString(" · ")
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(bytes shr 10)
}

@Composable
private fun Thumbnail(uuid: String, viewModel: VideosViewModel) {
    val bitmap by produceState<Bitmap?>(initialValue = null, uuid) {
        value = withContext(Dispatchers.IO) {
            viewModel.thumbnailOf(uuid)?.let { BitmapFactory.decodeFile(it.path) }
        }
    }
    Box(
        Modifier.width(128.dp).height(72.dp).alpha(1f),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
