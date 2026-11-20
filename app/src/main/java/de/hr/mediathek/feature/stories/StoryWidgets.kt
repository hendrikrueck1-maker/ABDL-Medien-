package de.hr.mediathek.feature.stories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.hr.mediathek.data.db.StoryCategory

fun categoryLabel(c: StoryCategory): String = when (c) {
    StoryCategory.MEN -> "Männer"
    StoryCategory.WOMEN -> "Frauen"
    StoryCategory.MIXED -> "Gemischt"
}

/** Fünf Sterne zum Antippen; erneutes Tippen auf den gesetzten Wert löscht die Bewertung. */
@Composable
fun StarRow(rating: Int, onRate: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            Text(
                if (i <= rating) "★" else "☆",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 48.dp)
                    .clickable { onRate(i) }
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .semantics { contentDescription = "$i Sterne" },
            )
        }
    }
}
