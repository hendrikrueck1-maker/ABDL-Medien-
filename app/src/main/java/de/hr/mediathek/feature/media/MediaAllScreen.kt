package de.hr.mediathek.feature.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import de.hr.mediathek.data.db.MediaType
import de.hr.mediathek.data.prefs.BrowseMode
import de.hr.mediathek.feature.library.Badge
import de.hr.mediathek.feature.library.FilterDialog
import de.hr.mediathek.feature.library.FilterLabel
import de.hr.mediathek.feature.library.FolderCard
import de.hr.mediathek.feature.library.FolderItem
import de.hr.mediathek.feature.library.PathBar
import de.hr.mediathek.feature.library.ViewSettingsDialog
import de.hr.mediathek.feature.library.activeFilterCount
import de.hr.mediathek.feature.library.clearedFilters
import de.hr.mediathek.feature.library.rememberThumbBitmap
import de.hr.mediathek.feature.videos.VideoFilter
import de.hr.mediathek.feature.videos.VideoSort
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaAllScreen(
    onOpenVideo: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    viewModel: MediaAllViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val categories by viewModel.categoryList.collectAsStateWithLifecycle()
    val tags by viewModel.tagList.collectAsStateWithLifecycle()
    val settings by viewModel.browser.settings.collectAsStateWithLifecycle()
    val path by viewModel.browser.path.collectAsStateWithLifecycle()
    var showView by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    val foldersOn = settings.mode == BrowseMode.FOLDERS && filter.search.isBlank()
    BackHandler(enabled = foldersOn && path.isNotEmpty()) { viewModel.browser.up() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            stringResource(R.string.nav_media),
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
            OutlinedButton(onClick = { showView = true }) { Text(stringResource(R.string.view_button)) }
            val active = activeFilterCount(filter)
            if (active > 0) {
                Button(onClick = { showFilter = true }) { Text(stringResource(R.string.filter_button_active, active)) }
            } else {
                OutlinedButton(onClick = { showFilter = true }) { Text(stringResource(R.string.filter_button)) }
            }
        }
        if (foldersOn && path.isNotEmpty()) PathBar(path = path, onGoTo = viewModel.browser::goTo)
        if (showView) {
            ViewSettingsDialog(
                settings = settings,
                filter = filter,
                isVideo = false,
                onMode = viewModel.browser::setMode,
                onLayout = viewModel.browser::setLayout,
                onDetails = viewModel.browser::setShowDetails,
                onFavoritesOnly = { v -> viewModel.update { it.copy(favoriteOnly = v) } },
                onSort = { sort ->
                    if (sort == VideoSort.RANDOM && filter.sort == VideoSort.RANDOM) viewModel.reshuffle()
                    viewModel.update { it.copy(sort = sort) }
                },
                onDismiss = { showView = false },
            )
        }
        if (showFilter) {
            FilterDialog(
                filter = filter,
                isVideo = false,
                showCategoryAndTags = true,
                categories = categories.map { FilterLabel(it.uuid, it.name) },
                tags = tags.map { FilterLabel(it.uuid, it.name) },
                onChange = { change -> viewModel.update(change) },
                onReset = { viewModel.update { it.clearedFilters() } },
                onDismiss = { showFilter = false },
            )
        }
        if (items.itemCount == 0 && folders.isEmpty()) {
            Text(
                stringResource(if (foldersOn && path.isNotEmpty()) R.string.lib_empty_folder else R.string.media_empty),
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(folders, key = { "f:" + it.path }) { folder -> AllFolderCard(folder, filter, viewModel) }
            items(count = items.itemCount, key = { index -> items.peek(index)?.uuid ?: index }) { index ->
                val file = items[index]
                if (file != null) {
                    MediaCell(file, viewModel, settings.showDetails) {
                        if (file.status == MediaStatus.ONLINE) {
                            viewModel.rememberContext()
                            if (file.type == MediaType.VIDEO) onOpenVideo(file.uuid) else onOpenImage(file.uuid)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllFolderCard(folder: FolderItem, filter: VideoFilter, viewModel: MediaAllViewModel) {
    val previews by produceState<List<File>>(initialValue = emptyList(), folder.path, folder.count, filter.copy(search = "")) {
        value = viewModel.browser.preview(filter, folder.path)
    }
    FolderCard(
        item = folder,
        previews = previews,
        tiles = true,
        countLabel = stringResource(R.string.lib_count_files, folder.count),
        onClick = { viewModel.browser.enter(folder.name) },
    )
}

@Composable
private fun MediaCell(file: MediaFileEntity, viewModel: MediaAllViewModel, showDetails: Boolean, onClick: () -> Unit) {
    val bitmap = rememberThumbBitmap(viewModel.thumbnailOf(file.uuid))
    Column(Modifier.alpha(if (file.status == MediaStatus.ONLINE) 1f else 0.5f).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Badge(
                stringResource(if (file.type == MediaType.VIDEO) R.string.media_type_video else R.string.media_type_image),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        if (showDetails) {
            Text(file.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
