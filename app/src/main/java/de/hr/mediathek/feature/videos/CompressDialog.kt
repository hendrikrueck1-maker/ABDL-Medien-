package de.hr.mediathek.feature.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
    test: TestState?,
    onRunTest: (CompressionLevel) -> Unit,
    onClearTest: () -> Unit,
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
        is PlanState.Ready -> PlanContent(state.plan, test, onRunTest, onClearTest, onStart, onDismiss)
    }
}

@Composable
private fun PlanContent(
    plan: CompressionPlan,
    test: TestState?,
    onRunTest: (CompressionLevel) -> Unit,
    onClearTest: () -> Unit,
    onStart: (CompressionLevel, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
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
                        RadioButton(selected = level == option.level, onClick = {
                            if (level != option.level) onClearTest()
                            level = option.level
                        })
                        Text(
                            stringResource(
                                R.string.compress_option,
                                stringResource(levelName(option.level)), size(option.estimatedBytes), saving,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                TestPanel(test, onRun = { onRunTest(level) }, level = level)
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

/** Testlauf-Bereich: Knopf, Fortschritt, geschätztes Ergebnis und Vorher/Nachher-Vorschau. */
@Composable
private fun TestPanel(test: TestState?, onRun: () -> Unit, level: CompressionLevel) {
    var preview by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (test) {
            null -> OutlinedButton(onClick = onRun) { Text(stringResource(R.string.compress_test_run)) }
            is TestState.Running -> {
                Text(stringResource(R.string.compress_test_running), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { test.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            is TestState.Failed -> {
                Warning(stringResource(R.string.compress_test_failed))
                test.reason?.let { Text(stringResource(R.string.compress_test_reason, it), style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = onRun) { Text(stringResource(R.string.compress_test_run)) }
            }
            is TestState.Ready -> if (test.level == level) {
                val r = test.result
                val saving = 100 - (r.estimatedTotalBytes * 100 / r.sourceBytes).toInt()
                Text(
                    stringResource(R.string.compress_test_result, size(r.estimatedTotalBytes), size(r.sourceBytes), saving),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { preview = true }) { Text(stringResource(R.string.compress_test_preview)) }
                if (preview) TestPreview(r) { preview = false }
            } else {
                OutlinedButton(onClick = onRun) { Text(stringResource(R.string.compress_test_run)) }
            }
        }
    }
}

/** Spielt denselben Ausschnitt einmal im Original und einmal komprimiert ab; Umschalten behält die Position. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TestPreview(result: de.hr.mediathek.data.compress.TestResult, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val original = remember(result) {
        androidx.media3.common.MediaItem.Builder()
            .setUri(android.net.Uri.parse(result.sourceUri))
            .setClippingConfiguration(
                androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(result.startMs)
                    .setEndPositionMs(result.startMs + result.lengthMs)
                    .build(),
            )
            .build()
    }
    val compressed = remember(result) {
        androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(result.resultPath)))
    }
    var showOriginal by remember { mutableStateOf(true) }
    val player = remember(result) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            setMediaItem(original)
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }

    fun switchTo(originalWanted: Boolean) {
        if (originalWanted == showOriginal) return
        val position = player.currentPosition
        player.setMediaItem(if (originalWanted) original else compressed, position)
        player.prepare()
        player.playWhenReady = true
        showOriginal = originalWanted
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_test_preview)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    update = { it.player = player },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { switchTo(true) }, enabled = !showOriginal) {
                        Text(stringResource(R.string.compress_test_original))
                    }
                    OutlinedButton(onClick = { switchTo(false) }, enabled = showOriginal) {
                        Text(stringResource(R.string.compress_test_compressed))
                    }
                }
                Text(
                    stringResource(if (showOriginal) R.string.compress_test_showing_original else R.string.compress_test_showing_compressed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_message_ok)) } },
    )
}

/**
 * Serie: Stufe wählen, am ersten Video einen Testlauf machen und dann alle einreihen.
 * Der Testlauf ist empfohlen. Wer ihn überspringen will oder wem er fehlschlägt, bestätigt das mit einem Haken;
 * so bleibt "Alle einreihen" nie ohne erkennbaren Grund gesperrt.
 */
@Composable
fun SeriesDialog(
    count: Int,
    test: TestState?,
    onRunTest: (CompressionLevel) -> Unit,
    onClearTest: () -> Unit,
    onStart: (CompressionLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    var level by remember { mutableStateOf(CompressionLevel.BALANCED) }
    var withoutTest by remember { mutableStateOf(false) }
    val testOk = test is TestState.Ready && test.level == level
    val running = test is TestState.Running
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_series_title, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CompressionLevel.entries.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = level == l, onClick = {
                            if (level != l) onClearTest()
                            level = l
                        })
                        Text(stringResource(levelName(l)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (level == CompressionLevel.STRONG) Warning(stringResource(R.string.compress_strong_warning))
                Text(stringResource(R.string.compress_series_info), style = MaterialTheme.typography.bodySmall)
                TestPanel(test, onRun = { onRunTest(level) }, level = level)
                if (testOk) {
                    Text(stringResource(R.string.compress_series_test_ok), style = MaterialTheme.typography.bodyMedium)
                } else if (!running) {
                    Text(stringResource(R.string.compress_series_need_test), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withoutTest, onCheckedChange = { withoutTest = it })
                        Text(stringResource(R.string.compress_series_skip_test), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(level) }, enabled = !running && (testOk || withoutTest)) {
                Text(stringResource(R.string.compress_series_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_cancel)) } },
    )
}
