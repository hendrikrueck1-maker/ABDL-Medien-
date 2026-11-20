package de.hr.mediathek.feature.images

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.ImmersiveMode
import kotlinx.coroutines.delay

private val INTERVALS = listOf(3, 5, 10, 20, 30, 60)

@Composable
fun SlideshowScreen(onBack: () -> Unit, viewModel: SlideshowViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Aus der Bildansicht gestartet: nach "Beenden" zurück zum Bild statt in die Einrichtung.
    val close: () -> Unit = { if (viewModel.fromViewer) onBack() else viewModel.stop() }
    if (viewModel.fromViewer && !state.loading && state.total == 0) {
        LaunchedEffect(Unit) { onBack() }
    }
    if (state.started) {
        Playing(state, viewModel, onClose = close)
    } else if (viewModel.fromViewer) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        Setup(state, viewModel, onBack)
    }
}

@Composable
private fun Setup(state: SlideshowState, viewModel: SlideshowViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
            Text(stringResource(R.string.slideshow_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (state.loading) {
            Text(stringResource(R.string.player_loading))
            return@Column
        }
        if (state.total == 0) {
            Text(stringResource(R.string.slideshow_empty))
            return@Column
        }
        Text(stringResource(R.string.slideshow_count, state.total))
        Text(stringResource(R.string.slideshow_interval), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            INTERVALS.forEach { s ->
                FilterChip(
                    selected = state.intervalSec == s,
                    onClick = { viewModel.setInterval(s) },
                    label = { Text(stringResource(R.string.slideshow_seconds, s)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.slideshow_crossfade), modifier = Modifier.weight(1f))
            Switch(checked = state.crossfade, onCheckedChange = viewModel::setCrossfade)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.slideshow_random), modifier = Modifier.weight(1f))
            Switch(checked = state.random, onCheckedChange = viewModel::setRandom)
        }
        Text(stringResource(R.string.slideshow_filter_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = viewModel::start) { Text(stringResource(R.string.slideshow_start)) }
    }
}

@Composable
private fun Playing(state: SlideshowState, viewModel: SlideshowViewModel, onClose: () -> Unit) {
    // Diashow immer im Vollbild; der Bildschirm bleibt nur währenddessen an.
    ImmersiveMode(true)
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(onBack = onClose)

    var controls by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failures by remember { mutableStateOf(0) }

    // Bild laden; nicht lesbare Bilder werden übersprungen (bei lauter Fehlern endet die Diashow).
    LaunchedEffect(state.tick) {
        val uuid = state.currentUuid ?: return@LaunchedEffect
        val uri = viewModel.uriOf(uuid)
        val info = uri?.let { viewModel.loader.info(it) }
        val loaded = if (uri != null && info != null) viewModel.loader.decodeSampled(uri, info, MAX_PX) else null
        if (loaded == null) {
            failures++
            if (failures > MAX_FAILURES_IN_ROW) onClose() else viewModel.next()
        } else {
            failures = 0
            bitmap = loaded
        }
    }
    // Zeitgeber: nach Ablauf des Intervalls (und nur wenn ein Bild da ist und nicht pausiert) zum nächsten Bild.
    LaunchedEffect(state.tick, state.paused, state.intervalSec, bitmap) {
        if (state.paused || bitmap == null) return@LaunchedEffect
        delay(state.intervalSec * 1000L)
        viewModel.next()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        Crossfade(
            targetState = bitmap,
            animationSpec = tween(if (state.crossfade) 700 else 0),
            label = "slide",
        ) { current ->
            if (current != null) {
                Image(current.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (controls || state.paused) {
            // Dunkle, fast deckende Leiste mit hellen Knöpfen: auf jedem Bild gut lesbar.
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                Button(onClick = viewModel::previous, colors = colors) { Text("◀", style = MaterialTheme.typography.titleMedium) }
                Button(onClick = viewModel::togglePause, colors = colors) {
                    Text(
                        stringResource(if (state.paused) R.string.slideshow_resume else R.string.slideshow_pause),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Button(onClick = viewModel::next, colors = colors) { Text("▶", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6FA8), contentColor = Color.Black),
                ) { Text(stringResource(R.string.slideshow_end), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

private const val MAX_PX = 2560
private const val MAX_FAILURES_IN_ROW = 10
