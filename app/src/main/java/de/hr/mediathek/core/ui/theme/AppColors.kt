package de.hr.mediathek.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Farb-Tokens „Nacht-Pastell“. Ein festes dunkles Design; Flächen sind immer deckend. */
data class AppColors(
    val bgFullscreen: Color = Color(0xFF07060D),
    val bar: Color = Color(0xFF1E1A33),
    val card: Color = Color(0xFF2A2442),
    val cardInset: Color = Color(0xFF3A3360),
    val muted: Color = Color(0xFF4A4270),
    val onCard: Color = Color(0xFFF6F0FF),
    val onCardVariant: Color = Color(0xFFC9BEDF),
    val onAccent: Color = Color(0xFF1A1530),
    val rosa: Color = Color(0xFFFF9CC8),
    val hellblau: Color = Color(0xFF8ED6FF),
    val mint: Color = Color(0xFF7EEBC4),
    val gold: Color = Color(0xFFFFE08A),
    val lavendel: Color = Color(0xFFC9B2FF),
    val warnung: Color = Color(0xFFFF8A8A),
    val shadow: Color = Color(0xFF140E28),
)

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
