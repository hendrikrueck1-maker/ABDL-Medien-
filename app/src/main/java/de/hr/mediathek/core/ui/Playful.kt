package de.hr.mediathek.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import de.hr.mediathek.core.ui.theme.LocalAppColors
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Antippbar mit kleinem Federn: beim Drücken schrumpft das Element kurz und springt beim Loslassen zurück.
 * Ersetzt clickable/combinedClickable auf Karten und Kacheln.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "bounce",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** Lässt das Symbol aufploppen und ein paar Herzchen und Sterne aufsteigen, sobald [active] wahr wird. */
@Composable
fun PopBox(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "pop",
    )
    val burst = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (active) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
        }
    }
    Box(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (burst.value < 1f) {
            SPARKLES.forEach { (symbol, dx, dy) ->
                Text(
                    symbol,
                    fontSize = 12.sp,
                    modifier = Modifier.graphicsLayer {
                        translationX = dx * burst.value * density
                        translationY = dy * burst.value * density
                        alpha = 1f - burst.value
                    },
                )
            }
        }
    }
}

private val SPARKLES = listOf(
    Triple("\u2665", -18f, -46f),
    Triple("\u2726", 16f, -52f),
    Triple("\u2665", 0f, -64f),
    Triple("\u2726", -26f, -30f),
    Triple("\u2665", 24f, -34f),
)

/** Eine schwebende Figur: Herz oder Stern, Bahn am Rand, Größe in dp, [cycles] Durchläufe pro 56 Sekunden (8 bis 14 s je Weg). */
private class Floater(val heart: Boolean, val x: Float, val sizeDp: Float, val cycles: Int, val phase: Float, val colorIndex: Int)

private val FLOATERS = listOf(
    Floater(true, 0.004f, 24f, 5, 0.05f, 0),
    Floater(false, 0.996f, 20f, 6, 0.40f, 1),
    Floater(true, 0.996f, 26f, 4, 0.72f, 2),
    Floater(false, 0.004f, 22f, 7, 0.15f, 3),
    Floater(true, 0.500f, 20f, 6, 0.85f, 4),
    Floater(false, 0.500f, 24f, 5, 0.30f, 0),
)

private fun heartPath(cx: Float, cy: Float, s: Float): Path = Path().apply {
    moveTo(cx, cy + s * 0.9f)
    cubicTo(cx - s * 1.4f, cy - s * 0.1f, cx - s * 0.7f, cy - s * 1.1f, cx, cy - s * 0.35f)
    cubicTo(cx + s * 0.7f, cy - s * 1.1f, cx + s * 1.4f, cy - s * 0.1f, cx, cy + s * 0.9f)
    close()
}

private fun starPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    for (k in 0 until 10) {
        val rad = if (k % 2 == 0) r else r * 0.45f
        val a = (-PI / 2 + k * PI / 5).toFloat()
        val px = cx + rad * kotlin.math.cos(a)
        val py = cy + rad * sin(a)
        if (k == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

/**
 * Langsam aufsteigende Herzen und Sterne in den Modulfarben, höchstens sechs. Sie liegen hinter allen Karten und
 * sind nur in den freien Randbereichen über dem Hintergrundbild zu sehen, nie über Text oder Bedienelementen.
 * Aus bei [enabled] = false, bei Systemeinstellung "Animationen entfernen" und wenn die App nicht im Vordergrund ist.
 */
@Composable
fun FloatingHearts(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val animationsOn = remember(context, resumed) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    if (!enabled || !resumed || !animationsOn) return

    val transition = rememberInfiniteTransition(label = "hearts")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 56_000, easing = LinearEasing)),
        label = "heartsTime",
    )
    val c = LocalAppColors.current
    val colors = listOf(c.rosa, c.hellblau, c.mint, c.gold, c.lavendel)
    Canvas(modifier.fillMaxSize()) {
        FLOATERS.forEach { f ->
            val progress = (t * f.cycles + f.phase) % 1f
            val s = f.sizeDp.dp.toPx() / 2f
            val sway = 0.012f * sin((progress * 2.0 * PI * 2.0).toFloat() + f.phase * 6f)
            val cx = size.width * (f.x + sway)
            val cy = size.height * (1.08f - progress * 1.16f)
            val color = colors[f.colorIndex % colors.size].copy(alpha = 0.85f)
            drawPath(if (f.heart) heartPath(cx, cy, s) else starPath(cx, cy, s * 1.1f), color)
        }
    }
}
