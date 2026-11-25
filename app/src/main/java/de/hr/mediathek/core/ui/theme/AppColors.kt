package de.hr.mediathek.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Farb-Tokens „Nacht-Pastell“. Ein festes dunkles Design. Die dunklen Flächen sind Glas: auf dunklen Hintergrundbildern
 * zu 60 % durchsichtig, auf hellen Bildern automatisch dichter (siehe [withGlass]), damit Schrift immer lesbar bleibt.
 */
data class AppColors(
    val bgFullscreen: Color = Color(0xFF07060D),
    /** Dunkle Flächen: Grundwert 40 % deckend (Glasoptik); [withGlass] passt die Deckkraft an das Bild an, [solid] ist deckend für Menüs und Dialoge. */
    val bar: Color = Color(0xFF1E1A33).copy(alpha = 0.4f),
    val card: Color = Color(0xFF2A2442).copy(alpha = 0.4f),
    val cardInset: Color = Color(0xFF3A3360).copy(alpha = 0.4f),
    val muted: Color = Color(0xFF4A4270),
    val onCard: Color = Color(0xFFF6F0FF),
    val onCardVariant: Color = Color(0xFFC9BEDF),
    val onAccent: Color = Color(0xFF1A1530),
    val rosa: Color = Color(0xFFFF9CC8),
    val hellblau: Color = Color(0xFF8ED6FF),
    val mint: Color = Color(0xFF7EEBC4),
    val gold: Color = Color(0xFFFFE08A),
    val lavendel: Color = Color(0xFFC9B2FF),
    val warnung: Color = Color(0xFFFFA3A3),
    val shadow: Color = Color(0xFF140E28),
) {
    /** Dieselben Farben, aber deckend. */
    fun solid() = copy(bar = bar.copy(alpha = 1f), card = card.copy(alpha = 1f), cardInset = cardInset.copy(alpha = 1f))

    /** Dieselben Farben mit der Deckkraft [alpha] für alle dunklen Flächen. */
    fun withGlass(alpha: Float) = copy(bar = bar.copy(alpha = alpha), card = card.copy(alpha = alpha), cardInset = cardInset.copy(alpha = alpha))
}

/**
 * Anpassung des Glases an die Helligkeit des Hintergrundbilds. [brightness] ist die relative Leuchtdichte (0–1) der
 * hellsten 5 % des Bildes. Gerechnet für weißen, grauen und schwarzen Hintergrund: jede Schriftfarbe bleibt ≥ 4,5 : 1.
 */
object GlassLevel {
    const val MIN_ALPHA = 0.4f
    const val MAX_ALPHA = 0.85f
    const val MAX_VEIL = 0.35f
    /** Heller Schimmer oben links auf Karten (klein, sonst sinkt der Kontrast). */
    const val GLOSS = 0.04f

    /** 0 = dunkles Bild (volles Glas), 1 = helles Bild (dichteste Stufe). */
    fun level(brightness: Float): Float = ((brightness - 0.04f) / 0.3f).coerceIn(0f, 1f)
    fun cardAlpha(level: Float): Float = MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * level
    fun veilAlpha(level: Float): Float = MAX_VEIL * level
}

/** Rotierende Tönungen für eigene Kategorien: Fläche und Text. */
val CategoryChipColors: List<Pair<Color, Color>> = listOf(
    Color(0xFF3B2142) to Color(0xFFFFC4DE),
    Color(0xFF15343F) to Color(0xFFC2E9FF),
    Color(0xFF163A30) to Color(0xFFBDF5DF),
    Color(0xFF3A3018) to Color(0xFFEFE3C4),
)

/** Farben der Leseseite im Geschichten-Leser: Fläche und Text. */
object ReaderPage {
    val night = Color(0xFF1E1A33) to Color(0xFFEDE6F7)
    val sepia = Color(0xFFF4E8D3) to Color(0xFF3B2E1E)
    val light = Color(0xFFFFFFFF) to Color(0xFF241B33)
}

val LocalAppColors = staticCompositionLocalOf { AppColors() }
