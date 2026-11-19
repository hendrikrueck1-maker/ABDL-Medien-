package de.hr.mediathek.feature.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.feature.videos.CompressionViewModel

/** Zeigt Stand der Komprimierung und Originale in der Schonfrist; löscht sie auf Wunsch sofort. */
@Composable
fun CompressionCard(viewModel: CompressionViewModel = hiltViewModel()) {
    val grace by viewModel.grace.collectAsStateWithLifecycle()
    val blocked by viewModel.blockedCount.collectAsStateWithLifecycle()
    val job by viewModel.latestJob.collectAsStateWithLifecycle()
    val purged by viewModel.purged.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val onlyCharging by viewModel.onlyCharging.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    val running = job?.status == JobStatus.QUEUED || job?.status == JobStatus.RUNNING

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.compress_card_title), style = MaterialTheme.typography.titleMedium, color = onColor)
            job?.let { j ->
                val text = when {
                    running -> j.phase ?: stringResource(R.string.compress_waiting)
                    j.status == JobStatus.FAILED -> j.lastError ?: ""
                    else -> j.phase ?: ""
                }
                if (text.isNotBlank()) {
                    Text(
                        stringResource(R.string.compress_last_job, text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (j.status == JobStatus.FAILED) MaterialTheme.colorScheme.error else onColor,
                    )
                }
                if (running) {
                    val p = j.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = onlyCharging, onCheckedChange = viewModel::setOnlyCharging)
                Text(stringResource(R.string.compress_only_charging), style = MaterialTheme.typography.bodyMedium, color = onColor)
            }
            if (queue.isNotEmpty() || paused) {
                Text(
                    stringResource(if (paused) R.string.compress_queue_paused else R.string.compress_queue_title, queue.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = onColor,
                )
                queue.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (item.running) "▶ " else "${index + 1}. ") + item.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = onColor,
                        )
                        if (!item.running) {
                            TextButton(onClick = { viewModel.move(item.jobUuid, true) }, enabled = index > 0 && !queue[index - 1].running) { Text("↑") }
                            TextButton(onClick = { viewModel.move(item.jobUuid, false) }, enabled = index < queue.lastIndex) { Text("↓") }
                        }
                        TextButton(onClick = { viewModel.cancel(item.jobUuid) }) { Text(stringResource(R.string.diag_cancel)) }
                    }
                }
                OutlinedButton(onClick = { viewModel.setPaused(!paused) }) {
                    Text(stringResource(if (paused) R.string.compress_resume else R.string.compress_pause))
                }
            }
            Text(
                if (grace.count == 0) stringResource(R.string.compress_grace_none)
                else stringResource(R.string.compress_grace, grace.count, de.hr.mediathek.feature.videos.size(grace.bytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = onColor,
            )
            if (blocked > 0) {
                Text(stringResource(R.string.compress_blocked, blocked), style = MaterialTheme.typography.bodySmall, color = onColor)
            }
            OutlinedButton(onClick = { confirm = true }, enabled = grace.count > 0) {
                Text(stringResource(R.string.compress_purge))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.compress_purge)) },
            text = { Text(stringResource(R.string.compress_purge_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.purgeNow()
                }) { Text(stringResource(R.string.compress_purge_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
    purged?.let { n ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPurged,
            text = { Text(stringResource(R.string.compress_purge_done, n)) },
            confirmButton = { TextButton(onClick = viewModel::dismissPurged) { Text(stringResource(R.string.storage_message_ok)) } },
        )
    }
}
