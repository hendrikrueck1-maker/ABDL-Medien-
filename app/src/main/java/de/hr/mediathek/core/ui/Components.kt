package de.hr.mediathek.core.ui

import androidx.compose.foundation.BorderStroke
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

/** Standardkarte: deckend, Radius 30, weicher Schatten. [bar] = die dunklere Variante für Kopfkarten und Leisten. */
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
        modifier = modifier.shadow(12.dp, shape, ambientColor = c.shadow, spotColor = c.shadow),
        shape = shape,
        color = if (bar) c.bar else c.card,
        contentColor = c.onCard,
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
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
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(2.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = LocalAppColors.current.bar, contentColor = color),
        content = content,
    )
}
