package de.hr.mediathek.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Glasoptik: farbig getönte, durchsichtige Milchglas-Fläche mit hellem Rand, der oben links glänzt.
 * Sie liegt hinter Schrift, damit sie auf dem Hintergrundbild gut lesbar bleibt.
 */
fun Modifier.glass(tint: Color, shape: Shape = RoundedCornerShape(24.dp)): Modifier = composed {
    val a = LocalReadability.current.cardAlpha
    this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = (a + 0.08f).coerceAtMost(1f)), tint.copy(alpha = (a - 0.08f).coerceAtLeast(0f)))), shape)
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.12f))), shape)
}

/** Kartenfarben im selben Stil, damit Karten auf dem Hintergrundbild lesbar sind. */
@Composable
fun glassCardColors(tint: Color): CardColors = CardDefaults.cardColors(containerColor = tint.copy(alpha = (LocalReadability.current.cardAlpha - 0.10f).coerceAtLeast(0f)))

/** Überschrift als farbiges Glas-Etikett. */
@Composable
fun GlassTitle(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primaryContainer,
    onTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(
        text = text,
        style = style,
        color = onTint,
        modifier = modifier.glass(tint, CircleShape).padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
