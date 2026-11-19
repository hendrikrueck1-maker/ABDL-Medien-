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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SourceDao
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
) : ViewModel() {
    private val _image = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = _image

    init {
        viewModelScope.launch {
            val uuid = dao.randomImageUuid() ?: return@launch
            val media = dao.getByUuid(uuid) ?: return@launch
            val docId = media.documentIdCache ?: return@launch
            val tree = sources.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return@launch
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val info = loader.info(uri) ?: return@launch
            _image.value = loader.decodeSampled(uri, info, 1280)
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)))
        }
        content()
    }
}
