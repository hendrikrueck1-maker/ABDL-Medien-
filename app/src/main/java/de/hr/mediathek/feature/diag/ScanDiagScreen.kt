package de.hr.mediathek.feature.diag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R

@Composable
fun ScanDiagScreen(onBack: () -> Unit, viewModel: ScanDiagViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.start(uri)
    }

    DiagFrame(
        title = stringResource(R.string.diag_scan_title),
        state = state,
        onBack = onBack,
    ) {
        Text(stringResource(R.string.diag_scan_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(null) }, enabled = !state.running) {
                Text(stringResource(R.string.diag_pick_folder))
            }
            if (state.running) {
                OutlinedButton(onClick = viewModel::cancel) {
                    Text(stringResource(R.string.diag_cancel))
                }
            }
        }
    }
}
