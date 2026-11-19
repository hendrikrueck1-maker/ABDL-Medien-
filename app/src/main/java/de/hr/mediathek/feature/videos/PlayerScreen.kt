package de.hr.mediathek.feature.videos

import android.view.ViewGroup
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
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val s = state) {
        PlayerState.Loading -> CenterText(stringResource(R.string.player_loading), onBack)
        PlayerState.Offline -> CenterText(stringResource(R.string.player_offline), onBack)
        PlayerState.NotFound -> CenterText(stringResource(R.string.player_not_found), onBack)
        is PlayerState.Ready -> ReadyPlayer(s, viewModel, onBack)
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
private fun ReadyPlayer(state: PlayerState.Ready, viewModel: PlayerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }
    var speedIndex by remember { mutableIntStateOf(0) }

    val player = remember(state.uri) {
        ExoPlayer.Builder(context)
            // Nur Plattform-Decoder; Pause beim Abziehen der Kopfhörer.
            .setHandleAudioBecomingNoisy(true)
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
            player.release()
        }
    }

    // Position regelmäßig sichern (nicht bei jedem Bild), damit nach einem Absturz nichts verloren geht.
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) viewModel.saveProgress(player.currentPosition, player.duration)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
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
                    }
                },
                update = { it.player = player },
            )
            error?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back), color = Color.White) }
            OutlinedButton(onClick = {
                speedIndex = (speedIndex + 1) % SPEEDS.size
                player.setPlaybackSpeed(SPEEDS[speedIndex])
            }) { Text("${SPEEDS[speedIndex]}x") }
            IconButton(onClick = viewModel::toggleFavorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.video_favorite),
                    tint = if (state.favorite) MaterialTheme.colorScheme.primary else Color.Gray,
                )
            }
            (1..5).forEach { star ->
                IconButton(onClick = { viewModel.setRating(star) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.video_rate, star),
                        tint = if (star <= state.rating) Color(0xFFFFC107) else Color.Gray,
                    )
                }
            }
        }
    }
}
