package de.hr.mediathek.feature.videos

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.sp
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import de.hr.mediathek.core.ui.PopBox
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import de.hr.mediathek.core.ui.ImmersiveMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import de.hr.mediathek.R
import kotlinx.coroutines.delay

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onNext: (String) -> Unit,
    onSwitchVersion: (uuid: String, original: Boolean) -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Videos laufen immer im Vollbild; die Systemleisten bleiben auch während des Ladens weg, damit nichts springt.
    ImmersiveMode(true)

    when (val s = state) {
        PlayerState.Loading -> CenterText(stringResource(R.string.player_loading), onBack)
        PlayerState.Offline -> CenterText(stringResource(R.string.player_offline), onBack)
        PlayerState.NotFound -> CenterText(stringResource(R.string.player_not_found), onBack)
        PlayerState.OriginalGone -> CenterText(stringResource(R.string.player_original_gone), onBack)
        is PlayerState.Ready -> ReadyPlayer(s, viewModel, onBack, onNext, onSwitchVersion)
    }
}

@Composable
private fun CenterText(text: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReadyPlayer(
    state: PlayerState.Ready,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNext: (String) -> Unit,
    onSwitchVersion: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }
    var speedIndex by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    var confirmDeleteOriginal by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val player = remember(state.uri) {
        ExoPlayer.Builder(context)
            // Nur Plattform-Decoder; Pause beim Abziehen der Kopfhörer.
            .setHandleAudioBecomingNoisy(true)
            // Das Freigeben der Decoder darf den Hauptthread nicht lange blockieren (sonst ruckelt der Wechsel zur Liste).
            .setReleaseTimeoutMs(150)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(state.uri))
                prepare()
                if (state.startPositionMs > 0) seekTo(state.startPositionMs)
                playWhenReady = true
            }
    }

    val errorUnsupported = stringResource(R.string.player_error_unsupported)
    val errorGeneric = stringResource(R.string.player_error_generic)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Am Ende automatisch zum nächsten Video der Liste (Playlist, Filter oder Zufall).
                if (playbackState == Player.STATE_ENDED) {
                    viewModel.saveProgress(player.duration, player.duration)
                    viewModel.next(onNext)
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    -> errorUnsupported
                    else -> errorGeneric
                }
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                viewModel.saveProgress(player.currentPosition, player.duration)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            viewModel.saveProgress(player.currentPosition, player.duration)
            // Sofort anhalten und die Decoder im Hintergrund abbauen (stop), das eigentliche release() erst danach:
            // so blockiert nichts, während die Liste wieder erscheint.
            player.playWhenReady = false
            player.stop()
            Handler(Looper.getMainLooper()).postDelayed({ player.release() }, 1_200)
        }
    }

    // Position regelmäßig sichern (nicht bei jedem Bild), damit nach einem Absturz nichts verloren geht.
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) viewModel.saveProgress(player.currentPosition, player.duration)
        }
    }

    if (showOriginal) {
        AlertDialog(
            onDismissRequest = { showOriginal = false },
            title = { Text(stringResource(R.string.player_original_title)) },
            text = { Text(stringResource(if (state.isOriginal) R.string.player_original_playing else R.string.player_original_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showOriginal = false
                    onSwitchVersion(state.media.uuid, !state.isOriginal)
                }) { Text(stringResource(if (state.isOriginal) R.string.player_play_compressed else R.string.player_play_original)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showOriginal = false; confirmDeleteOriginal = true }) {
                        Text(stringResource(R.string.player_original_delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showOriginal = false }) { Text(stringResource(R.string.diag_cancel)) }
                }
            },
        )
    }
    if (confirmDeleteOriginal) {
        AlertDialog(
            onDismissRequest = { confirmDeleteOriginal = false },
            title = { Text(stringResource(R.string.player_original_delete_title)) },
            text = { Text(stringResource(R.string.player_original_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteOriginal = false
                    viewModel.deleteOriginal { wasOriginal -> if (wasOriginal) onSwitchVersion(state.media.uuid, false) }
                }) { Text(stringResource(R.string.player_original_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteOriginal = false }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    // Die Knopfleiste oben blendet gemeinsam mit der Zeitleiste ein und aus.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE },
                    )
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
        )
        if (state.isOriginal) {
            Text(
                stringResource(R.string.player_original_badge),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        error?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        AnimatedVisibility(
            visible = controlsVisible || error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back), color = Color.White) }
                OutlinedButton(onClick = {
                    speedIndex = (speedIndex + 1) % SPEEDS.size
                    player.setPlaybackSpeed(SPEEDS[speedIndex])
                }) { Text("${SPEEDS[speedIndex]}x", color = Color.White) }
                if (viewModel.hasContext) {
                    OutlinedButton(onClick = { viewModel.next(onNext) }) { Text(stringResource(R.string.player_next), color = Color.White) }
                }
                if (state.hasOriginal || state.isOriginal) {
                    OutlinedButton(onClick = { showOriginal = true }) { Text(stringResource(R.string.player_original_button), color = Color.White) }
                }
            }
        }
        // Markierungen hochkant am linken Rand: Favorit, Tropfen, zum Löschen, darunter die fünf Sterne.
        AnimatedVisibility(
            visible = controlsVisible || error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            MarkRail(state, viewModel)
        }
    }
}

@Composable
private fun MarkRail(state: PlayerState.Ready, viewModel: PlayerViewModel) {
    Column(
        Modifier
            .padding(start = 8.dp)
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(28.dp))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(onClick = viewModel::toggleFavorite, modifier = Modifier.size(48.dp)) {
            PopBox(active = state.favorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.video_favorite),
                    tint = if (state.favorite) Color(0xFFFF6FA8) else Color(0xFF9E9E9E),
                )
            }
        }
        IconButton(onClick = viewModel::toggleMarkedPee, modifier = Modifier.size(48.dp)) {
            PopBox(active = state.markedPee) {
                Text("💧", fontSize = 22.sp, modifier = Modifier.alpha(if (state.markedPee) 1f else 0.35f))
            }
        }
        IconButton(onClick = viewModel::toggleMarkedDelete, modifier = Modifier.size(48.dp)) {
            PopBox(active = state.markedDelete) {
                Text("🗑", fontSize = 22.sp, modifier = Modifier.alpha(if (state.markedDelete) 1f else 0.35f))
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).width(28.dp), color = Color.White.copy(alpha = 0.3f))
        (1..5).forEach { star ->
            IconButton(onClick = { viewModel.setRating(star) }, modifier = Modifier.size(44.dp)) {
                PopBox(active = star <= state.rating) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.video_rate, star),
                        tint = if (star <= state.rating) Color(0xFFFFC107) else Color(0xFF9E9E9E),
                    )
                }
            }
        }
    }
}
