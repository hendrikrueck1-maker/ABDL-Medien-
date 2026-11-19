package de.hr.mediathek.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.hr.mediathek.R
import de.hr.mediathek.data.prefs.BrowseMode
import de.hr.mediathek.data.prefs.CardLayout
import de.hr.mediathek.data.prefs.ViewSettings
import de.hr.mediathek.feature.videos.CompressedFilter
import de.hr.mediathek.feature.videos.SeenFilter
import de.hr.mediathek.feature.videos.SizeRange
import de.hr.mediathek.feature.videos.VideoFilter
import de.hr.mediathek.feature.videos.VideoSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Name mit UUID für Kategorien und Schlagwörter in den Filterdialogen. */
data class FilterLabel(val uuid: String, val name: String)

fun sortLabelRes(sort: VideoSort): Int = when (sort) {
    VideoSort.PLAYLIST_ORDER -> R.string.sort_playlist
    VideoSort.ADDED_DESC -> R.string.sort_added
    VideoSort.ADDED_ASC -> R.string.sort_added_asc
    VideoSort.NAME_ASC -> R.string.sort_name_asc
    VideoSort.NAME_DESC -> R.string.sort_name_desc
    VideoSort.DATE_DESC -> R.string.sort_date
    VideoSort.DATE_ASC -> R.string.sort_date_asc
    VideoSort.RATING_DESC -> R.string.sort_rating
    VideoSort.RATING_ASC -> R.string.sort_rating_asc
    VideoSort.SIZE_DESC -> R.string.sort_size
    VideoSort.SIZE_ASC -> R.string.sort_size_asc
    VideoSort.DURATION_DESC -> R.string.sort_duration
    VideoSort.DURATION_ASC -> R.string.sort_duration_asc
    VideoSort.RANDOM -> R.string.sort_random
}

private val VIDEO_SORTS = listOf(
    VideoSort.NAME_ASC, VideoSort.NAME_DESC, VideoSort.ADDED_DESC, VideoSort.ADDED_ASC,
    VideoSort.DATE_DESC, VideoSort.DATE_ASC, VideoSort.SIZE_DESC, VideoSort.SIZE_ASC,
    VideoSort.DURATION_DESC, VideoSort.DURATION_ASC, VideoSort.RATING_DESC, VideoSort.RATING_ASC,
    VideoSort.RANDOM,
)
private val IMAGE_SORTS = listOf(
    VideoSort.NAME_ASC, VideoSort.NAME_DESC, VideoSort.ADDED_DESC, VideoSort.ADDED_ASC,
    VideoSort.DATE_DESC, VideoSort.DATE_ASC, VideoSort.SIZE_DESC, VideoSort.SIZE_ASC,
    VideoSort.RATING_DESC, VideoSort.RATING_ASC, VideoSort.RANDOM,
)

/** Anzahl der aktiven Filter (ohne Suche und Sortierung), für die Anzeige am Filterknopf. */
fun activeFilterCount(f: VideoFilter): Int {
    var n = 0
    if (f.favoriteOnly) n++
    if (f.minRating > 0) n++
    if (f.seen != SeenFilter.ALL) n++
    if (f.onlineOnly) n++
    if (f.format != null) n++
    if (f.categoryUuid != null) n++
    if (f.tagUuid != null) n++
    if (f.sizeRange != SizeRange.ALL) n++
    if (f.compressed != CompressedFilter.ALL) n++
    return n
}

/** Filter zurücksetzen, aber Suche, Sortierung und Playlist behalten. */
fun VideoFilter.clearedFilters(): VideoFilter = VideoFilter(sort = sort, search = search, playlistUuid = playlistUuid)

@Composable
fun rememberThumbBitmap(file: File?): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, file?.path) {
        value = file?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } }
    }
    return bitmap
}

/** Kleine Kennzeichnung auf Karten, z. B. "Komprimiert" oder "Gesehen". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Pfadleiste der Ordneransicht: "Alle Ordner › A › B"; ein Tipp springt zu der Ebene. */
@Composable
fun PathBar(path: String, onGoTo: (String) -> Unit, modifier: Modifier = Modifier) {
    val parts = if (path.isEmpty()) emptyList() else path.split('/')
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onGoTo("") }) { Text(stringResource(R.string.lib_all_folders)) }
        parts.forEachIndexed { index, name ->
            Text("›", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { onGoTo(parts.take(index + 1).joinToString("/")) }) { Text(name, maxLines = 1) }
        }
    }
}

/** Ordnerkarte mit Vorschau-Collage und Dateizahl, wie im VLC-Beispiel. */
@Composable
fun FolderCard(
    item: FolderItem,
    previews: List<File>,
    tiles: Boolean,
    countLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        if (tiles) {
            Column {
                Collage(previews, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Column(Modifier.padding(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(countLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Collage(previews, Modifier.width(128.dp).height(72.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(countLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun Collage(files: List<File>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (files.isEmpty()) {
            Text("📁", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val slots = if (files.size >= 4) 4 else if (files.size >= 2) 2 else 1
        when (slots) {
            1 -> CollageCell(files[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CollageCell(files[0], Modifier.weight(1f).fillMaxSize())
                CollageCell(files[1], Modifier.weight(1f).fillMaxSize())
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CollageCell(files[0], Modifier.weight(1f).fillMaxSize())
                    CollageCell(files[1], Modifier.weight(1f).fillMaxSize())
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CollageCell(files[2], Modifier.weight(1f).fillMaxSize())
                    CollageCell(files[3], Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CollageCell(file: File, modifier: Modifier) {
    val bitmap = rememberThumbBitmap(file)
    Box(modifier) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * "Ansicht": Gruppierung nach Ordnern, Liste oder Kacheln, Details ein/aus, Favoriten, Sortierung.
 * Entspricht dem Anzeigeeinstellungen-Dialog im VLC-Beispiel.
 */
@Composable
fun ViewSettingsDialog(
    settings: ViewSettings,
    filter: VideoFilter,
    isVideo: Boolean,
    onMode: (BrowseMode) -> Unit,
    onLayout: (CardLayout) -> Unit,
    onDetails: (Boolean) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onSort: (VideoSort) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.view_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(stringResource(R.string.view_group_folders), settings.mode == BrowseMode.FOLDERS) {
                    onMode(if (it) BrowseMode.FOLDERS else BrowseMode.ALL)
                }
                SwitchRow(stringResource(R.string.view_as_list), settings.layout == CardLayout.LIST) {
                    onLayout(if (it) CardLayout.LIST else CardLayout.TILES)
                }
                SwitchRow(stringResource(R.string.view_details), settings.showDetails, onDetails)
                SwitchRow(stringResource(R.string.view_favorites_only), filter.favoriteOnly, onFavoritesOnly)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.view_sort), style = MaterialTheme.typography.titleSmall)
                (if (isVideo) VIDEO_SORTS else IMAGE_SORTS).forEach { sort ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSort(sort) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = filter.sort == sort, onClick = { onSort(sort) })
                        Text(stringResource(sortLabelRes(sort)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.view_done)) } },
    )
}

private fun sizeLabelRes(range: SizeRange): Int = when (range) {
    SizeRange.ALL -> R.string.size_all
    SizeRange.UNDER_100_MB -> R.string.size_under_100mb
    SizeRange.FROM_100_MB_TO_1_GB -> R.string.size_100mb_1gb
    SizeRange.FROM_1_GB_TO_5_GB -> R.string.size_1gb_5gb
    SizeRange.OVER_5_GB -> R.string.size_over_5gb
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** "Filter": Größe, Komprimierung, Wiedergabestand, Bewertung, Format, Verfügbarkeit, Kategorien, Schlagwörter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterDialog(
    filter: VideoFilter,
    isVideo: Boolean,
    showCategoryAndTags: Boolean,
    categories: List<FilterLabel>,
    tags: List<FilterLabel>,
    onChange: ((VideoFilter) -> VideoFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle(stringResource(R.string.filter_size))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizeRange.entries.forEach { r ->
                        FilterChip(
                            selected = filter.sizeRange == r,
                            onClick = { onChange { it.copy(sizeRange = r) } },
                            label = { Text(stringResource(sizeLabelRes(r))) },
                        )
                    }
                }
                if (isVideo) {
                    SectionTitle(stringResource(R.string.filter_compressed))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CompressedFilter.ALL to R.string.compressed_all,
                            CompressedFilter.ONLY to R.string.compressed_only,
                            CompressedFilter.NOT to R.string.compressed_not,
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = filter.compressed == value,
                                onClick = { onChange { it.copy(compressed = value) } },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                    SectionTitle(stringResource(R.string.filter_playback))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SeenFilter.ALL to R.string.filter_seen_all,
                            SeenFilter.UNSEEN to R.string.video_filter_unseen,
                            SeenFilter.IN_PROGRESS to R.string.video_filter_in_progress,
                            SeenFilter.SEEN to R.string.filter_seen_done,
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = filter.seen == value,
                                onClick = { onChange { it.copy(seen = value) } },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
                SectionTitle(stringResource(R.string.filter_rating))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 4, 5).forEach { r ->
                        FilterChip(
                            selected = filter.minRating == r,
                            onClick = { onChange { it.copy(minRating = r) } },
                            label = { Text(if (r == 0) stringResource(R.string.filter_any) else "★".repeat(r) + "+") },
                        )
                    }
                }
                SectionTitle(stringResource(R.string.filter_format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (isVideo) listOf("mp4", "mkv", "avi", "mov") else listOf("jpg", "png", "webp", "gif")).forEach { ext ->
                        FilterChip(
                            selected = filter.format == ext,
                            onClick = { onChange { it.copy(format = if (it.format == ext) null else ext) } },
                            label = { Text(ext) },
                        )
                    }
                }
                SwitchRow(stringResource(R.string.video_filter_online), filter.onlineOnly) { v -> onChange { it.copy(onlineOnly = v) } }
                if (showCategoryAndTags && categories.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.filter_categories))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { c ->
                            FilterChip(
                                selected = filter.categoryUuid == c.uuid,
                                onClick = { onChange { it.copy(categoryUuid = if (it.categoryUuid == c.uuid) null else c.uuid) } },
                                label = { Text(c.name) },
                            )
                        }
                    }
                }
                if (showCategoryAndTags && tags.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.filter_tags))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { t ->
                            FilterChip(
                                selected = filter.tagUuid == t.uuid,
                                onClick = { onChange { it.copy(tagUuid = if (it.tagUuid == t.uuid) null else t.uuid) } },
                                label = { Text("#" + t.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.view_done)) } },
        dismissButton = { TextButton(onClick = onReset) { Text(stringResource(R.string.filter_reset)) } },
    )
}
