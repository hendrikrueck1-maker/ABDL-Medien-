package de.hr.mediathek.feature.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import de.hr.mediathek.R
import de.hr.mediathek.data.compress.Blocker
import de.hr.mediathek.data.compress.CompressionLevel
import de.hr.mediathek.data.compress.CompressionPlan

/** Vorab-Analyse und Stufenwahl für die Komprimierung eines einzelnen Videos. */
@Composable
fun CompressDialog(
    state: PlanState,
    onStart: (CompressionLevel, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        PlanState.Loading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.compress_title)) },
            text = { Text(stringResource(R.string.compress_loading)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_cancel)) } },
        )
        PlanState.Unavailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.compress_title)) },
            text = { Text(stringResource(R.string.compress_unavailable)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_message_ok)) } },
        )
        is PlanState.Ready -> PlanContent(state.plan, onStart, onDismiss)
    }
}

@Composable
private fun PlanContent(plan: CompressionPlan, onStart: (CompressionLevel, Boolean) -> Unit, onDismiss: () -> Unit) {
    var level by remember { mutableStateOf(CompressionLevel.BALANCED) }
    var confirmed by remember { mutableStateOf(false) }
    val noVideo = Blocker.NO_VIDEO in plan.blockers
    val needsConfirm = plan.blockers.isNotEmpty() || plan.alreadySmall
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.compress_source, size(plan.sourceBytes), duration(plan.durationMs)))
                plan.options.forEach { option ->
                    val saving = 100 - (option.estimatedBytes * 100 / plan.sourceBytes).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = level == option.level, onClick = { level = option.level })
                        Text(
                            stringResource(
                                R.string.compress_option,
                                stringResource(levelName(option.level)), size(option.estimatedBytes), saving,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (level == CompressionLevel.STRONG) Warning(stringResource(R.string.compress_strong_warning))
                if (plan.alreadySmall) Warning(stringResource(R.string.compress_small_warning))
                plan.blockers.forEach { Warning(stringResource(blockerText(it))) }
                if (plan.lowSpace) Warning(stringResource(R.string.compress_low_space))
                if (needsConfirm && !noVideo) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text(stringResource(R.string.compress_confirm), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.compress_info), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(level, confirmed) },
                enabled = !noVideo && (!needsConfirm || confirmed),
            ) { Text(stringResource(R.string.compress_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_cancel)) } },
    )
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun levelName(level: CompressionLevel): Int = when (level) {
    CompressionLevel.BARELY -> R.string.compress_level_barely
    CompressionLevel.BALANCED -> R.string.compress_level_balanced
    CompressionLevel.STRONG -> R.string.compress_level_strong
}

private fun blockerText(b: Blocker): Int = when (b) {
    Blocker.NO_VIDEO -> R.string.compress_blocker_no_video
    Blocker.EXTRA_AUDIO -> R.string.compress_blocker_audio
    Blocker.SUBTITLES -> R.string.compress_blocker_subtitles
    Blocker.HDR_OR_10BIT -> R.string.compress_blocker_hdr
}

internal fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(bytes shr 10)
}

private fun duration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
