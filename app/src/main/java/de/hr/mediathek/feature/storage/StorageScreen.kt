package de.hr.mediathek.feature.storage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.core.jobs.JobRunner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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

@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val thumbs by viewModel.thumbs.collectAsStateWithLifecycle()
    val onboardingSeen by viewModel.onboardingSeen.collectAsStateWithLifecycle()
    var showOnboarding by remember { mutableStateOf(false) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addSource(uri)
    }
    val workPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setWorkFolder(uri)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    val requestNotificationPermissionIfNeeded: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = { showOnboarding = false },
            title = { Text(stringResource(R.string.storage_onboarding_title)) },
            text = { Text(stringResource(R.string.storage_onboarding_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showOnboarding = false
                    viewModel.confirmOnboarding()
                    sourcePicker.launch(null)
                }) { Text(stringResource(R.string.storage_onboarding_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showOnboarding = false }) { Text(stringResource(R.string.diag_cancel)) }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
            Text(
                text = stringResource(R.string.storage_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.message?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(msg.textRes()),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.storage_message_ok)) }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.storage_sources_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (state.sources.isEmpty()) {
                    Text(
                        stringResource(R.string.storage_sources_empty),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                state.sources.forEach { item ->
                    SourceBlock(
                        item = item,
                        onScan = {
                            requestNotificationPermissionIfNeeded()
                            viewModel.scan(item.source)
                        },
                        onCancel = { item.job?.let { viewModel.cancelJob(it.uuid) } },
                        onConfirm = { info -> viewModel.confirmMissing(info.jobUuid) },
                        onDismiss = { info -> viewModel.dismissConfirmation(info.jobUuid) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (onboardingSeen) sourcePicker.launch(null) else showOnboarding = true
                    }) { Text(stringResource(R.string.storage_add_source)) }
                    OutlinedButton(onClick = viewModel::refreshStatus) {
                        Text(stringResource(R.string.storage_refresh))
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val onColor = MaterialTheme.colorScheme.onPrimaryContainer
                Text(
                    stringResource(R.string.thumbs_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = onColor,
                )
                Text(
                    stringResource(R.string.thumbs_stats, thumbs.count, thumbs.bytes / (1024 * 1024)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor,
                )
                if (thumbs.lowSpace) {
                    Text(stringResource(R.string.thumbs_low_space), color = MaterialTheme.colorScheme.error)
                } else if (thumbs.full) {
                    Text(stringResource(R.string.thumbs_full), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::generateThumbnails) { Text(stringResource(R.string.thumbs_generate)) }
                    OutlinedButton(onClick = viewModel::clearThumbnails) { Text(stringResource(R.string.thumbs_clear)) }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.storage_work_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    stringResource(R.string.storage_work_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = state.workFolderLabel?.let { stringResource(R.string.storage_work_current, it) }
                        ?: stringResource(R.string.storage_work_none),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Button(onClick = { workPicker.launch(null) }) {
                    Text(stringResource(R.string.storage_work_choose))
                }
            }
        }
    }
}

private fun StorageMessage.textRes(): Int = when (this) {
    StorageMessage.SOURCE_ADDED -> R.string.storage_msg_source_added
    StorageMessage.SOURCE_EXISTS -> R.string.storage_msg_source_exists
    StorageMessage.SOURCE_FAILED -> R.string.storage_msg_source_failed
    StorageMessage.WORK_FOLDER_SET -> R.string.storage_msg_work_set
    StorageMessage.WORK_FOLDER_REJECTED -> R.string.storage_msg_work_rejected
    StorageMessage.WORK_FOLDER_FAILED -> R.string.storage_msg_work_failed
    StorageMessage.SCAN_ALREADY_ACTIVE -> R.string.storage_msg_scan_active
}

@Composable
private fun SourceBlock(
    item: SourceItem,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (ConfirmInfo) -> Unit,
    onDismiss: (ConfirmInfo) -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSecondaryContainer
    val job = item.job
    val busy = job != null && (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING || job.status == JobStatus.PAUSED)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.source.label, style = MaterialTheme.typography.titleSmall, color = textColor)
        Text(
            stringResource(
                when (item.reachable) {
                    true -> R.string.storage_status_online
                    false -> R.string.storage_status_offline
                    null -> R.string.storage_status_checking
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
        if (item.total > 0) {
            Text(
                stringResource(R.string.storage_counts, item.total, item.offline),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
        }

        when {
            item.confirm != null -> {
                Text(
                    stringResource(R.string.storage_confirm_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.storage_confirm_text, item.confirm.missing, item.confirm.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onConfirm(item.confirm) }) { Text(stringResource(R.string.storage_confirm_yes)) }
                    OutlinedButton(onClick = { onDismiss(item.confirm) }) { Text(stringResource(R.string.storage_confirm_no)) }
                }
            }
            job != null && job.status == JobStatus.QUEUED -> {
                Text(stringResource(R.string.storage_scan_queued), style = MaterialTheme.typography.bodySmall, color = textColor)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            job != null && job.status == JobStatus.RUNNING -> {
                Text(job.phase ?: "", style = MaterialTheme.typography.bodySmall, color = textColor)
                val progress = job.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            job != null && job.status == JobStatus.COMPLETED ->
                Text(job.phase ?: "", style = MaterialTheme.typography.bodySmall, color = textColor)
            job != null && job.status == JobStatus.FAILED ->
                Text(
                    stringResource(R.string.storage_scan_failed, job.lastError ?: "-"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            job != null && job.status == JobStatus.CANCELLED ->
                Text(stringResource(R.string.storage_scan_cancelled), style = MaterialTheme.typography.bodySmall, color = textColor)
        }

        if (item.confirm == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, enabled = item.reachable == true && !busy) {
                    Text(stringResource(R.string.storage_scan_now))
                }
                if (job != null && (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING)) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.storage_scan_cancel)) }
                }
            }
        }
    }
}
