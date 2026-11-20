package de.hr.mediathek.feature.images

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import android.view.accessibility.AccessibilityManager
import de.hr.mediathek.core.ui.AppBackgroundViewModel
import de.hr.mediathek.core.ui.theme.LocalAppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.ImmersiveMode
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.images.ImageInfo
import de.hr.mediathek.data.images.ImageLoader
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

@Composable
fun ImageViewerScreen(onBack: () -> Unit, onSlideshow: (String) -> Unit = {}, viewModel: ImageViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Bilder immer im Vollbild; ein Tipp blendet die Leiste ein und aus.
    ImmersiveMode(true)
    when (val s = state) {
        ViewerState.Loading -> Centered(stringResource(R.string.player_loading), onBack)
        ViewerState.NotFound -> Centered(stringResource(R.string.image_not_found), onBack)
        is ViewerState.Ready -> Pages(s, viewModel, onBack, onSlideshow)
    }
}

@Composable
private fun Centered(text: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White)
        TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
    }
}

@Composable
private fun Pages(state: ViewerState.Ready, viewModel: ImageViewerViewModel, onBack: () -> Unit, onSlideshow: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = state.startIndex) { state.uuids.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var pokes by remember { mutableIntStateOf(0) }
    val poke = { pokes++; chrome = true }
    val background: AppBackgroundViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val pinnedUuid by background.pinned.collectAsStateWithLifecycle()
    // Leiste nach 3 s ausblenden (nicht mit Screenreader).
    val ctx = LocalContext.current
    val touchExploration = remember { ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    LaunchedEffect(chrome, pokes, pager.currentPage) {
        if (chrome && !touchExploration) {
            delay(3000)
            chrome = false
        }
    }
    Box(Modifier.fillMaxSize().background(LocalAppColors.current.bgFullscreen)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { state.uuids[it] },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val uuid = state.uuids[page]
            val media by remember(uuid) { viewModel.observe(uuid) }.collectAsStateWithLifecycle(initialValue = null)
            val current = pager.currentPage == page
            ImagePage(
                media = media,
                viewModel = viewModel,
                onTap = { if (chrome) chrome = false else poke() },
                onZoomed = { if (current) zoomed = it },
            )
        }
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            val uuid = state.uuids.getOrNull(pager.currentPage)
            val media by remember(uuid) { uuid?.let { viewModel.observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
                .collectAsStateWithLifecycle(initialValue = null)
            Overlays(media, pager.currentPage + 1, state.uuids.size, viewModel, onBack, background, pinnedUuid, poke) {
                uuid?.let(onSlideshow)
            }
        }
    }
}

@Composable
private fun Overlays(
    media: MediaFileEntity?,
    index: Int,
    total: Int,
    viewModel: ImageViewerViewModel,
    onBack: () -> Unit,
    background: AppBackgroundViewModel,
    pinnedUuid: String?,
    poke: () -> Unit,
    onSlideshow: () -> Unit,
) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        OverlayPill(Modifier.align(Alignment.TopStart).widthIn(max = 420.dp)) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.diag_back)) { onBack() }
            Column(Modifier.padding(end = 12.dp)) {
                Text(
                    stringResource(R.string.img_viewer_label, media?.displayName.orEmpty()),
                    style = MaterialTheme.typography.titleSmall, color = c.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.img_position, index, total), style = MaterialTheme.typography.bodySmall, color = c.onCardVariant, maxLines = 1)
            }
        }
        if (media != null) {
            OverlayPill(Modifier.align(Alignment.TopEnd)) {
                StarRow(media.rating) { viewModel.rate(media, it); poke() }
                FavoritePill(media.favorite) { viewModel.toggleFavorite(media); poke() }
                AccentPill(
                    stringResource(if (pinnedUuid == media.uuid) R.string.img_is_background else R.string.img_as_background),
                    c.lavendel,
                ) { background.pin(media.uuid); poke() }
            }
            OverlayPill(Modifier.align(Alignment.BottomCenter)) {
                // Startet die Diashow ab diesem Bild mit den zuletzt gewählten Einstellungen.
                AccentPill(stringResource(R.string.slideshow_from_here), c.hellblau, Icons.Filled.PlayArrow) { onSlideshow() }
                DeleteMarkToggle(media.markedDelete) { viewModel.toggleMarkedDelete(media); poke() }
            }
        }
    }
}

@Composable
private fun ImagePage(media: MediaFileEntity?, viewModel: ImageViewerViewModel, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    if (media == null) return
    if (media.status != MediaStatus.ONLINE) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.image_offline), color = Color.White, modifier = Modifier.padding(24.dp))
        }
        return
    }
    val uri by produceState<Uri?>(null, media.uuid, media.documentIdCache) { value = viewModel.uriOf(media) }
    val u = uri ?: return
    val ext = media.displayName.substringAfterLast('.', "").lowercase()
    val animatedCandidate = ext == "gif" || ext == "webp"
    val animated by produceState<android.graphics.drawable.Drawable?>(null, u) {
        value = if (animatedCandidate) viewModel.loader.animated(u) else null
    }
    val drawable = animated
    if (drawable != null) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = {
                    it.setImageDrawable(drawable)
                    (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        ZoomableImage(u, viewModel.loader, onTap, onZoomed)
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data object Failed : LoadState
    class Ready(val base: Bitmap, val info: ImageInfo) : LoadState
}

private class Detail(val bitmap: Bitmap, val area: Rect)

/**
 * Zoomen und Verschieben. Die Grundansicht ist verkleinert; ab etwa 1,5-facher Vergrößerung wird der sichtbare
 * Ausschnitt zusätzlich in voller Auflösung nachgeladen (Kachel), damit auch sehr große Bilder scharf bleiben.
 */
@Composable
private fun ZoomableImage(uri: Uri, loader: ImageLoader, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val load by produceState<LoadState>(LoadState.Loading, uri) {
        val info = loader.info(uri)
        val base = info?.let { loader.decodeSampled(uri, it, BASE_PX) }
        value = if (info != null && base != null) LoadState.Ready(base, info) else LoadState.Failed
    }
    when (val s = load) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.player_loading), color = Color.White)
        }
        LoadState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.image_error), color = Color.White, modifier = Modifier.padding(24.dp))
        }
        is LoadState.Ready -> ZoomContent(uri, loader, s, onTap, onZoomed)
    }
}

@Composable
private fun ZoomContent(uri: Uri, loader: ImageLoader, ready: LoadState.Ready, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val info = ready.info
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var detail by remember(uri) { mutableStateOf<Detail?>(null) }
    val density = LocalDensity.current

    val regions by produceState<ImageLoader.RegionSession?>(null, uri) { value = loader.openRegions(uri, info) }
    DisposableEffect(regions) { onDispose { regions?.close() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val fit = min(w / info.width, h / info.height)
        val dispW = info.width * fit
        val dispH = info.height * fit
        // Bis zur doppelten Originalgröße auf dem Bildschirm, mindestens 4-fach.
        val maxScale = (2f / fit).coerceIn(4f, 60f)

        fun clamp(o: Offset, s: Float): Offset {
            val mx = max(0f, (dispW * s - w) / 2f)
            val my = max(0f, (dispH * s - h) / 2f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }

        fun zoomAround(c: Offset, newScale: Float, pan: Offset = Offset.Zero) {
            val s = scale
            val center = Offset(w / 2f, h / 2f)
            val ns = newScale.coerceIn(1f, maxScale)
            val moved = (c - center) - (c - center - offset) * (ns / s) + pan
            scale = ns
            offset = if (ns <= 1.001f) Offset.Zero else clamp(moved, ns)
            onZoomed(scale > 1.001f)
        }

        // Kachel nachladen, wenn die Geste zur Ruhe gekommen ist.
        LaunchedEffect(scale, offset, regions) {
            if (scale < 1.5f || regions == null) {
                detail = null
                return@LaunchedEffect
            }
            delay(180)
            val cx = w / 2f
            val cy = h / 2f
            fun toImageX(px: Float) = ((cx + (px - cx - offset.x) / scale) - (w - dispW) / 2f) / fit
            fun toImageY(py: Float) = ((cy + (py - cy - offset.y) / scale) - (h - dispH) / 2f) / fit
            val pad = 0.1f
            val left = (toImageX(0f) - info.width * pad * 0.1f).toInt().coerceIn(0, info.width - 1)
            val top = (toImageY(0f) - info.height * pad * 0.1f).toInt().coerceIn(0, info.height - 1)
            val right = (toImageX(w) + info.width * pad * 0.1f).toInt().coerceIn(left + 1, info.width)
            val bottom = (toImageY(h) + info.height * pad * 0.1f).toInt().coerceIn(top + 1, info.height)
            val area = Rect(left, top, right, bottom)
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { regions?.decode(area, TILE_PX) }
            detail = bitmap?.let { Detail(it, area) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(uri) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val multi = event.changes.size > 1
                            // Mit einem Finger und ohne Zoom bleibt die Geste frei für das Blättern.
                            if (multi || scale > 1.001f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    zoomAround(centroid, scale * zoom, pan)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(uri) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            if (scale > 1.01f) zoomAround(tap, 1f) else zoomAround(tap, min(3f, maxScale))
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                Image(
                    ready.base.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                detail?.let { d ->
                    val x = (w - dispW) / 2f + d.area.left * fit
                    val y = (h - dispH) / 2f + d.area.top * fit
                    Image(
                        d.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset { IntOffset(x.toInt(), y.toInt()) }
                            .size(
                                with(density) { (d.area.width() * fit).toDp() },
                                with(density) { (d.area.height() * fit).toDp() },
                            ),
                    )
                }
            }
        }
    }
}

private const val BASE_PX = 2560
private const val TILE_PX = 2048
