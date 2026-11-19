package de.hr.mediathek.feature.diag

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.hr.mediathek.R
import java.io.File
import java.util.Locale

object DiagRoutes {
    const val SCAN = "diag/scan"
    const val TRANSCODE = "diag/transcode"
    const val PDF = "diag/pdf"
}

data class DiagUiState(
    val running: Boolean = false,
    val progress: String = "",
    val result: String = "",
    val outputs: List<File> = emptyList(),
)

fun deviceInfoLine(): String =
    "Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

fun formatMb(bytes: Long): String = String.format(Locale.GERMANY, "%.1f MB", bytes / 1_048_576.0)

fun formatMbit(bitsPerSecond: Long): String =
    String.format(Locale.GERMANY, "%.1f Mbit/s", bitsPerSecond / 1_000_000.0)

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.GERMANY, "%d:%02d min", totalSeconds / 60, totalSeconds % 60)
}

/** Gemeinsamer Rahmen der Diagnose-Bildschirme: Zurück, Steuerung, Fortschritt, kopierbares Ergebnis. */
@Composable
fun DiagFrame(
    title: String,
    state: DiagUiState,
    onBack: () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        controls()
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress, style = MaterialTheme.typography.bodySmall)
        }
        if (state.result.isNotEmpty()) {
            Button(onClick = { clipboard.setText(AnnotatedString(state.result)) }) {
                Text(stringResource(R.string.diag_copy))
            }
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    text = state.result,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
