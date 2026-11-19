package de.hr.mediathek.feature.videos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.verticalScroll
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.grid.GridItemSpan
import de.hr.mediathek.data.prefs.CardLayout
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
import java.io.File
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.db.SeenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VideosScreen(
    onOpen: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenDuplicates: () -> Unit,
    viewModel: VideosViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.videos.collectAsLazyPagingItems()
    var sortMenu by remember { mutableStateOf(false) }
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val categories by viewModel.categoryList.collectAsStateWithLifecycle()
    val usedCategories by viewModel.usedCategories.collectAsStateWithLifecycle()
    var showCategoryDialog by remember { mutableStateOf(false) }
    val tags by viewModel.tagList.collectAsStateWithLifecycle()
    val usedTags by viewModel.usedTags.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showTagDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showPlaylistPick by remember { mutableStateOf(false) }
    var noRandom by remember { mutableStateOf(false) }
    val compression: CompressionViewModel = hiltViewModel()
    val plan by compression.plan.collectAsStateWithLifecycle()
    val queued by compression.queued.collectAsStateWithLifecycle()
    val test by compression.test.collectAsStateWithLifecycle()
    val series by compression.series.collectAsStateWithLifecycle()
    var compressTarget by remember { mutableStateOf<String?>(null) }
    var rateMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showViewSettings by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    val viewSettings by viewModel.browser.settings.collectAsStateWithLifecycle()
    val path by viewModel.browser.path.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val compressedUuids by viewModel.compressedUuids.collectAsStateWithLifecycle()
    val foldersOn = viewSettings.mode == BrowseMode.FOLDERS && filter.search.isBlank() && filter.playlistUuid == null
    BackHandler(enabled = foldersOn && path.isNotEmpty()) { viewModel.browser.up() }
    BackHandler(enabled = selected.isNotEmpty()) { viewModel.clearSelection() }
    val trashResult by viewModel.trashResult.collectAsStateWithLifecycle()
    var confirmTrash by remember { mutableStateOf(0) } // 0 = zu, 1 = erste Rückfrage, 2 = zweite ab 10 Dateien

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (selected.isEmpty()) {
            Row(
                Modifier.padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.nav_videos),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.openRandom(onOpen) { noRandom = true } }) { Text(stringResource(R.string.video_random)) }
                Box {
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_title)) }, onClick = { moreMenu = false; showPlaylistDialog = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.dup_title)) }, onClick = { moreMenu = false; onOpenDuplicates() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.trash_title)) }, onClick = { moreMenu = false; onOpenTrash() })
                    }
                }
            }
        } else {
            Row(
                Modifier.padding(top = 8.dp, bottom = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.video_selected, selected.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = { viewModel.favoriteSelected(true) }) { Text(stringResource(R.string.video_fav_set)) }
                OutlinedButton(onClick = { viewModel.favoriteSelected(false) }) { Text(stringResource(R.string.video_fav_unset)) }
                Box {
                    OutlinedButton(onClick = { rateMenu = true }) { Text(stringResource(R.string.video_rate_action)) }
                    DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                        (0..5).forEach { r ->
                            DropdownMenuItem(
                                text = { Text(if (r == 0) stringResource(R.string.video_rate_none) else "★".repeat(r)) },
                                onClick = {
                                    rateMenu = false
                                    viewModel.rateSelected(r)
                                },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = {
                    viewModel.loadUsedCategories()
                    showCategoryDialog = true
                }) { Text(stringResource(R.string.video_category_action)) }
                OutlinedButton(onClick = {
                    viewModel.loadUsedTags()
                    showTagDialog = true
                }) { Text(stringResource(R.string.tag_action)) }
                OutlinedButton(onClick = {
                    if (selected.size == 1) {
                        val id = selected.first()
                        compressTarget = id
                        compression.load(id)
                    } else {
                        compression.beginSeries(selected.toList())
                    }
                }) { Text(stringResource(R.string.compress_action)) }
                OutlinedButton(onClick = { showPlaylistPick = true }) { Text(stringResource(R.string.playlist_add_action)) }
                if (filter.playlistUuid != null) {
                    OutlinedButton(onClick = viewModel::removeSelectedFromCurrentPlaylist) { Text(stringResource(R.string.playlist_remove_action)) }
                }
                OutlinedButton(onClick = { confirmTrash = 1 }) { Text(stringResource(R.string.trash_action)) }
                TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.video_clear_selection)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter.search,
                onValueChange = { text -> viewModel.update { it.copy(search = text) } },
                label = { Text(stringResource(R.string.video_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { showViewSettings = true }) { Text(stringResource(R.string.view_button)) }
            val activeCount = activeFilterCount(filter)
            if (activeCount > 0) {
                Button(onClick = { showFilter = true }) { Text(stringResource(R.string.filter_button_active, activeCount)) }
            } else {
                OutlinedButton(onClick = { showFilter = true }) { Text(stringResource(R.string.filter_button)) }
            }
        }
        if (foldersOn && path.isNotEmpty()) {
            PathBar(path = path, onGoTo = viewModel.browser::goTo)
        }
        filter.playlistUuid?.let { pl ->
            Row(Modifier.padding(vertical = 4.dp)) {
                FilterChip(
                    selected = true,
                    onClick = { viewModel.update { it.copy(playlistUuid = null, sort = VideoSort.ADDED_DESC) } },
                    label = { Text("▶ " + (playlists.firstOrNull { it.uuid == pl }?.name ?: "") + " ✕") },
                )
            }
        }
        if (showViewSettings) {
            ViewSettingsDialog(
                settings = viewSettings,
                filter = filter,
                isVideo = true,
                onMode = viewModel.browser::setMode,
                onLayout = viewModel.browser::setLayout,
                onDetails = viewModel.browser::setShowDetails,
                onFavoritesOnly = { v -> viewModel.update { it.copy(favoriteOnly = v) } },
                onSort = { sort ->
                    if (sort == VideoSort.RANDOM && filter.sort == VideoSort.RANDOM) viewModel.reshuffle()
                    viewModel.update { it.copy(sort = sort) }
                },
                onDismiss = { showViewSettings = false },
            )
        }
        if (showFilter) {
            FilterDialog(
                filter = filter,
                isVideo = true,
                showCategoryAndTags = true,
                categories = categories.map { FilterLabel(it.uuid, it.name) },
                tags = tags.map { FilterLabel(it.uuid, it.name) },
                onChange = { change -> viewModel.update(change) },
                onReset = { viewModel.update { it.clearedFilters() } },
                onDismiss = { showFilter = false },
            )
        }

        trashResult?.let { r ->
            AlertDialog(
                onDismissRequest = viewModel::dismissTrashResult,
                title = { Text(stringResource(R.string.trash_title)) },
                text = { Text(stringResource(R.string.trash_result, r.done, r.failed)) },
                confirmButton = { TextButton(onClick = viewModel::dismissTrashResult) { Text(stringResource(R.string.storage_message_ok)) } },
            )
        }
        if (confirmTrash > 0) {
            val count = selected.size
            val second = confirmTrash == 2
            AlertDialog(
                onDismissRequest = { confirmTrash = 0 },
                title = { Text(stringResource(if (second) R.string.trash_confirm_title_many else R.string.trash_confirm_title, count)) },
                text = { Text(stringResource(if (second) R.string.trash_confirm_text_many else R.string.trash_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        // Ab 10 Dateien kommt eine zweite, deutlichere Rückfrage.
                        if (count >= 10 && !second) {
                            confirmTrash = 2
                        } else {
                            confirmTrash = 0
                            viewModel.trashSelected()
                        }
                    }) { Text(stringResource(R.string.trash_action)) }
                },
                dismissButton = { TextButton(onClick = { confirmTrash = 0 }) { Text(stringResource(R.string.diag_cancel)) } },
            )
        }
        if (showCategoryDialog) {
            CategoryDialog(
                title = stringResource(R.string.video_category_action),
                categories = categories.map { LabelItem(it.uuid, it.name) },
                used = usedCategories,
                onAssign = viewModel::assignSelected,
                onUnassign = viewModel::unassignSelected,
                onCreate = viewModel::createCategory,
                onDelete = viewModel::deleteCategory,
                onDismiss = { showCategoryDialog = false },
            )
        }

        if (showTagDialog) {
            CategoryDialog(
                title = stringResource(R.string.tag_action),
                categories = tags.map { LabelItem(it.uuid, it.name) },
                used = usedTags,
                onAssign = viewModel::assignTag,
                onUnassign = viewModel::unassignTag,
                onCreate = viewModel::createTag,
                onDelete = viewModel::deleteTag,
                onDismiss = { showTagDialog = false },
            )
        }
        if (showPlaylistDialog) {
            PlaylistDialog(
                playlists = playlists,
                pickMode = false,
                onOpen = { showPlaylistDialog = false; viewModel.applyPlaylist(it) },
                onAddSelected = {},
                onCreate = viewModel::createPlaylist,
                onSaveSmart = viewModel::saveSmartPlaylist,
                onDelete = viewModel::deletePlaylist,
                onDismiss = { showPlaylistDialog = false },
            )
        }
        if (showPlaylistPick) {
            PlaylistDialog(
                playlists = playlists,
                pickMode = true,
                onOpen = {},
                onAddSelected = { showPlaylistPick = false; viewModel.addSelectedToPlaylist(it.uuid) },
                onCreate = viewModel::createPlaylist,
                onSaveSmart = {},
                onDelete = {},
                onDismiss = { showPlaylistPick = false },
            )
        }
        plan?.let { state ->
            CompressDialog(
                state = state,
                test = test,
                onRunTest = { level -> compressTarget?.let { compression.runTest(it, level) } },
                onClearTest = compression::clearTest,
                onStart = { level, confirmed ->
                    compressTarget?.let { compression.start(it, level, confirmed) }
                    compression.clearTest()
                    viewModel.clearSelection()
                },
                onDismiss = {
                    compression.clearTest()
                    compression.dismissPlan()
                },
            )
        }
        series?.let { uuids ->
            SeriesDialog(
                count = uuids.size,
                test = test,
                onRunTest = { level -> compression.runTest(uuids.first(), level) },
                onClearTest = compression::clearTest,
                onStart = { level ->
                    compression.startSeries(level)
                    viewModel.clearSelection()
                },
                onDismiss = compression::dismissSeries,
            )
        }
        queued?.let { ok ->
            AlertDialog(
                onDismissRequest = compression::dismissQueued,
                text = { Text(stringResource(if (ok) R.string.compress_queued else R.string.compress_exists)) },
                confirmButton = { TextButton(onClick = compression::dismissQueued) { Text(stringResource(R.string.storage_message_ok)) } },
            )
        }
        if (noRandom) {
            AlertDialog(
                onDismissRequest = { noRandom = false },
                text = { Text(stringResource(R.string.video_random_none)) },
                confirmButton = { TextButton(onClick = { noRandom = false }) { Text(stringResource(R.string.storage_message_ok)) } },
            )
        }

        if (items.itemCount == 0 && folders.isEmpty()) {
            Text(
                stringResource(if (foldersOn && path.isNotEmpty()) R.string.lib_empty_folder else R.string.video_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        val tiles = viewSettings.layout == CardLayout.TILES
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (tiles) 200.dp else 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(folders, key = { "f:" + it.path }) { folder ->
                VideoFolderCard(folder, filter, tiles, viewModel)
            }
            items(count = items.itemCount, key = { index -> items.peek(index)?.uuid ?: index }) { index ->
                val video = items[index]
                if (video != null) {
                    VideoCard(
                        video = video,
                        viewModel = viewModel,
                        selected = video.uuid in selected,
                        tiles = tiles,
                        showDetails = viewSettings.showDetails,
                        compressed = video.uuid in compressedUuids,
                        onClick = {
                            if (selected.isNotEmpty()) {
                                viewModel.toggleSelected(video.uuid)
                            } else {
                                viewModel.rememberContext()
                                onOpen(video.uuid)
                            }
                        },
                        onLongClick = { viewModel.toggleSelected(video.uuid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoFolderCard(folder: FolderItem, filter: VideoFilter, tiles: Boolean, viewModel: VideosViewModel) {
    val previews by produceState<List<File>>(initialValue = emptyList(), folder.path, folder.count, filter.copy(search = "")) {
        value = viewModel.browser.preview(filter, folder.path)
    }
    FolderCard(
        item = folder,
        previews = previews,
        tiles = tiles,
        countLabel = stringResource(R.string.lib_count_videos, folder.count),
        onClick = { viewModel.browser.enter(folder.name) },
    )
}

@Composable
internal fun CategoryDialog(
    title: String,
    categories: List<LabelItem>,
    used: Set<String>,
    onAssign: (String) -> Unit,
    onUnassign: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<LabelItem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (categories.isEmpty()) Text(stringResource(R.string.category_none))
                categories.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            c.name + if (c.uuid in used) " ✓" else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = { onAssign(c.uuid) }) { Text(stringResource(R.string.category_add)) }
                        TextButton(onClick = { onUnassign(c.uuid) }) { Text(stringResource(R.string.category_remove)) }
                        TextButton(onClick = { confirmDelete = c }) { Text(stringResource(R.string.category_delete)) }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.category_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.category_create)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_message_ok)) } },
    )
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.category_delete_title, c.name)) },
            text = { Text(stringResource(R.string.category_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(c.uuid)
                    confirmDelete = null
                }) { Text(stringResource(R.string.category_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}

internal data class LabelItem(val uuid: String, val name: String)

@Composable
private fun PlaylistDialog(
    playlists: List<de.hr.mediathek.data.db.PlaylistEntity>,
    pickMode: Boolean,
    onOpen: (de.hr.mediathek.data.db.PlaylistEntity) -> Unit,
    onAddSelected: (de.hr.mediathek.data.db.PlaylistEntity) -> Unit,
    onCreate: (String) -> Unit,
    onSaveSmart: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<de.hr.mediathek.data.db.PlaylistEntity?>(null) }
    val list = if (pickMode) playlists.filter { it.type == de.hr.mediathek.data.db.PlaylistType.MANUAL } else playlists
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (pickMode) R.string.playlist_pick_title else R.string.playlist_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) Text(stringResource(R.string.playlist_none))
                list.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            p.name + if (p.type == de.hr.mediathek.data.db.PlaylistType.SMART) " " + stringResource(R.string.playlist_smart_suffix) else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (pickMode) {
                            TextButton(onClick = { onAddSelected(p) }) { Text(stringResource(R.string.category_add)) }
                        } else {
                            TextButton(onClick = { onOpen(p) }) { Text(stringResource(R.string.playlist_open)) }
                            TextButton(onClick = { confirmDelete = p }) { Text(stringResource(R.string.category_delete)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.playlist_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCreate(newName); newName = "" }, enabled = newName.isNotBlank()) {
                        Text(stringResource(R.string.playlist_create))
                    }
                    if (!pickMode) {
                        OutlinedButton(onClick = { onSaveSmart(newName); newName = "" }, enabled = newName.isNotBlank()) {
                            Text(stringResource(R.string.playlist_save_smart))
                        }
                    }
                }
                if (!pickMode) Text(stringResource(R.string.playlist_smart_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_message_ok)) } },
    )
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.category_delete_title, p.name)) },
            text = { Text(stringResource(R.string.playlist_delete_text)) },
            confirmButton = {
                TextButton(onClick = { onDelete(p.uuid); confirmDelete = null }) { Text(stringResource(R.string.category_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VideoCard(
    video: MediaFileEntity,
    viewModel: VideosViewModel,
    selected: Boolean,
    tiles: Boolean,
    showDetails: Boolean,
    compressed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val offline = video.status != MediaStatus.ONLINE
    val progress = watchProgress(video)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (offline) 0.5f else 1f)
            // Offline-Dateien bleiben sichtbar und lassen sich auswählen/bewerten, aber nicht abspielen.
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (tiles) {
            Column {
                Thumbnail(video.uuid, viewModel, progress, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VideoTexts(video, offline, showDetails, compressed, progress)
                }
            }
        } else {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumbnail(video.uuid, viewModel, progress, Modifier.width(128.dp).height(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VideoTexts(video, offline, showDetails, compressed, progress)
                }
            }
        }
    }
}

@Composable
private fun VideoTexts(video: MediaFileEntity, offline: Boolean, showDetails: Boolean, compressed: Boolean, progress: Float?) {
    Text(
        video.displayName,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (showDetails) {
        Text(
            metaLine(video),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(video.rating.coerceIn(0, 5)) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFFC107), modifier = Modifier.height(16.dp))
        }
        if (video.favorite) {
            Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.video_favorite), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(16.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compressed) Badge(stringResource(R.string.badge_compressed))
        if (video.seenState == SeenState.SEEN) {
            Badge(stringResource(R.string.badge_seen))
        } else if (progress != null) {
            Badge(stringResource(R.string.badge_progress, (progress * 100).toInt().coerceIn(1, 99)))
        }
        if (offline) Badge(stringResource(R.string.badge_offline))
    }
}

/** Fortschritt 0..1, nur für angefangene, noch nicht fertig gesehene Videos mit bekannter Dauer. */
private fun watchProgress(video: MediaFileEntity): Float? {
    val duration = video.durationMs ?: return null
    if (duration <= 0 || video.seenState == SeenState.SEEN || video.resumePositionMs <= 0) return null
    return (video.resumePositionMs.toFloat() / duration).coerceIn(0f, 1f)
}

private fun metaLine(video: MediaFileEntity): String {
    val parts = ArrayList<String>()
    video.durationMs?.let { parts += formatDuration(it) }
    if (video.width != null && video.height != null) parts += "${video.width}×${video.height}"
    parts += formatSize(video.size)
    video.displayName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.let { parts += it.uppercase() }
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
private fun Thumbnail(uuid: String, viewModel: VideosViewModel, progress: Float?, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, uuid) {
        value = withContext(Dispatchers.IO) {
            viewModel.thumbnailOf(uuid)?.let { BitmapFactory.decodeFile(it.path) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            )
        }
    }
}
