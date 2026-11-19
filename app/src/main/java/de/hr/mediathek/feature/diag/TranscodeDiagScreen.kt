package de.hr.mediathek.feature.diag

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R

@Composable
fun TranscodeDiagScreen(onBack: () -> Unit, viewModel: TranscodeDiagViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val clip by viewModel.clip.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.start(uris)
    }

    DiagFrame(
        title = stringResource(R.string.diag_transcode_title),
        state = state,
        onBack = onBack,
    ) {
        Text(stringResource(R.string.diag_transcode_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(65 to R.string.diag_stage_light, 50 to R.string.diag_stage_balanced, 35 to R.string.diag_stage_strong)
                .forEach { (percent, label) ->
                    FilterChip(
                        selected = stage == percent,
                        onClick = { viewModel.setStage(percent) },
                        enabled = !state.running,
                        label = { Text(stringResource(label)) },
                    )
                }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = clip, onCheckedChange = viewModel::setClip, enabled = !state.running)
            Text(stringResource(R.string.diag_clip_label))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !state.running) {
                Text(stringResource(R.string.diag_pick_videos))
            }
            if (state.running) {
                OutlinedButton(onClick = viewModel::cancel) {
                    Text(stringResource(R.string.diag_cancel))
                }
            }
        }
        if (!state.running && state.outputs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.outputs.forEachIndexed { index, file ->
                    OutlinedButton(onClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(intent)
                    }) {
                        Text(stringResource(R.string.diag_preview, index + 1))
                    }
                }
            }
        }
    }
}
