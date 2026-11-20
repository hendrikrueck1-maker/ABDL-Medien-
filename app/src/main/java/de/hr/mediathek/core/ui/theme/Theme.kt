package de.hr.mediathek.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

// Pastell-Palette: Mint, Rosé, Hellblau/Türkis (Flieder und Pfirsich als Zusatz für Kacheln).
private val LightColors = lightColorScheme(
    primary = Color(0xFFEC6FA3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFC9DE),
    onPrimaryContainer = Color(0xFF5A1F38),
    secondary = Color(0xFF1FB5C9),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBDEFF5),
    onSecondaryContainer = Color(0xFF0C4650),
    tertiary = Color(0xFF3DC9A0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFF3E0),
    onTertiaryContainer = Color(0xFF0F4630),
    background = Color(0xFFF4FDFB),
    onBackground = Color(0xFF2F3A3C),
    surface = Color(0xFFF4FDFB),
    onSurface = Color(0xFF2F3A3C),
    surfaceVariant = Color(0xFFE6F4F2),
    onSurfaceVariant = Color(0xFF4F6165),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0FBF9),
    surfaceContainer = Color(0xFFE8F8F6),
    surfaceContainerHigh = Color(0xFFDFF4F2),
    surfaceContainerHighest = Color(0xFFD6F0EE),
    outline = Color(0xFF8FB0B3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8FBE),
    onPrimary = Color(0xFF4A1830),
    primaryContainer = Color(0xFF99306A),
    onPrimaryContainer = Color(0xFFFFD6E4),
    secondary = Color(0xFF7DBBFF),
    onSecondary = Color(0xFF0E3150),
    secondaryContainer = Color(0xFF2B63A0),
    onSecondaryContainer = Color(0xFFD3EAFF),
    tertiary = Color(0xFF6FE0B0),
    onTertiary = Color(0xFF0B3A27),
    tertiaryContainer = Color(0xFF217553),
    onTertiaryContainer = Color(0xFFCFF3E0),
    background = Color(0xFF152122),
    onBackground = Color(0xFFE9F5F3),
    surface = Color(0xFF152122),
    onSurface = Color(0xFFE9F5F3),
    surfaceVariant = Color(0xFF2A3B3C),
    onSurfaceVariant = Color(0xFFBFD6D4),
    surfaceContainerLowest = Color(0xFF0F1A1B),
    surfaceContainerLow = Color(0xFF1C2A2B),
    surfaceContainer = Color(0xFF223234),
    surfaceContainerHigh = Color(0xFF2B3D3F),
    surfaceContainerHighest = Color(0xFF344749),
    outline = Color(0xFF8FA9AB),
)

private val PlayfulShapes = Shapes(
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(36.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

/** Zusatzfarben für die Kacheln der Bereiche (Flieder und Pfirsich neben Rosé, Blau, Mint). */
data class TileColors(val container: Color, val content: Color)

object TilePalette {
    val lavenderLight = TileColors(Color(0xFFE6DDFB), Color(0xFF3B2D63))
    val peachLight = TileColors(Color(0xFFFFE3CF), Color(0xFF5E3417))
    val lavenderDark = TileColors(Color(0xFF4B3F6B), Color(0xFFE6DDFB))
    val peachDark = TileColors(Color(0xFF6B4A33), Color(0xFFFFE3CF))
}

/** Farbschema folgt dem System (hell/dunkel), immer mit der Pastell-Palette. */
@Composable
fun MediathekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        shapes = PlayfulShapes,
        content = content,
    )
}

