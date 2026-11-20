package de.hr.mediathek.feature.images

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.images.ImageLoader
import de.hr.mediathek.feature.videos.VideoQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ViewerState {
    data object Loading : ViewerState
    data object NotFound : ViewerState
    data class Ready(val uuids: List<String>, val startIndex: Int) : ViewerState
}

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dao: MediaFileDao,
    private val sources: SourceDao,
    private val context: ImageContext,
    val loader: ImageLoader,
) : ViewModel() {
    private val startUuid: String = checkNotNull(savedState["uuid"])

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state

    private val trees = HashMap<String, Uri?>()

    init {
        viewModelScope.launch {
            val built = VideoQuery.buildOrderedUuids(context.filter, context.seed, type = "IMAGE")
            val list = dao.uuids(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            _state.value = when {
                startUuid in list -> ViewerState.Ready(list, list.indexOf(startUuid))
                dao.getByUuid(startUuid) != null -> ViewerState.Ready(listOf(startUuid), 0)
                else -> ViewerState.NotFound
            }
        }
    }

    fun observe(uuid: String): Flow<MediaFileEntity?> = dao.observeByUuid(uuid)

    /** Dokument-Adresse des Bildes; null, wenn die Quelle nicht (mehr) bekannt ist. */
    suspend fun uriOf(media: MediaFileEntity): Uri? {
        val docId = media.documentIdCache ?: return null
        val tree = trees.getOrPut(media.sourceUuid) { sources.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } }
            ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }

    fun toggleFavorite(media: MediaFileEntity) {
        viewModelScope.launch { dao.setFavorite(media.uuid, !media.favorite, System.currentTimeMillis()) }
    }

    fun toggleMarkedDelete(media: MediaFileEntity) {
        viewModelScope.launch { dao.setMarkedDelete(media.uuid, !media.markedDelete, System.currentTimeMillis()) }
    }

    /** Zweiter Tipp auf denselben Stern entfernt die Bewertung. */
    fun rate(media: MediaFileEntity, stars: Int) {
        viewModelScope.launch {
            dao.setRating(media.uuid, if (media.rating == stars) 0 else stars, System.currentTimeMillis())
        }
    }
}
