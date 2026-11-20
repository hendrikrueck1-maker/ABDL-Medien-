package de.hr.mediathek.feature.videos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.db.TrashRow

@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirmItem by remember { mutableStateOf<TrashRow?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
            Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.bodySmall)
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.storage_message_ok)) }
        }
        if (items.isEmpty()) Text(stringResource(R.string.trash_empty))
        items.forEach { item ->
            val daysLeft = ((item.expiresAt - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
            Card(colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(stringResource(R.string.trash_days_left, daysLeft.toInt()), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.restore(item) }) { Text(stringResource(R.string.trash_restore)) }
                        OutlinedButton(onClick = { confirmItem = item }) { Text(stringResource(R.string.trash_delete_forever)) }
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            OutlinedButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.trash_delete_all)) }
        }
    }

    confirmItem?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmItem = null },
            title = { Text(stringResource(R.string.trash_delete_forever)) },
            text = { Text(stringResource(R.string.trash_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmItem = null
                    viewModel.deleteForever(item)
                }) { Text(stringResource(R.string.trash_delete_forever)) }
            },
            dismissButton = { TextButton(onClick = { confirmItem = null }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.trash_delete_all)) },
            text = { Text(stringResource(R.string.trash_delete_all_text, items.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    viewModel.deleteAll()
                }) { Text(stringResource(R.string.trash_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}

@Composable
fun DuplicatesScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}, viewModel: TrashViewModel = hiltViewModel()) {
    val groups by viewModel.duplicateGroups.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
            Text(stringResource(R.string.dup_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.dup_hint), style = MaterialTheme.typography.bodySmall)
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.storage_message_ok)) }
        }
        if (groups.isEmpty()) Text(stringResource(R.string.dup_none))
        groups.forEach { group ->
            Card(colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { m ->
                        val offline = m.status != MediaStatus.ONLINE
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Ein Tipp auf das Vorschaubild oder den Namen spielt das Video ab.
                            val thumb = de.hr.mediathek.feature.library.rememberThumbBitmap(remember(m.uuid) { viewModel.thumbnailOf(m.uuid) })
                            Box(
                                Modifier
                                    .width(128.dp)
                                    .height(72.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(Color.Black.copy(alpha = 0.25f))
                                    .clickable(enabled = !offline) {
                                        viewModel.prepareOpen()
                                        onOpen(m.uuid)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumb != null) {
                                    Image(thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text("▶", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(m.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text(m.relativePath, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                // Wer Bewertung/Favorit hat, soll das beim Aufräumen sehen.
                                val info = buildList {
                                    if (m.rating > 0) add("★".repeat(m.rating))
                                    if (m.favorite) add(stringResource(R.string.video_favorite))
                                    if (m.markedPee) add("💧")
                                    if (offline) add(stringResource(R.string.video_offline))
                                }.joinToString(" · ")
                                if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.prepareOpen()
                                            onOpen(m.uuid)
                                        },
                                        enabled = !offline,
                                    ) { Text(stringResource(R.string.dup_play)) }
                                    OutlinedButton(onClick = { confirm = m.uuid }, enabled = !offline) {
                                        Text(stringResource(R.string.dup_to_trash))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { uuid ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.dup_to_trash)) },
            text = { Text(stringResource(R.string.trash_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    viewModel.trashDuplicate(uuid)
                }) { Text(stringResource(R.string.dup_to_trash)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}
