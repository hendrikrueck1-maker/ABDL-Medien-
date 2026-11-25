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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Glasfläche in der Farbe [tint] mit abgerundeten Ecken. Die Deckkraft folgt dem Hintergrundbild
 * (dunkles Bild: 40 %, helles Bild: bis 85 %), siehe [de.hr.mediathek.core.ui.theme.GlassLevel].
 */
fun Modifier.glass(tint: Color, shape: Shape = RoundedCornerShape(28.dp)): Modifier = composed {
    val alpha = de.hr.mediathek.core.ui.theme.LocalAppColors.current.card.alpha
    this.clip(shape).background(tint.copy(alpha = alpha), shape)
}

/** Kartenfarben: Glas mit der an das Hintergrundbild angepassten Deckkraft. */
@Composable
fun glassCardColors(tint: Color): CardColors =
    CardDefaults.cardColors(containerColor = tint.copy(alpha = de.hr.mediathek.core.ui.theme.LocalAppColors.current.card.alpha))

/** Überschrift als Pille auf einer Glasfläche. */
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
