package de.hr.mediathek.feature.images

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.Image
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
import androidx.compose.material3.OutlinedButton
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                onTap = { chrome = !chrome },
                onZoomed = { if (current) zoomed = it },
            )
        }
        if (chrome) {
            val uuid = state.uuids.getOrNull(pager.currentPage)
            val media by remember(uuid) { uuid?.let { viewModel.observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
                .collectAsStateWithLifecycle(initialValue = null)
            TopBar(media, pager.currentPage + 1, state.uuids.size, viewModel, onBack) { uuid?.let(onSlideshow) }
        }
    }
}

@Composable
private fun TopBar(media: MediaFileEntity?, index: Int, total: Int, viewModel: ImageViewerViewModel, onBack: () -> Unit, onSlideshow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back), color = Color.White) }
            Text(
                media?.displayName ?: "",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("$index / $total", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        if (media != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (1..5).forEach { star ->
                    TextButton(onClick = { viewModel.rate(media, star) }) {
                        Text(if (star <= media.rating) "★" else "☆", color = Color(0xFFFFC107), style = MaterialTheme.typography.titleLarge)
                    }
                }
                OutlinedButton(onClick = { viewModel.toggleFavorite(media) }) {
                    Text(stringResource(if (media.favorite) R.string.video_fav_unset else R.string.video_fav_set), color = Color.White)
                }
                // Startet die Diashow ab diesem Bild mit den zuletzt gewählten Einstellungen.
                OutlinedButton(onClick = onSlideshow) { Text(stringResource(R.string.slideshow_from_here), color = Color.White) }
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
