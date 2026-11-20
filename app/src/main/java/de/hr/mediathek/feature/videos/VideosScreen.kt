@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package de.hr.mediathek.feature.videos

import androidx.compose.material.icons.outlined.StarBorder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import de.hr.mediathek.core.ui.AccentButton
import de.hr.mediathek.core.ui.AppCard
import de.hr.mediathek.core.ui.OutlineAccentButton
import de.hr.mediathek.core.ui.theme.CategoryChipColors
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.core.ui.bounceClick
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
    val seriesResult by compression.seriesResult.collectAsStateWithLifecycle()
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

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize().padding(if (wide) 20.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp)) {
        if (selected.isEmpty()) {
            AppCard(Modifier.fillMaxWidth(), bar = true, padding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(if (wide) 1f else 1f)) {
                        Text(stringResource(R.string.nav_videos), style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, color = c.onCard)
                        Text(
                            (if (foldersOn && path.isNotEmpty()) path.replace("/", " › ") + " · " else "") + stringResource(R.string.lib_count_videos, items.itemCount),
                            style = MaterialTheme.typography.bodySmall, color = c.onCardVariant, maxLines = 1,
                        )
                    }
                    if (wide) {
                        OutlinedTextField(
                            value = filter.search,
                            onValueChange = { text -> viewModel.update { it.copy(search = text) } },
                            placeholder = { Text(stringResource(R.string.video_search_in_folder)) },
                            singleLine = true,
                            shape = CircleShape,
                            modifier = Modifier.width(320.dp),
                        )
                    }
                    AccentButton(onClick = { viewModel.openRandom(onOpen) { noRandom = true } }, color = c.mint) { Text(stringResource(R.string.video_random)) }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more), tint = c.onCard)
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_title)) }, onClick = { moreMenu = false; showPlaylistDialog = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.dup_title)) }, onClick = { moreMenu = false; onOpenDuplicates() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.trash_title)) }, onClick = { moreMenu = false; onOpenTrash() })
                        }
                    }
                }
                if (!wide) {
                    OutlinedTextField(
                        value = filter.search,
                        onValueChange = { text -> viewModel.update { it.copy(search = text) } },
                        placeholder = { Text(stringResource(R.string.video_search)) },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            val flags by viewModel.selectionFlags.collectAsStateWithLifecycle()
            val moreActions = buildList {
                add(SelectionAction(stringResource(R.string.video_category_action)) {
                    viewModel.loadUsedCategories()
                    showCategoryDialog = true
                })
                add(SelectionAction(stringResource(R.string.tag_action)) {
                    viewModel.loadUsedTags()
                    showTagDialog = true
                })
                add(SelectionAction(stringResource(R.string.compress_action)) {
                    if (selected.size == 1) {
                        val id = selected.first()
                        compressTarget = id
                        compression.load(id)
                    } else {
                        compression.beginSeries(selected.toList())
                    }
                })
                add(SelectionAction(stringResource(R.string.playlist_add_action)) { showPlaylistPick = true })
                if (filter.playlistUuid != null) {
                    add(SelectionAction(stringResource(R.string.playlist_remove_action), viewModel::removeSelectedFromCurrentPlaylist))
                }
                add(SelectionAction(stringResource(R.string.trash_action)) { confirmTrash = 1 })
            }
            SelectionBar(
                count = selected.size,
                flags = flags,
                onClose = viewModel::clearSelection,
                onFavorite = { viewModel.favoriteSelected(flags.fav < selected.size) },
                onPee = { viewModel.markPeeSelected(flags.pee < selected.size) },
                onDeleteMark = { viewModel.markDeleteSelected(flags.del < selected.size) },
                onRate = viewModel::rateSelected,
                actions = moreActions,
                modifier = Modifier,
            )
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
                onMarkedDeleteOnly = { v -> viewModel.update { it.copy(markedDeleteOnly = v) } },
                onPeeOnly = { v -> viewModel.update { it.copy(peeOnly = v) } },
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
        seriesResult?.let { (done, total) ->
            AlertDialog(
                onDismissRequest = compression::dismissSeriesResult,
                text = {
                    Text(
                        when {
                            done == 0 -> stringResource(R.string.compress_series_result_none)
                            done < total -> stringResource(R.string.compress_series_result, done, total) + "\n" +
                                stringResource(R.string.compress_series_result_skipped, total - done)
                            else -> stringResource(R.string.compress_series_result, done, total) + "\n" +
                                stringResource(R.string.compress_queued)
                        },
                    )
                },
                confirmButton = { TextButton(onClick = compression::dismissSeriesResult) { Text(stringResource(R.string.storage_message_ok)) } },
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

        val tiles = viewSettings.layout == CardLayout.TILES
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillChip(stringResource(R.string.video_chip_all), filter.format == null && !filter.favoriteOnly && !filter.peeOnly) {
                        viewModel.update { it.copy(format = null, favoriteOnly = false, peeOnly = false) }
                    }
                    PillChip(stringResource(R.string.video_chip_favorites), filter.favoriteOnly) { viewModel.update { it.copy(favoriteOnly = !it.favoriteOnly) } }
                    PillChip(stringResource(R.string.video_chip_pee), filter.peeOnly) { viewModel.update { it.copy(peeOnly = !it.peeOnly) } }
                    val cat = categories.firstOrNull { it.uuid == filter.categoryUuid }
                    PillChip(cat?.name ?: stringResource(R.string.video_chip_category), cat != null) { showFilter = true }
                    OutlineAccentButton(onClick = { showViewSettings = true }, color = c.mint, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.view_button)) }
                    val activeCount = activeFilterCount(filter)
                    if (activeCount > 0) {
                        AccentButton(onClick = { showFilter = true }, color = c.mint, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.filter_button_active, activeCount)) }
                    } else {
                        OutlineAccentButton(onClick = { showFilter = true }, color = c.mint, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.filter_button)) }
                    }
                }
                if (foldersOn && path.isNotEmpty()) {
                    PathBar(path = path, onGoTo = viewModel.browser::goTo, modifier = Modifier.clip(CircleShape).background(c.bar).padding(horizontal = 8.dp))
                }
                if (items.itemCount == 0 && folders.isEmpty()) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(if (foldersOn && path.isNotEmpty()) R.string.lib_empty_folder else R.string.video_empty),
                            style = MaterialTheme.typography.bodyMedium, color = c.onCard,
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (tiles) 220.dp else 320.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
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
    }
}

/** Filter-Chip als Pille: inaktiv dunkel mit heller Schrift, aktiv in Mint mit dunkler Schrift. */
@Composable
private fun PillChip(label: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(if (active) c.mint else c.bar)
            .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) c.onAccent else c.onCard, maxLines = 1) }
}

/** Ordnerbaum entlang des aktuellen Pfads (aktiver Ordner als Mint-Pille) und die eigenen Kategorien als Chips. */
@Composable
private fun FolderTreeCard(
    path: String,
    folders: List<FolderItem>,
    categories: List<FilterLabel>,
    activeCategory: String?,
    onGoTo: (String) -> Unit,
    onEnter: (String) -> Unit,
    onCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val segments = if (path.isEmpty()) emptyList() else path.split("/")
    AppCard(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.video_folders), style = MaterialTheme.typography.titleMedium, color = c.onCard)
            TreeRow(stringResource(R.string.video_root_folder), 0, segments.isEmpty()) { onGoTo("") }
            segments.forEachIndexed { i, seg ->
                TreeRow(seg, i + 1, i == segments.lastIndex) { onGoTo(segments.take(i + 1).joinToString("/")) }
            }
            for (f in folders) TreeRow(f.name, segments.size + 1, false) { onEnter(f.name) }
            if (categories.isNotEmpty()) {
                Text(stringResource(R.string.video_my_categories), style = MaterialTheme.typography.titleMedium, color = c.onCard, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEachIndexed { i, cat ->
                        val (bg, fg) = CategoryChipColors[i % CategoryChipColors.size]
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(bg)
                                .border(if (cat.uuid == activeCategory) 2.dp else 0.dp, fg, CircleShape)
                                .clickable { onCategory(cat.uuid) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) { Text(cat.name, style = MaterialTheme.typography.labelMedium, color = fg) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeRow(name: String, depth: Int, active: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier
            .padding(start = (depth * 14).dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (active) c.mint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Folder, null, tint = if (active) c.onAccent else c.onCardVariant, modifier = Modifier.size(20.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, color = if (active) c.onAccent else c.onCard, maxLines = 1)
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
    val c = LocalAppColors.current
    val offline = video.status != MediaStatus.ONLINE
    val progress = watchProgress(video)
    val shape = RoundedCornerShape(26.dp)
    Surface(
        shape = shape,
        color = c.card,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (offline) 0.5f else 1f)
            .then(if (selected) Modifier.border(3.dp, c.rosa, shape) else Modifier)
            // Offline-Dateien bleiben sichtbar und lassen sich auswählen/bewerten, aber nicht abspielen.
            .bounceClick(onLongClick = onLongClick, onClick = onClick),
    ) {
        if (tiles) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumbnail(video.uuid, viewModel, progress, video.durationMs, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VideoTexts(video, offline, showDetails, compressed, progress)
                }
            }
        } else {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumbnail(video.uuid, viewModel, progress, video.durationMs, Modifier.width(128.dp).height(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VideoTexts(video, offline, showDetails, compressed, progress)
                }
            }
        }
    }
}

/** Markierung mit Form, Farbe und Wort (nie nur Farbe). */
@Composable
private fun MarkPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, fill: Color?, outline: Color?, text: Color) {
    val c = LocalAppColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .then(if (outline != null) Modifier.border(2.dp, outline, CircleShape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = text, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = text)
    }
}

@Composable
private fun VideoTexts(video: MediaFileEntity, offline: Boolean, showDetails: Boolean, compressed: Boolean, progress: Float?) {
    val c = LocalAppColors.current
    Text(video.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, color = c.onCard)
    if (showDetails) {
        Text(metaLine(video), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (video.rating > 0) {
            repeat(5) { i ->
                Icon(if (i < video.rating.coerceIn(0, 5)) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null, tint = if (i < video.rating.coerceIn(0, 5)) c.gold else c.onCardVariant, modifier = Modifier.size(16.dp))
            }
        } else {
            Text(stringResource(R.string.video_not_rated), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (video.favorite) MarkPill(stringResource(R.string.mark_fav), Icons.Filled.Favorite, c.rosa, null, c.onAccent)
        if (video.markedPee) MarkPill(stringResource(R.string.mark_pee), Icons.Filled.WaterDrop, c.hellblau, null, c.onAccent)
        if (video.markedDelete) MarkPill(stringResource(R.string.mark_delete), Icons.Filled.Delete, null, c.warnung, c.warnung)
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
private fun Thumbnail(uuid: String, viewModel: VideosViewModel, progress: Float?, durationMs: Long?, modifier: Modifier) {
    val file = remember(uuid) { viewModel.thumbnailOf(uuid) }
    val bitmap = de.hr.mediathek.feature.library.rememberThumbBitmap(file)
    val c = LocalAppColors.current
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.cardInset).border(2.dp, c.mint, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = c.onCardVariant)
        }
        // Die Laufzeit steht immer unten links im Eck, unabhängig vom Schalter für die Details.
        if (durationMs != null && durationMs > 0) {
            Text(
                formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = c.onCard,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = if (progress != null) 12.dp else 8.dp)
                    .background(c.onAccent, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            )
        }
    }
}
