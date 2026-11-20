package de.hr.mediathek.feature.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.PopBox
import de.hr.mediathek.data.db.FlagCounts

/** Ein Eintrag im Überlaufmenü der Auswahlleiste. */
class SelectionAction(val label: String, val onClick: () -> Unit)

/**
 * Leiste für die Mehrfachauswahl: Schließen, Anzahl, drei Schalter (Favorit, Tropfen, zum Löschen),
 * Bewertung und ein Menü für alles Weitere. Favorit und Tropfen verhalten sich gleich: sind schon alle gewählten
 * Videos markiert, nimmt ein Tipp die Markierung weg, sonst setzt er sie bei allen.
 */
@Composable
fun SelectionBar(
    count: Int,
    flags: FlagCounts,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onPee: () -> Unit,
    onDeleteMark: () -> Unit,
    onRate: (Int) -> Unit,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
) {
    var rateMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sel_close))
            }
            Text(
                stringResource(R.string.sel_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
                maxLines = 1,
            )
            val favState = markState(flags.fav, count)
            val peeState = markState(flags.pee, count)
            val delState = markState(flags.del, count)
            MarkToggle(favState, stringResource(R.string.sel_favorite), onFavorite) {
                Icon(
                    if (favState == MarkState.ALL) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            MarkToggle(peeState, stringResource(R.string.sel_pee), onPee) { Text("💧", fontSize = 22.sp) }
            MarkToggle(delState, stringResource(R.string.sel_delete_mark), onDeleteMark) { Text("🗑", fontSize = 22.sp) }
            IconButton(onClick = { rateMenu = true }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.sel_rate),
                    tint = androidx.compose.ui.graphics.Color(0xFFFFB300),
                )
                DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                    (5 downTo 0).forEach { r ->
                        DropdownMenuItem(
                            text = { Text(if (r == 0) stringResource(R.string.video_rate_none) else "★".repeat(r)) },
                            onClick = {
                                rateMenu = false
                                onRate(r)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { moreMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sel_more))
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    actions.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.label) },
                            onClick = {
                                moreMenu = false
                                a.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

private enum class MarkState { NONE, SOME, ALL }

private fun markState(marked: Int, total: Int): MarkState = when {
    total > 0 && marked >= total -> MarkState.ALL
    marked > 0 -> MarkState.SOME
    else -> MarkState.NONE
}

@Composable
private fun MarkToggle(state: MarkState, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        PopBox(
            active = state == MarkState.ALL,
            modifier = Modifier.size(28.dp).alpha(
                when (state) {
                    MarkState.ALL -> 1f
                    MarkState.SOME -> 0.7f
                    MarkState.NONE -> 0.4f
                },
            ),
        ) { content() }
    }
}
