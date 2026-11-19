package de.hr.mediathek.feature.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.images.SimilarImageGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

@Composable
fun ImageDuplicatesScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: ImageDuplicatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val trashResult by viewModel.trashResult.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val analysing = stats.total > 0 && stats.done < stats.total

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
            Text(
                stringResource(R.string.image_dup_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(stringResource(R.string.image_dup_progress, stats.done, stats.total), style = MaterialTheme.typography.bodyMedium)
        if (stats.total > 0) {
            LinearProgressIndicator(
                progress = { stats.done.toFloat() / stats.total },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        if (analysing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::startAnalysis) { Text(stringResource(R.string.image_dup_start)) }
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.image_dup_refresh)) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            FilterChip(
                selected = state.mode == DuplicateMode.EXACT,
                onClick = { viewModel.setMode(DuplicateMode.EXACT) },
                label = { Text(stringResource(R.string.image_dup_exact)) },
            )
            FilterChip(
                selected = state.mode == DuplicateMode.SIMILAR,
                onClick = { viewModel.setMode(DuplicateMode.SIMILAR) },
                label = { Text(stringResource(R.string.image_dup_similar)) },
            )
        }
        if (state.mode == DuplicateMode.SIMILAR) {
            Text(stringResource(R.string.image_dup_threshold, state.threshold), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = state.threshold.toFloat(),
                onValueChange = { viewModel.setThreshold(it.toInt()) },
                onValueChangeFinished = viewModel::applyThreshold,
                valueRange = 0f..SimilarImageGroups.MAX_DISTANCE.toFloat(),
                steps = SimilarImageGroups.MAX_DISTANCE - 1,
            )
        }

        if (state.selected.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.video_selected, state.selected.size), color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.trash_action)) }
                TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.video_clear_selection)) }
            }
        }

        if (!state.loading && state.groups.isEmpty()) {
            Text(stringResource(R.string.image_dup_none), modifier = Modifier.padding(top = 16.dp))
        } else if (state.groups.isNotEmpty()) {
            Text(stringResource(R.string.image_dup_groups, state.groups.size), style = MaterialTheme.typography.labelLarge)
        }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.groups) { group ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.image_dup_group_size, group.images.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.markAllButLargest(group) }) {
                            Text(stringResource(R.string.image_dup_mark_rest))
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        group.images.forEach { image ->
                            DuplicateCell(image, viewModel, image.uuid in state.selected, onOpen)
                        }
                    }
                }
            }
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
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.trash_confirm_title_many_images, state.selected.size)) },
            text = { Text(stringResource(R.string.trash_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.trashSelected()
                }) { Text(stringResource(R.string.trash_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}

@Composable
private fun DuplicateCell(
    image: MediaFileEntity,
    viewModel: ImageDuplicatesViewModel,
    selected: Boolean,
    onOpen: (String) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, image.uuid) {
        value = withContext(Dispatchers.IO) { viewModel.thumbnailOf(image.uuid)?.let { BitmapFactory.decodeFile(it.path) } }
    }
    Column(Modifier.width(150.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onOpen(image.uuid) },
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = image.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { viewModel.toggle(image.uuid) })
            Text(
                formatSize(image.size) + dimensions(image),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            image.relativePath,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dimensions(image: MediaFileEntity): String {
    val w = image.width
    val h = image.height
    return if (w != null && h != null) " · ${w}×$h" else ""
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) DecimalFormat("0.0").format(mb) + " MB" else DecimalFormat("0").format(bytes / 1024.0) + " KB"
}
