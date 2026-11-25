package de.hr.mediathek.core.ui

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.ui.theme.GlassLevel
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.images.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Hintergrundbild der App: beim Start ein zufälliges Bild, auf Wunsch ein festes ("Als Hintergrund").
 * Das feste Bild bleibt, bis „Neues Hintergrundbild“ gedrückt wird.
 */
@HiltViewModel
class AppBackgroundViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val sources: SourceDao,
    private val settings: SettingDao,
    private val loader: ImageLoader,
) : ViewModel() {
    private val _image = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = _image

    /** Helligkeit des Bildes (Leuchtdichte der hellsten 5 %, 0–1); steuert die Deckkraft des Glases. */
    private val _brightness = MutableStateFlow(0f)
    val brightness: StateFlow<Float> = _brightness

    /** Das aktuell fixierte Bild; null, wenn der Zufall gilt. */
    private val _pinned = MutableStateFlow<String?>(null)
    val pinned: StateFlow<String?> = _pinned

    init {
        viewModelScope.launch {
            val saved = settings.get(K_PIN)?.takeIf { it.isNotBlank() }
            if (saved != null && show(saved)) {
                _pinned.value = saved
            } else {
                showRandom()
            }
        }
    }

    /** Neues Zufallsbild (beim Start und für „Neues Hintergrundbild“); hebt die Fixierung auf. */
    fun reload() {
        viewModelScope.launch {
            _pinned.value = null
            settings.set(SettingEntity(K_PIN, ""))
            showRandom()
        }
    }

    /** Fixiert dieses Bild als Hintergrund, bis „Neues Hintergrundbild“ gedrückt wird. */
    fun pin(uuid: String) {
        viewModelScope.launch {
            if (show(uuid)) {
                _pinned.value = uuid
                settings.set(SettingEntity(K_PIN, uuid))
            }
        }
    }

    private suspend fun showRandom() {
        // Mehrere Versuche: das gezogene Bild kann auf einer gerade entfernten Karte liegen.
        repeat(3) {
            val uuid = dao.randomImageUuid() ?: return
            if (show(uuid)) return
        }
    }

    private suspend fun show(uuid: String): Boolean {
        val media = dao.getByUuid(uuid) ?: return false
        val docId = media.documentIdCache ?: return false
        val tree = sources.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val info = loader.info(uri) ?: return false
        val bitmap = loader.decodeSampled(uri, info, 1920) ?: return false
        _brightness.value = withContext(Dispatchers.Default) { BackgroundBrightness.of(bitmap) }
        _image.value = bitmap
        return true
    }

    private companion object {
        /** Beginnt mit "ui_": gehört zu den Geräteeinstellungen und wandert nicht ins Backup. */
        const val K_PIN = "ui_background_uuid"
    }
}

/** Misst die Helligkeit eines Bildes: Leuchtdichte der hellsten 5 % (verkleinert auf 48×48). */
object BackgroundBrightness {
    fun of(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val pixels = IntArray(48 * 48)
        small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        if (small !== bitmap) small.recycle()
        return quantile(pixels.map { luminance(it) }, 0.95)
    }

    /** Relative Leuchtdichte nach WCAG für eine ARGB-Farbe. */
    fun luminance(argb: Int): Float {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return (0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)).toFloat()
    }

    fun quantile(values: List<Float>, q: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
    }
}

/**
 * Hintergrund hinter der ganzen App: das Bild, scharf. Damit Schrift auf jedem Bild lesbar bleibt, werden bei hellen
 * Bildern ein dunkler Schleier und dichteres Glas verwendet (siehe [GlassLevel]); dunkle Bilder bleiben unverändert.
 * Ohne Bild bleibt eine dunkle Fläche.
 */
@Composable
fun AppBackground(hearts: Boolean = false, content: @Composable () -> Unit) {
    val viewModel: AppBackgroundViewModel = hiltViewModel()
    val image by viewModel.image.collectAsStateWithLifecycle()
    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
    val level = if (image != null) GlassLevel.level(brightness) else 0f
    val base = LocalAppColors.current
    Box(Modifier.fillMaxSize().background(base.bgFullscreen)) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (image != null && level > 0f) Box(Modifier.fillMaxSize().background(base.bgFullscreen.copy(alpha = GlassLevel.veilAlpha(level))))
        FloatingHearts(hearts)
        CompositionLocalProvider(LocalAppColors provides base.withGlass(GlassLevel.cardAlpha(level))) {
            content()
        }
    }
}
