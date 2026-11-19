package de.hr.mediathek.feature.diag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R

@Composable
fun PdfDiagScreen(onBack: () -> Unit, viewModel: PdfDiagViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sort by viewModel.sortByPosition.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.start(uri)
    }

    DiagFrame(
        title = stringResource(R.string.diag_pdf_title),
        state = state,
        onBack = onBack,
    ) {
        Text(stringResource(R.string.diag_pdf_hint))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = sort, onCheckedChange = viewModel::setSortByPosition, enabled = !state.running)
            Text(stringResource(R.string.diag_pdf_sort))
        }
        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !state.running) {
            Text(stringResource(R.string.diag_pick_pdf))
        }
    }
}
