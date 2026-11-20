package de.hr.mediathek.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Deckende Fläche in der Farbe [tint] mit abgerundeten Ecken (früher Milchglas). Der Name bleibt, damit alle
 * bestehenden Bildschirme sofort die neue, immer lesbare Optik bekommen.
 */
fun Modifier.glass(tint: Color, shape: Shape = RoundedCornerShape(28.dp)): Modifier =
    this.clip(shape).background(tint.copy(alpha = 1f), shape)

/** Kartenfarben: deckend, damit Karten auf jedem Hintergrundbild lesbar sind. */
@Composable
fun glassCardColors(tint: Color): CardColors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 1f))

/** Überschrift als Pille auf deckender Fläche. */
@Composable
fun GlassTitle(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onTint: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(
        text = text,
        style = style,
        color = onTint,
        modifier = modifier.glass(tint, CircleShape).padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
