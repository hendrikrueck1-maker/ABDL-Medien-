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
import de.hr.mediathek.core.ui.glass
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

/** Bereiche der Speicher-Einstellungen; jeder hat einen eigenen Menüpunkt unter Einstellungen. */
object StorageSection {
    const val SOURCES = "sources"
    const val BACKUP = "backup"
    const val COMPRESSION = "compression"
}

@Composable
fun StorageScreen(onBack: () -> Unit, section: String = StorageSection.SOURCES, viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val mirror by viewModel.mirror.collectAsStateWithLifecycle()
    val mirrorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setMirror(uri)
    }
    var rebindTarget by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<SourceItem?>(null) }
    var confirmRestore by remember { mutableStateOf<de.hr.mediathek.data.backup.BackupFileInfo?>(null) }
    val rebindPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = rebindTarget
        if (uri != null && target != null) viewModel.rebindSource(target, uri)
        rebindTarget = null
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
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

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.storage_remove_title, target.source.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.storage_remove_text))
                    OutlinedButton(
                        onClick = { viewModel.removeSource(target.source.uuid, false); removeTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.storage_remove_keep)) }
                    OutlinedButton(
                        onClick = { viewModel.removeSource(target.source.uuid, true); removeTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.storage_remove_purge)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.diag_cancel)) } },
        )
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
            TextButton(
                onClick = onBack,
                modifier = Modifier.glass(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text(stringResource(R.string.diag_back)) }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            de.hr.mediathek.core.ui.GlassTitle(
                text = stringResource(
                    when (section) {
                        StorageSection.BACKUP -> R.string.storage_section_backup
                        StorageSection.COMPRESSION -> R.string.storage_section_compression
                        else -> R.string.storage_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        state.message?.let { msg ->
            Card(
                colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.primaryContainer),
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

        if (section == StorageSection.SOURCES) {
        Card(
            colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.secondaryContainer),
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
                        onRebind = {
                            rebindTarget = item.source.uuid
                            rebindPicker.launch(null)
                        },
                        onRemove = { removeTarget = item },
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
        }

        if (section == StorageSection.BACKUP) BackupCard(
            status = backup,
            restore = restore,
            hasWorkFolder = state.workFolderLabel != null,
            onBackup = viewModel::backupNow,
            onToggleList = viewModel::toggleBackupList,
            onPick = { confirmRestore = it },
            onExport = { exportPicker.launch("mediathek-backup.zip") },
            onImport = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
            onDismissMessage = viewModel::dismissRestoreMessage,
        )

        if (section == StorageSection.BACKUP) MirrorCard(
            mirror = mirror,
            lastBackupAt = backup.lastSuccessAt,
            onPick = { mirrorPicker.launch(null) },
            onCopy = viewModel::mirrorNow,
            onRemove = viewModel::removeMirror,
        )

        if (section == StorageSection.COMPRESSION) CompressionCard()

        confirmRestore?.let { file ->
            AlertDialog(
                onDismissRequest = { confirmRestore = null },
                title = { Text(stringResource(R.string.restore_confirm_title)) },
                text = { Text(stringResource(R.string.restore_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRestore = null
                        viewModel.restore(file)
                    }) { Text(stringResource(R.string.restore_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.diag_cancel)) }
                },
            )
        }

        if (section == StorageSection.SOURCES) {
        Card(
            colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.primaryContainer),
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
        }

        if (section == StorageSection.BACKUP) {
        Card(
            colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.tertiaryContainer),
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
}

private fun StorageMessage.textRes(): Int = when (this) {
    StorageMessage.SOURCE_ADDED -> R.string.storage_msg_source_added
    StorageMessage.SOURCE_EXISTS -> R.string.storage_msg_source_exists
    StorageMessage.SOURCE_FAILED -> R.string.storage_msg_source_failed
    StorageMessage.WORK_FOLDER_SET -> R.string.storage_msg_work_set
    StorageMessage.WORK_FOLDER_REJECTED -> R.string.storage_msg_work_rejected
    StorageMessage.WORK_FOLDER_FAILED -> R.string.storage_msg_work_failed
    StorageMessage.SCAN_ALREADY_ACTIVE -> R.string.storage_msg_scan_active
    StorageMessage.SOURCE_REMOVED -> R.string.storage_msg_removed
    StorageMessage.SOURCE_REMOVE_BUSY -> R.string.storage_msg_remove_busy
    StorageMessage.SOURCE_REMOVE_FAILED -> R.string.storage_msg_remove_failed
}

@Composable
private fun SourceBlock(
    item: SourceItem,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (ConfirmInfo) -> Unit,
    onDismiss: (ConfirmInfo) -> Unit,
    onRebind: () -> Unit,
    onRemove: () -> Unit,
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
                if (item.reachable == false) {
                    OutlinedButton(onClick = onRebind) { Text(stringResource(R.string.storage_rebind)) }
                }
                Button(onClick = onScan, enabled = item.reachable == true && !busy) {
                    Text(stringResource(R.string.storage_scan_now))
                }
                if (job != null && (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING)) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.storage_scan_cancel)) }
                }
            }
            TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.storage_remove)) }
        }
    }
}

@Composable
private fun BackupCard(
    status: de.hr.mediathek.data.backup.BackupStatus,
    restore: RestoreUiState,
    hasWorkFolder: Boolean,
    onBackup: () -> Unit,
    onToggleList: () -> Unit,
    onPick: (de.hr.mediathek.data.backup.BackupFileInfo) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    val job = status.latestJob
    val running = job != null && (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING)
    val ageDays = status.lastSuccessAt?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() }
    Card(
        colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium, color = onColor)
            Text(
                when {
                    ageDays == null -> stringResource(R.string.backup_never)
                    ageDays == 0 -> stringResource(R.string.backup_today)
                    else -> stringResource(R.string.backup_days_ago, ageDays)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onColor,
            )
            if (ageDays != null && ageDays > 3) {
                Text(stringResource(R.string.backup_old_warning), color = MaterialTheme.colorScheme.error)
            }
            status.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!hasWorkFolder) {
                Text(stringResource(R.string.backup_needs_work_folder), style = MaterialTheme.typography.bodySmall, color = onColor)
            }
            if (job != null && job.status == JobStatus.RUNNING) {
                Text(job.phase ?: "", style = MaterialTheme.typography.bodySmall, color = onColor)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (job != null && job.status == JobStatus.QUEUED) {
                Text(stringResource(R.string.storage_scan_queued), style = MaterialTheme.typography.bodySmall, color = onColor)
            } else if (job != null && job.status == JobStatus.FAILED) {
                Text(stringResource(R.string.backup_failed, job.lastError ?: "-"), color = MaterialTheme.colorScheme.error)
            } else if (job != null && job.status == JobStatus.COMPLETED) {
                Text(job.phase ?: "", style = MaterialTheme.typography.bodySmall, color = onColor)
            }
            Button(onClick = onBackup, enabled = hasWorkFolder && !running) { Text(stringResource(R.string.backup_now)) }

            restore.message?.let { msg ->
                Text(
                    when (msg) {
                        is RestoreMessage.Imported -> stringResource(
                            R.string.restore_done,
                            msg.result.inserted, msg.result.updated, msg.result.kept, msg.result.conflicts,
                        ) + if (msg.result.olderThanTombstoneRetention) "\n" + stringResource(R.string.restore_old_backup) else ""
                        is RestoreMessage.Exported -> stringResource(R.string.export_done, msg.records)
                        is RestoreMessage.Failed -> stringResource(R.string.restore_failed, msg.reason)
                    },
                    color = if (msg is RestoreMessage.Failed) MaterialTheme.colorScheme.error else onColor,
                )
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.storage_message_ok)) }
            }
            if (restore.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleList, enabled = hasWorkFolder && !restore.busy) {
                    Text(stringResource(R.string.restore_show))
                }
                OutlinedButton(onClick = onExport, enabled = !restore.busy) { Text(stringResource(R.string.export_button)) }
                OutlinedButton(onClick = onImport, enabled = !restore.busy) { Text(stringResource(R.string.import_button)) }
            }
            restore.backups?.let { list ->
                if (list.isEmpty()) {
                    Text(stringResource(R.string.restore_none), style = MaterialTheme.typography.bodySmall, color = onColor)
                }
                list.forEach { file ->
                    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(file.lastModified))
                    OutlinedButton(onClick = { onPick(file) }, enabled = !restore.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("$date · ${(file.size / 1024).coerceAtLeast(1)} KB")
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorCard(
    mirror: MirrorUiState,
    lastBackupAt: Long?,
    onPick: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
) {
    val onColor = MaterialTheme.colorScheme.onTertiaryContainer
    val now = System.currentTimeMillis()
    fun ago(t: Long?): String {
        if (t == null) return stringResource(R.string.mirror_never)
        val d = ((now - t) / 86_400_000L).toInt()
        return if (d == 0) stringResource(R.string.mirror_today) else stringResource(R.string.mirror_days_ago, d)
    }
    val job = mirror.job
    val running = job != null && (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING)
    val stale = mirror.configured &&
        (mirror.lastSuccessAt == null || now - mirror.lastSuccessAt > de.hr.mediathek.data.backup.MirrorService.STALE_DAYS * 86_400_000L)
    val offer = mirror.configured && mirror.reachable == true && !running &&
        (mirror.lastSuccessAt == null || (lastBackupAt != null && lastBackupAt > mirror.lastSuccessAt))
    Card(
        colors = de.hr.mediathek.core.ui.glassCardColors(MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mirror_title), style = MaterialTheme.typography.titleMedium, color = onColor)
            // Sicherheits-Übersicht
            Text(stringResource(R.string.mirror_dash_main, ago(lastBackupAt)), color = onColor)
            Text(stringResource(R.string.mirror_dash_mirror, ago(mirror.lastSuccessAt)), color = onColor)
            if (lastBackupAt == null || now - lastBackupAt > 3 * 86_400_000L) {
                Text(stringResource(R.string.backup_old_warning), color = MaterialTheme.colorScheme.error)
            }
            if (!mirror.configured) {
                Text(stringResource(R.string.mirror_hint), style = MaterialTheme.typography.bodySmall, color = onColor)
                Button(onClick = onPick) { Text(stringResource(R.string.mirror_choose)) }
            } else {
                Text(
                    stringResource(R.string.mirror_card, mirror.label ?: "-") + " · " +
                        stringResource(if (mirror.reachable == true) R.string.mirror_connected else R.string.mirror_not_connected),
                    color = onColor,
                )
                if (stale) Text(stringResource(R.string.mirror_stale_warning), color = MaterialTheme.colorScheme.error)
                if (offer) Text(stringResource(R.string.mirror_offer), color = onColor)
                if (job != null && job.status == JobStatus.RUNNING) {
                    Text(job.phase ?: "", style = MaterialTheme.typography.bodySmall, color = onColor)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (job != null && job.status == JobStatus.FAILED) {
                    Text(stringResource(R.string.backup_failed, job.lastError ?: "-"), color = MaterialTheme.colorScheme.error)
                } else if (job != null && job.status == JobStatus.COMPLETED) {
                    Text(job.phase ?: "", style = MaterialTheme.typography.bodySmall, color = onColor)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onCopy, enabled = mirror.reachable == true && !running) {
                        Text(stringResource(R.string.mirror_copy_now))
                    }
                    OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.mirror_remove)) }
                }
            }
        }
    }
}
