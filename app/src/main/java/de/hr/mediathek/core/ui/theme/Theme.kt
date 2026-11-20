package de.hr.mediathek.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hr.mediathek.R

private val NightScheme = AppColors().solid().let { c ->
    darkColorScheme(
        primary = c.rosa, onPrimary = c.onAccent,
        primaryContainer = Color(0xFF3B2142), onPrimaryContainer = Color(0xFFFFC4DE),
        secondary = c.hellblau, onSecondary = c.onAccent,
        secondaryContainer = Color(0xFF15343F), onSecondaryContainer = Color(0xFFC2E9FF),
        tertiary = c.mint, onTertiary = c.onAccent,
        tertiaryContainer = Color(0xFF163A30), onTertiaryContainer = Color(0xFFBDF5DF),
        error = c.warnung, onError = c.onAccent,
        errorContainer = Color(0xFF4A2030), onErrorContainer = Color(0xFFFFD6D6),
        background = c.bar, onBackground = c.onCard,
        surface = c.card, onSurface = c.onCard,
        surfaceVariant = c.cardInset, onSurfaceVariant = c.onCardVariant,
        surfaceTint = c.rosa,
        inverseSurface = c.onCard, inverseOnSurface = c.onAccent, inversePrimary = Color(0xFF4B2FA0),
        surfaceContainerLowest = c.bgFullscreen, surfaceContainerLow = c.bar, surfaceContainer = c.card,
        surfaceContainerHigh = c.bar, surfaceContainerHighest = c.cardInset,
        outline = Color(0xFF9A90BE), outlineVariant = c.cardInset,
        scrim = Color(0xFF000000),
    )
}

@OptIn(ExperimentalTextApi::class)
private val Quicksand = FontFamily(
    Font(R.font.quicksand, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
@OptIn(ExperimentalTextApi::class)
private val Nunito = FontFamily(
    Font(R.font.nunito, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.nunito, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** Literata für lange Texte im Leser (Gewicht 400). */
@OptIn(ExperimentalTextApi::class)
val LiterataFamily = FontFamily(
    Font(R.font.literata, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
)

private fun heading(size: Int, line: Int) =
    TextStyle(fontFamily = Quicksand, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp)

private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = Nunito, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val AppTypography = Typography(
    displayLarge = heading(48, 56), displayMedium = heading(40, 48), displaySmall = heading(34, 42),
    headlineLarge = heading(32, 40), headlineMedium = heading(28, 36), headlineSmall = heading(24, 32),
    titleLarge = heading(22, 28), titleMedium = heading(18, 24), titleSmall = body(16, 22, FontWeight.ExtraBold),
    bodyLarge = body(16, 24), bodyMedium = body(15, 22), bodySmall = body(13, 18),
    labelLarge = body(15, 20, FontWeight.ExtraBold), labelMedium = body(13, 18, FontWeight.Bold), labelSmall = body(12, 16, FontWeight.Bold),
)

private val PlayfulShapes = Shapes(
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Zusatzfarben für die Kacheln der Bereiche (Flieder und Pfirsich neben Rosé, Blau, Mint). */
data class TileColors(val container: Color, val content: Color)

object TilePalette {
    val lavenderLight = TileColors(Color(0xFFE6DDFB), Color(0xFF3B2D63))
    val peachLight = TileColors(Color(0xFFFFE3CF), Color(0xFF5E3417))
    val lavenderDark = TileColors(Color(0xFF4B3F6B), Color(0xFFE6DDFB))
    val peachDark = TileColors(Color(0xFF6B4A33), Color(0xFFFFE3CF))
}

/** Immer „Nacht-Pastell“: ein festes dunkles Design, unabhängig von der Systemeinstellung. */
@Composable
fun MediathekTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides AppColors()) {
        MaterialTheme(colorScheme = NightScheme, typography = AppTypography, shapes = PlayfulShapes, content = content)
    }
}
