package de.hr.mediathek.feature.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
import de.hr.mediathek.feature.videos.CategoryDialog
import de.hr.mediathek.feature.videos.LabelItem
import de.hr.mediathek.feature.videos.VideoSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagesScreen(
    onOpen: (String) -> Unit,
    onOpenDuplicates: () -> Unit = {},
    onOpenSlideshow: () -> Unit = {},
    viewModel: ImagesViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.images.collectAsLazyPagingItems()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val categories by viewModel.categoryList.collectAsStateWithLifecycle()
    val tags by viewModel.tagList.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val usedCategories by viewModel.usedCategories.collectAsStateWithLifecycle()
    val usedTags by viewModel.usedTags.collectAsStateWithLifecycle()
    val trashResult by viewModel.trashResult.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var rateMenu by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (selected.isEmpty()) {
            Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.nav_images),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.rememberContext(); onOpenSlideshow() }) { Text(stringResource(R.string.slideshow_open)) }
                TextButton(onClick = onOpenDuplicates) { Text(stringResource(R.string.image_dup_open)) }
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
                androidx.compose.foundation.layout.Box {
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
                OutlinedButton(onClick = { viewModel.loadUsed(); showCategories = true }) {
                    Text(stringResource(R.string.video_category_action))
                }
                OutlinedButton(onClick = { viewModel.loadUsed(); showTags = true }) { Text(stringResource(R.string.tag_action)) }
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
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { sortMenu = true }) { Text(stringResource(sortLabel(filter.sort))) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    IMAGE_SORTS.forEach { sort ->
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
                selected = filter.onlineOnly,
                onClick = { viewModel.update { it.copy(onlineOnly = !it.onlineOnly) } },
                label = { Text(stringResource(R.string.video_filter_online)) },
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = filter.folder == folder,
                    onClick = { viewModel.update { it.copy(folder = if (it.folder == folder) null else folder) } },
                    label = { Text("📁 $folder") },
                )
            }
            tags.forEach { tag ->
                FilterChip(
                    selected = filter.tagUuid == tag.uuid,
                    onClick = { viewModel.update { it.copy(tagUuid = if (it.tagUuid == tag.uuid) null else tag.uuid) } },
                    label = { Text("#" + tag.name) },
                )
            }
            categories.forEach { category ->
                FilterChip(
                    selected = filter.categoryUuid == category.uuid,
                    onClick = {
                        viewModel.update { it.copy(categoryUuid = if (it.categoryUuid == category.uuid) null else category.uuid) }
                    },
                    label = { Text(category.name) },
                )
            }
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
                title = { Text(stringResource(if (second) R.string.trash_confirm_title_many_images else R.string.trash_confirm_title_images, count)) },
                text = { Text(stringResource(if (second) R.string.trash_confirm_text_many else R.string.trash_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
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
        if (showCategories) {
            CategoryDialog(
                title = stringResource(R.string.video_category_action),
                categories = categories.map { LabelItem(it.uuid, it.name) },
                used = usedCategories,
                onAssign = viewModel::assignCategory,
                onUnassign = viewModel::unassignCategory,
                onCreate = viewModel::createCategory,
                onDelete = viewModel::deleteCategory,
                onDismiss = { showCategories = false },
            )
        }
        if (showTags) {
            CategoryDialog(
                title = stringResource(R.string.tag_action),
                categories = tags.map { LabelItem(it.uuid, it.name) },
                used = usedTags,
                onAssign = viewModel::assignTag,
                onUnassign = viewModel::unassignTag,
                onCreate = viewModel::createTag,
                onDelete = viewModel::deleteTag,
                onDismiss = { showTags = false },
            )
        }

        if (items.itemCount == 0) {
            Text(stringResource(R.string.image_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 24.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = items.itemCount, key = { index -> items.peek(index)?.uuid ?: index }) { index ->
                val image = items[index]
                if (image != null) {
                    ImageCell(
                        image = image,
                        viewModel = viewModel,
                        selected = image.uuid in selected,
                        onClick = {
                            when {
                                selected.isNotEmpty() -> viewModel.toggleSelected(image.uuid)
                                image.status == MediaStatus.ONLINE -> {
                                    viewModel.rememberContext()
                                    onOpen(image.uuid)
                                }
                            }
                        },
                        onLongClick = { viewModel.toggleSelected(image.uuid) },
                    )
                }
            }
        }
    }
}

private val IMAGE_SORTS = listOf(
    VideoSort.ADDED_DESC, VideoSort.NAME_ASC, VideoSort.NAME_DESC, VideoSort.DATE_DESC,
    VideoSort.RATING_DESC, VideoSort.SIZE_DESC, VideoSort.RANDOM,
)

private fun sortLabel(sort: VideoSort): Int = when (sort) {
    VideoSort.NAME_ASC -> R.string.sort_name_asc
    VideoSort.NAME_DESC -> R.string.sort_name_desc
    VideoSort.DATE_DESC -> R.string.sort_date
    VideoSort.RATING_DESC -> R.string.sort_rating
    VideoSort.SIZE_DESC -> R.string.sort_size
    VideoSort.RANDOM -> R.string.sort_random
    else -> R.string.sort_added
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageCell(
    image: MediaFileEntity,
    viewModel: ImagesViewModel,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val offline = image.status != MediaStatus.ONLINE
    val bitmap by produceState<Bitmap?>(initialValue = null, image.uuid) {
        value = withContext(Dispatchers.IO) { viewModel.thumbnailOf(image.uuid)?.let { BitmapFactory.decodeFile(it.path) } }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(if (offline) 0.5f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = image.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp)) {
            if (image.rating > 0) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.padding(end = 2.dp))
                Text("${image.rating}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (image.favorite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.video_favorite),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
        if (offline) {
            Text(stringResource(R.string.video_offline), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.Center))
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
            Text("✓", color = Color.White, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Center))
        }
    }
}
