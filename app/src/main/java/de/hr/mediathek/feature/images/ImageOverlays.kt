package de.hr.mediathek.feature.images

import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.theme.LocalAppColors

/** Deckende Leiste für Vollbild-Overlays (Bilder, Diashow): Text steht nie direkt auf dem Bild. */
@Composable
internal fun OverlayPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(28.dp)).background(LocalAppColors.current.bar).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun RoundIconButton(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.card).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint ?: c.onCard) }
}

/** Fünf Sterne; zweiter Tipp auf denselben Stern nimmt die Bewertung zurück (macht der Aufrufer). */
@Composable
internal fun StarRow(rating: Int, onRate: (Int) -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { star ->
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .selectable(selected = star == rating, role = Role.RadioButton, onClick = { onRate(star) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (star <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    stringResource(R.string.video_rate, star),
                    tint = if (star <= rating) c.gold else c.onCardVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Favorit: aktiv gefülltes rosa Herz mit Wort, inaktiv nur Umriss. */
@Composable
internal fun FavoritePill(active: Boolean, onToggle: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.height(48.dp).clip(CircleShape)
            .then(if (active) Modifier.background(c.rosa) else Modifier.border(2.dp, c.rosa, CircleShape))
            .toggleable(value = active, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (active) c.onAccent else c.rosa
        Icon(if (active) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = tint)
        Text(stringResource(R.string.mark_fav_long), style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

/** Gefüllte Pille in Modulfarbe mit Wort. */
@Composable
internal fun AccentPill(label: String, color: Color, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.height(48.dp).clip(CircleShape).background(color).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = c.onAccent)
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Nur Umriss und Wort, nie gefüllt: zum Löschen markieren. */
@Composable
internal fun DeleteMarkToggle(marked: Boolean, onToggle: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.height(48.dp).clip(CircleShape).border(2.dp, c.warnung, CircleShape)
            .toggleable(value = marked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Delete, null, tint = c.warnung)
        Text(
            stringResource(if (marked) R.string.player_marked_delete else R.string.player_mark_delete),
            style = MaterialTheme.typography.labelLarge, color = c.warnung, maxLines = 1,
        )
    }
}
