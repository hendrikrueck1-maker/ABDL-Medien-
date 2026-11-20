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
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
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

private class Bubble(val x: Float, val radiusDp: Float, val speed: Int, val phase: Float)

private val BUBBLES = listOf(
    Bubble(0.08f, 26f, 1, 0.05f),
    Bubble(0.22f, 14f, 2, 0.40f),
    Bubble(0.35f, 34f, 1, 0.72f),
    Bubble(0.50f, 10f, 3, 0.15f),
    Bubble(0.63f, 22f, 2, 0.85f),
    Bubble(0.75f, 40f, 1, 0.30f),
    Bubble(0.86f, 16f, 2, 0.58f),
    Bubble(0.94f, 28f, 1, 0.92f),
    Bubble(0.15f, 18f, 3, 0.66f),
)

/** Zarte Seifenblasen, die langsam nach oben steigen. Liegen hinter dem Inhalt und stören keine Eingaben. */
@Composable
fun FloatingBubbles(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bubbles")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 90_000, easing = LinearEasing)),
        label = "bubbleTime",
    )
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary).map { it.copy(alpha = 0.12f) }
    Canvas(modifier.fillMaxSize()) {
        BUBBLES.forEachIndexed { i, b ->
            val progress = (t * b.speed + b.phase) % 1f
            val r = b.radiusDp.dp.toPx()
            val sway = 0.03f * sin((progress * 2.0 * PI * 2.0).toFloat() + b.phase * 6f)
            val center = Offset(size.width * (b.x + sway), size.height * (1.15f - progress * 1.3f))
            drawCircle(colors[i % colors.size], radius = r, center = center)
            drawCircle(Color.White.copy(alpha = 0.30f), radius = r * 0.22f, center = Offset(center.x - r * 0.35f, center.y - r * 0.35f))
        }
    }
}
