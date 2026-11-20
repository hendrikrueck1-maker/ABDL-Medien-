package de.hr.mediathek.core.ui

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.ui.theme.LocalAppColors
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.images.ImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
        val uuid = dao.randomImageUuid() ?: return
        show(uuid)
    }

    private suspend fun show(uuid: String): Boolean {
        val media = dao.getByUuid(uuid) ?: return false
        val docId = media.documentIdCache ?: return false
        val tree = sources.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val info = loader.info(uri) ?: return false
        val bitmap = loader.decodeSampled(uri, info, 1920) ?: return false
        _image.value = bitmap
        return true
    }

    private companion object {
        /** Beginnt mit "ui_": gehört zu den Geräteeinstellungen und wandert nicht ins Backup. */
        const val K_PIN = "ui_background_uuid"
    }
}

/**
 * Hintergrund hinter der ganzen App: das Bild, scharf und ohne Schleier. Texte stehen nie direkt darauf,
 * sondern immer auf deckenden Flächen (Karten, Leisten). Ohne Bild bleibt eine dunkle Fläche.
 */
@Composable
fun AppBackground(hearts: Boolean = false, content: @Composable () -> Unit) {
    val viewModel: AppBackgroundViewModel = hiltViewModel()
    val image by viewModel.image.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(LocalAppColors.current.bgFullscreen)) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Das Bild immer leicht milchig (nur zur Optik; Texte stehen ohnehin auf deckenden Karten).
        if (image != null) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f)))
        FloatingHearts(hearts)
        content()
    }
}
