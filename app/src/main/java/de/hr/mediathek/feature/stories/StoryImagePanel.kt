package de.hr.mediathek.feature.stories

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.hr.mediathek.R
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.feature.images.AccentPill
import de.hr.mediathek.feature.images.RoundIconButton
import kotlinx.coroutines.delay

private val SLIDE_INTERVALS = listOf(3, 5, 8, 10, 20, 30, 60)

/**
 * Bildbereich neben (Tablet, Querformat) oder über (Handy) dem Text: Kopf mit Ordnerwahl und Vollbild,
 * Bild auf dunkler Fläche, darunter Zurück/Pause/Weiter, Zufall und Intervall. Wischen blättert ebenfalls.
 */
@Composable
internal fun StoryImagePanel(
    image: ReaderImage,
    folders: List<String>,
    seconds: Int,
    random: Boolean,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFolder: (String) -> Unit,
    onRandom: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
) {
    val c = LocalAppColors.current
    var paused by remember { mutableStateOf(false) }
    var chooseFolder by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onStart() }
    LaunchedEffect(paused, seconds, image.uuid, image.total) {
        if (!paused && image.uuid != null && image.total > 1) {
            delay(seconds.coerceAtLeast(2) * 1000L)
            onNext()
        }
    }
    val folderLabel = image.folder.ifEmpty { stringResource(R.string.reader_all_folders) }

    Column(
        modifier.clip(RoundedCornerShape(32.dp)).background(c.bar).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.reader_images_title), style = MaterialTheme.typography.titleSmall, color = c.onCard, maxLines = 1)
                Text(
                    stringResource(R.string.reader_images_sub, folderLabel, image.position, image.total),
                    style = MaterialTheme.typography.bodySmall, color = c.onCardVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.height(48.dp).clip(CircleShape).background(c.card)
                    .clickable(onClickLabel = stringResource(R.string.reader_folder_choose), role = Role.Button) { chooseFolder = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.reader_folder_choose), style = MaterialTheme.typography.labelLarge, color = c.onCard, maxLines = 1)
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.onCard)
            }
            RoundIconButton(Icons.Filled.Fullscreen, stringResource(R.string.reader_fullscreen)) { onFullscreen() }
            if (onCollapse != null) RoundIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.reader_collapse_images)) { onCollapse() }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.bgFullscreen)
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total > 80f) onPrevious() else if (total < -80f) onNext() },
                    ) { _, delta -> total += delta }
                },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = image.bitmap, animationSpec = tween(700), label = "reader-image", modifier = Modifier.fillMaxSize()) { bmp ->
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            if (image.bitmap == null) {
                Text(
                    if (image.unavailable) "Bilder gerade nicht erreichbar (Speicherkarte?)"
                    else stringResource(if (image.loading) R.string.reader_loading_image else R.string.reader_no_images),
                    color = c.onCardVariant, modifier = Modifier.padding(16.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundIconButton(Icons.Filled.SkipPrevious, stringResource(R.string.img_prev)) { onPrevious() }
            Box(
                Modifier.size(width = 80.dp, height = 48.dp).clip(CircleShape).background(c.hellblau)
                    .clickable(onClickLabel = stringResource(if (paused) R.string.slideshow_resume else R.string.slideshow_pause), role = Role.Button) { paused = !paused },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    stringResource(if (paused) R.string.slideshow_resume else R.string.slideshow_pause),
                    tint = c.onAccent,
                )
            }
            RoundIconButton(Icons.Filled.SkipNext, stringResource(R.string.img_next)) { onNext() }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(48.dp).clip(CircleShape)
                    .then(if (random) Modifier.background(c.mint) else Modifier.border(2.dp, c.mint, CircleShape))
                    .toggleable(value = random, role = Role.Switch, onValueChange = onRandom)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint = if (random) c.onAccent else c.mint
                Icon(Icons.Filled.Shuffle, null, tint = tint)
                Text(stringResource(R.string.img_random_short), style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
            }
            Box(
                Modifier.height(48.dp).clip(CircleShape).background(c.card)
                    .clickable(onClickLabel = stringResource(R.string.img_interval), role = Role.Button) {
                        onInterval(SLIDE_INTERVALS.firstOrNull { it > seconds } ?: SLIDE_INTERVALS.first())
                    }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.slideshow_seconds, seconds), style = MaterialTheme.typography.labelLarge, color = c.onCard) }
        }
    }

    if (chooseFolder) {
        AlertDialog(
            onDismissRequest = { chooseFolder = false },
            containerColor = c.bar.copy(alpha = 1f),
            shape = RoundedCornerShape(30.dp),
            confirmButton = { TextButton(onClick = { chooseFolder = false }) { Text(stringResource(R.string.diag_back)) } },
            title = { Text(stringResource(R.string.reader_folder_choose)) },
            text = {
                LazyColumn {
                    items(listOf("") + folders) { name ->
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { chooseFolder = false; onFolder(name) }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name.ifEmpty { stringResource(R.string.reader_all_folders) },
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (name == image.folder) Icon(Icons.Filled.Check, null, tint = c.mint)
                        }
                    }
                }
            },
        )
    }
}
