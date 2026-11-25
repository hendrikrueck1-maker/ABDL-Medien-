package de.hr.mediathek.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.hr.mediathek.core.ui.theme.LocalAppColors

/** Standardkarte: Glas (Deckkraft passt sich dem Hintergrundbild an), Radius 30. [bar] = die dunklere Variante für Kopfkarten und Leisten. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    bar: Boolean = false,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(30.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (bar) c.bar else c.card,
        contentColor = c.onCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
    ) {
        // Glanz: leichter Schimmer von links oben (klein gehalten, sonst leidet die Lesbarkeit), dazu der feine helle Rand.
        Column(
            Modifier.background(Brush.linearGradient(listOf(Color.White.copy(alpha = de.hr.mediathek.core.ui.theme.GlassLevel.GLOSS), Color.Transparent))).padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Akzent-Button: Pille in Modulfarbe, Text in `onAccent`. */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAppColors.current.rosa,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "accent-bounce")
    Button(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.defaultMinSize(minHeight = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = LocalAppColors.current.onAccent),
        content = content,
    )
}

/** Umriss-Button: Pille auf `bar`, 2 dp Umriss und Text in Akzentfarbe. */
@Composable
fun OutlineAccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAppColors.current.rosa,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "outline-bounce")
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.defaultMinSize(minHeight = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(2.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = LocalAppColors.current.bar.copy(alpha = 1f), contentColor = color),
        content = content,
    )
}
