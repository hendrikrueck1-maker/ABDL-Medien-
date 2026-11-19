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

// Pastell-Palette: Rosé, Hellblau, Mint, Flieder, Pfirsich.
private val LightColors = lightColorScheme(
    primary = Color(0xFFE8458B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFBCD9),
    onPrimaryContainer = Color(0xFF5A1F38),
    secondary = Color(0xFF3D8BFD),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8DAFF),
    onSecondaryContainer = Color(0xFF123B5E),
    tertiary = Color(0xFF22B77F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB0F0D2),
    onTertiaryContainer = Color(0xFF0F4630),
    background = Color(0xFFFFF7FA),
    onBackground = Color(0xFF3A2E34),
    surface = Color(0xFFFFF7FA),
    onSurface = Color(0xFF3A2E34),
    surfaceVariant = Color(0xFFF7E6EE),
    onSurfaceVariant = Color(0xFF6B5560),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F5),
    surfaceContainer = Color(0xFFFFE9F1),
    surfaceContainerHigh = Color(0xFFFFE2ED),
    surfaceContainerHighest = Color(0xFFFFDBE9),
    outline = Color(0xFFB59AA6),
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
    background = Color(0xFF1E1A24),
    onBackground = Color(0xFFEDE3EC),
    surface = Color(0xFF1E1A24),
    onSurface = Color(0xFFEDE3EC),
    surfaceVariant = Color(0xFF3D3446),
    onSurfaceVariant = Color(0xFFD0C2CE),
    surfaceContainerLowest = Color(0xFF181420),
    surfaceContainerLow = Color(0xFF252030),
    surfaceContainer = Color(0xFF2B2536),
    surfaceContainerHigh = Color(0xFF352E41),
    surfaceContainerHighest = Color(0xFF40384D),
    outline = Color(0xFF9A8C9A),
)

private val PlayfulShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(36.dp),
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
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = PlayfulShapes,
        content = content,
    )
}
