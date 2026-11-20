package de.hr.mediathek.core.ui

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import de.hr.mediathek.data.images.ImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Wählt beim Start der App ein zufälliges Bild aus der Bildersammlung als Hintergrund. */
@HiltViewModel
class AppBackgroundViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val sources: SourceDao,
    private val loader: ImageLoader,
    private val settings: SettingDao,
) : ViewModel() {
    private val _image = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = _image

    private val _stats = MutableStateFlow<ImageStats?>(null)

    /** Reglerstand 0..1 und Automatik für die Lesbarkeit (Einstellungen). */
    val level: StateFlow<Float> = settings.observe(KEY_LEVEL).map { it?.toFloatOrNull() ?: Readability.DEFAULT_LEVEL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Readability.DEFAULT_LEVEL)
    val auto: StateFlow<Boolean> = settings.observe(KEY_AUTO).map { it != "0" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val stats: StateFlow<ImageStats?> = _stats

    fun setLevel(v: Float) { viewModelScope.launch { settings.set(SettingEntity(KEY_LEVEL, v.toString())) } }
    fun setAuto(v: Boolean) { viewModelScope.launch { settings.set(SettingEntity(KEY_AUTO, if (v) "1" else "0")) } }

    private fun measure(bmp: Bitmap): ImageStats {
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        var sum = 0.0
        var sq = 0.0
        val n = 24 * 24
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = small.getPixel(x, y)
            val l = (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
            sum += l; sq += l * l
        }
        val mean = sum / n
        val std = kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
        return ImageStats(mean.toFloat(), std.toFloat())
    }

    init {
        viewModelScope.launch {
            val uuid = dao.randomImageUuid() ?: return@launch
            val media = dao.getByUuid(uuid) ?: return@launch
            val docId = media.documentIdCache ?: return@launch
            val tree = sources.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return@launch
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val info = loader.info(uri) ?: return@launch
            val bmp = loader.decodeSampled(uri, info, 1280)
            if (bmp != null) _stats.value = measure(bmp)
            _image.value = bmp
        }
    }
}

/**
 * Hintergrund hinter der ganzen App: das Bild, darüber ein Schleier in der Hintergrundfarbe, damit Texte lesbar bleiben.
 * Ohne Bild (noch keine Bilder eingelesen) bleibt der normale Hintergrund.
 */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val viewModel: AppBackgroundViewModel = hiltViewModel()
    val image by viewModel.image.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val auto by viewModel.auto.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val cfg = remember(level, auto, stats, dark) { Readability.compute(level, auto, stats, dark) }
    CompositionLocalProvider(LocalReadability provides cfg) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(6.dp))
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = cfg.veilAlpha)))
        }
        content()
    }
}
}

private const val KEY_LEVEL = "ui_readability"
private const val KEY_AUTO = "ui_readability_auto"
