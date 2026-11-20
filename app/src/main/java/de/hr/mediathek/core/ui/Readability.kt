package de.hr.mediathek.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Wie deckend Flächen hinter Schrift sind (0..1), damit Text auf jedem Hintergrundbild lesbar bleibt. */
data class ReadabilityConfig(val cardAlpha: Float, val panelAlpha: Float, val veilAlpha: Float)

/** Kennzahlen des Hintergrundbilds: mittlere Helligkeit und Unruhe (Standardabweichung), je 0..1. */
data class ImageStats(val luminance: Float, val contrast: Float)

val LocalReadability = compositionLocalOf { ReadabilityConfig(0.80f, 0.45f, 0.25f) }

object Readability {
    const val DEFAULT_LEVEL = 0.5f

    /**
     * Berechnet die Deckkraft aus dem Regler (level 0..1) und, wenn auto, aus dem Bild:
     * helle Bilder im hellen Design und dunkle im dunklen Design sowie unruhige Bilder bekommen mehr Schutz.
     */
    fun compute(level: Float, auto: Boolean, stats: ImageStats?, dark: Boolean): ReadabilityConfig {
        val l = level.coerceIn(0f, 1f)
        var card = 0.55f + 0.40f * l
        var panel = 0.20f + 0.50f * l
        var veil = 0.08f + 0.35f * l
        if (auto && stats != null) {
            val busy = (stats.contrast / 0.30f).coerceIn(0f, 1f)
            val risk = (if (dark) stats.luminance else 1f - stats.luminance).coerceIn(0f, 1f)
            val boost = 0.15f * busy + 0.25f * risk
            panel += boost
            veil += boost * 0.8f
            card += boost * 0.4f
        }
        return ReadabilityConfig(card.coerceAtMost(0.96f), panel.coerceAtMost(0.85f), veil.coerceAtMost(0.75f))
    }
}

/** Dezente Fläche hinter losem Text (Seitenleiste, Hinweise), damit er nie direkt auf dem Bild liegt. */
fun Modifier.readable(shape: Shape = RoundedCornerShape(20.dp)): Modifier = composed {
    val cfg = LocalReadability.current
    val color = MaterialTheme.colorScheme.surface.copy(alpha = cfg.cardAlpha)
    this.clip(shape).background(color, shape).padding(horizontal = 6.dp, vertical = 2.dp)
}
