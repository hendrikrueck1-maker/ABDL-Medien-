package de.hr.mediathek.feature.videos

import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import de.hr.mediathek.core.ui.theme.LocalAppColors
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

    val c = LocalAppColors.current
    val a11y = remember {
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var rotationLocked by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    val categories by viewModel.categoryList.collectAsStateWithLifecycle()
    val usedCategories by viewModel.usedCategories.collectAsStateWithLifecycle()
    val poke = { touch++ }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            delay(300)
        }
    }
    // Overlays blenden nach 3 s ohne Berührung aus; mit TalkBack bleiben sie stehen.
    LaunchedEffect(touch, controlsVisible, seeking, error) {
        if (controlsVisible && !a11y && !seeking && error == null && !showCategories) {
            delay(3_000)
            controlsVisible = false
        }
    }
    DisposableEffect(rotationLocked) {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.requestedOrientation = if (rotationLocked) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    if (showCategories) {
        CategoryDialog(
            title = stringResource(R.string.video_category_action),
            categories = categories.map { LabelItem(it.uuid, it.name) },
            used = usedCategories,
            onAssign = viewModel::assignCategory,
            onUnassign = viewModel::unassignCategory,
            onCreate = viewModel::createCategory,
            onDelete = viewModel::deleteCategory,
            onDismiss = { showCategories = false; poke() },
        )
    }

    Box(Modifier.fillMaxSize().background(c.bgFullscreen)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setOnClickListener { controlsVisible = !controlsVisible; touch++ }
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
        )
        error?.let {
            Text(
                it,
                color = c.onCard,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).background(c.bar, RoundedCornerShape(24.dp)).padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        AnimatedVisibility(
            visible = controlsVisible || error != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                // Oben links: zurück und Titel.
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth(0.45f).clip(RoundedCornerShape(32.dp)).background(c.bar).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).border(2.dp, c.mint, CircleShape).clickable(onClickLabel = stringResource(R.string.diag_back), onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.diag_back), tint = c.mint) }
                    Column(Modifier.weight(1f)) {
                        Text(state.media.displayName, style = MaterialTheme.typography.titleSmall, color = c.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playerInfo(state), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Oben rechts: Sterne, Favorit, Pee.
                Row(
                    Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(32.dp)).background(c.bar).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(Modifier.selectableGroup()) {
                        (1..5).forEach { star ->
                            Box(
                                Modifier.size(48.dp).selectable(selected = star == state.rating, role = Role.RadioButton, onClick = { viewModel.setRating(star); poke() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(if (star <= state.rating) Icons.Filled.Star else Icons.Outlined.StarBorder, stringResource(R.string.video_rate, star), tint = if (star <= state.rating) c.gold else c.onCardVariant)
                            }
                        }
                    }
                    MarkToggle(stringResource(R.string.mark_fav_long), Icons.Filled.Favorite, state.favorite, c.rosa) { viewModel.toggleFavorite(); poke() }
                    MarkToggle(stringResource(R.string.mark_pee), Icons.Filled.WaterDrop, state.markedPee, c.hellblau) { viewModel.toggleMarkedPee(); poke() }
                }
                // Unten: Zeitleiste und Bedienung.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(c.bar).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(formatPlayerTime(position), style = MaterialTheme.typography.labelMedium, color = c.onCardVariant)
                        Slider(
                            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            onValueChange = { seeking = true; position = (it * duration).toLong() },
                            onValueChangeFinished = { player.seekTo(position); seeking = false; poke() },
                            colors = SliderDefaults.colors(thumbColor = c.onCard, activeTrackColor = c.mint, inactiveTrackColor = c.cardInset),
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatPlayerTime(duration), style = MaterialTheme.typography.labelMedium, color = c.onCardVariant)
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RoundTool(Icons.Filled.Replay10, stringResource(R.string.player_back_10)) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); poke() }
                        Box(
                            Modifier.size(width = 72.dp, height = 56.dp).clip(CircleShape).background(c.mint)
                                .clickable(onClickLabel = stringResource(if (playing) R.string.player_pause else R.string.player_play)) {
                                    if (player.isPlaying) player.pause() else player.play()
                                    poke()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                stringResource(if (playing) R.string.player_pause else R.string.player_play),
                                tint = c.onAccent, modifier = Modifier.size(32.dp),
                            )
                        }
                        RoundTool(Icons.Filled.Forward10, stringResource(R.string.player_forward_10)) { player.seekTo(player.currentPosition + 10_000); poke() }
                        if (viewModel.hasContext) RoundTool(Icons.Filled.SkipNext, stringResource(R.string.player_next)) { viewModel.next(onNext) }
                        Box(
                            Modifier.height(48.dp).clip(CircleShape).background(c.card)
                                .clickable(onClickLabel = stringResource(R.string.player_speed)) {
                                    speedIndex = (speedIndex + 1) % SPEEDS.size
                                    player.setPlaybackSpeed(SPEEDS[speedIndex]); poke()
                                }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("${SPEEDS[speedIndex]}×", style = MaterialTheme.typography.labelLarge, color = c.onCard) }
                        Spacer(Modifier.width(24.dp))
                        // Abgesetzt: zum Löschen markieren (nur Umriss und Wort, nie gefüllt).
                        Row(
                            Modifier.height(48.dp).clip(CircleShape).border(2.dp, c.warnung, CircleShape)
                                .toggleable(value = state.markedDelete, role = Role.Switch, onValueChange = { viewModel.toggleMarkedDelete(); poke() })
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Filled.Delete, null, tint = c.warnung, modifier = Modifier.size(20.dp))
                            Text(
                                stringResource(if (state.markedDelete) R.string.player_marked_delete else R.string.player_mark_delete),
                                style = MaterialTheme.typography.labelLarge, color = c.warnung, maxLines = 1,
                            )
                        }
                        RoundTool(Icons.AutoMirrored.Filled.Label, stringResource(R.string.player_categories)) {
                            viewModel.loadUsedCategories(); showCategories = true
                        }
                        RoundTool(
                            if (rotationLocked) Icons.Filled.ScreenLockRotation else Icons.Filled.ScreenRotation,
                            stringResource(if (rotationLocked) R.string.player_unlock_rotation else R.string.player_lock_rotation),
                        ) { rotationLocked = !rotationLocked; poke() }
                        if (state.hasOriginal || state.isOriginal) {
                            RoundTool(Icons.Filled.History, stringResource(R.string.player_original_button)) { showOriginal = true }
                        }
                    }
                }
            }
        }
    }
}

private fun playerInfo(state: PlayerState.Ready): String {
    val m = state.media
    val folder = m.relativePath.substringBeforeLast('/', "").substringAfterLast('/')
    val format = m.displayName.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() && it.length <= 5 }
    val res = m.height?.takeIf { it > 0 }?.let { "${it}p" }
    return listOfNotNull(folder.takeIf { it.isNotEmpty() }, format, res, if (state.isOriginal) "Original" else null).joinToString(" · ")
}

private fun formatPlayerTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Runder Werkzeug-Knopf (48 dp) auf `card`. */
@Composable
private fun RoundTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.card).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = c.onCard) }
}

/** Schalter als Pille mit Form, Farbe und Wort: aktiv gefüllt, inaktiv nur Umriss. */
@Composable
private fun MarkToggle(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, color: Color, onToggle: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.height(48.dp).clip(CircleShape)
            .then(if (active) Modifier.background(color) else Modifier.border(2.dp, color, CircleShape))
            .toggleable(value = active, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (active) c.onAccent else color, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) c.onAccent else color, maxLines = 1)
    }
}
