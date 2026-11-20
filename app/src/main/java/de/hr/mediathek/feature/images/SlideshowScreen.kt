package de.hr.mediathek.feature.images

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import android.view.accessibility.AccessibilityManager
import de.hr.mediathek.core.ui.AppBackgroundViewModel
import de.hr.mediathek.core.ui.theme.LocalAppColors
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
    val c = LocalAppColors.current
    val media by viewModel.current.collectAsStateWithLifecycle(initialValue = null)
    val background: AppBackgroundViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val pinnedUuid by background.pinned.collectAsStateWithLifecycle()

    var controls by remember { mutableStateOf(true) }
    var pokes by remember { mutableIntStateOf(0) }
    val poke = { pokes++; controls = true }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failures by remember { mutableStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }

    // Bedienleiste nach 3 s ausblenden (nicht mit Screenreader).
    val touchExploration = remember(activity) {
        activity?.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    LaunchedEffect(controls, pokes) {
        if (controls && !touchExploration) {
            delay(3000)
            controls = false
        }
    }

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
            .background(c.bgFullscreen)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (controls) controls = false else poke() }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragX = 0f },
                    onDragEnd = {
                        if (dragX < -SWIPE_PX) { viewModel.next(); poke() } else if (dragX > SWIPE_PX) { viewModel.previous(); poke() }
                    },
                ) { _, delta -> dragX += delta }
            },
    ) {
        Crossfade(
            targetState = bitmap,
            animationSpec = tween(if (state.crossfade) 700 else 0),
            label = "slide",
        ) { current ->
            if (current != null) {
                Image(current.asImageBitmap(), contentDescription = media?.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                // Oben links: zurück, Titel, Position.
                OverlayPill(Modifier.align(Alignment.TopStart).widthIn(max = 420.dp)) {
                    RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.diag_back)) { onClose() }
                    Column(Modifier.padding(end = 12.dp)) {
                        val folder = media?.relativePath?.substringBeforeLast('/', "")?.substringAfterLast('/').orEmpty()
                        Text(
                            stringResource(R.string.img_slideshow_label, folder.ifEmpty { media?.displayName.orEmpty() }),
                            style = MaterialTheme.typography.titleSmall, color = c.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.img_position, state.position, state.total) + " · " +
                                stringResource(if (state.random) R.string.img_random_on else R.string.img_random_off),
                            style = MaterialTheme.typography.bodySmall, color = c.onCardVariant, maxLines = 1,
                        )
                    }
                }
                // Oben rechts: Bewertung, Favorit, Hintergrund.
                media?.let { m ->
                    OverlayPill(Modifier.align(Alignment.TopEnd)) {
                        StarRow(m.rating) { viewModel.rate(m, it); poke() }
                        FavoritePill(m.favorite) { viewModel.toggleFavorite(m); poke() }
                        val isBg = pinnedUuid == m.uuid
                        AccentPill(
                            stringResource(if (isBg) R.string.img_is_background else R.string.img_as_background),
                            c.lavendel,
                        ) { background.pin(m.uuid); poke() }
                    }
                }
                // Unten Mitte: Steuerung.
                OverlayPill(Modifier.align(Alignment.BottomCenter)) {
                    RoundIconButton(Icons.Filled.SkipPrevious, stringResource(R.string.img_prev)) { viewModel.previous(); poke() }
                    Box(
                        Modifier.size(width = 96.dp, height = 56.dp).clip(CircleShape).background(c.hellblau)
                            .clickable(
                                onClickLabel = stringResource(if (state.paused) R.string.slideshow_resume else R.string.slideshow_pause),
                                role = Role.Button,
                            ) { viewModel.togglePause(); poke() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            stringResource(if (state.paused) R.string.slideshow_resume else R.string.slideshow_pause),
                            tint = c.onAccent, modifier = Modifier.size(32.dp),
                        )
                    }
                    RoundIconButton(Icons.Filled.SkipNext, stringResource(R.string.img_next)) { viewModel.next(); poke() }
                    Spacer(Modifier.width(8.dp))
                    // Zufall (mint, aktiv gefüllt) und Intervall.
                    Row(
                        Modifier.height(48.dp).clip(CircleShape)
                            .then(if (state.random) Modifier.background(c.mint) else Modifier.border(2.dp, c.mint, CircleShape))
                            .toggleable(value = state.random, role = Role.Switch, onValueChange = { viewModel.setRandom(it); poke() })
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val tint = if (state.random) c.onAccent else c.mint
                        Icon(Icons.Filled.Shuffle, null, tint = tint)
                        Text(stringResource(R.string.img_random_short), style = MaterialTheme.typography.labelLarge, color = tint)
                    }
                    Box(
                        Modifier.height(48.dp).clip(CircleShape).background(c.card)
                            .clickable(onClickLabel = stringResource(R.string.img_interval), role = Role.Button) {
                                val next = INTERVALS.getOrNull(INTERVALS.indexOf(state.intervalSec) + 1) ?: INTERVALS.first()
                                viewModel.setInterval(next); poke()
                            }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.slideshow_seconds, state.intervalSec), style = MaterialTheme.typography.labelLarge, color = c.onCard) }
                    media?.let { m ->
                        Spacer(Modifier.width(8.dp))
                        DeleteMarkToggle(m.markedDelete) { viewModel.toggleMarkedDelete(m); poke() }
                    }
                }
            }
        }
    }
}

private const val MAX_PX = 2560
private const val MAX_FAILURES_IN_ROW = 10
private const val SWIPE_PX = 120f
