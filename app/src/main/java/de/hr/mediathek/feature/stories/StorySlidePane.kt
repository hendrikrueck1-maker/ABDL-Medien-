package de.hr.mediathek.feature.stories

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Zufällige Diashow neben dem Text: wechselt alle [seconds] Sekunden mit weichem Überblenden.
 * Tippen hält an oder setzt fort, Wischen blättert vor und zurück.
 */
@Composable
fun StorySlidePane(
    image: Bitmap?,
    seconds: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (image == null) onNext() }
    LaunchedEffect(paused, seconds) {
        if (!paused) while (true) {
            delay(seconds.coerceAtLeast(2) * 1000L)
            onNext()
        }
    }
    Box(
        modifier
            .background(Color(0xFF0E0E10))
            .pointerInput(Unit) { detectTapGestures(onTap = { paused = !paused }) }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 80f) onPrevious() else if (total < -80f) onNext() },
                ) { _, delta -> total += delta }
            },
    ) {
        Crossfade(targetState = image, animationSpec = tween(1500), label = "slide", modifier = Modifier.fillMaxSize()) { bmp ->
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (image == null) {
            Text("Lade Bild …", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
        }
        if (paused) {
            Text(
                "⏸ Pause",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
