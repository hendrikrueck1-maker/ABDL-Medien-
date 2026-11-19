package de.hr.mediathek.feature.videos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.SeenState
import de.hr.mediathek.data.db.SourceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PlayerState {
    data object Loading : PlayerState
    data object Offline : PlayerState
    data object NotFound : PlayerState
    data class Ready(
        val media: MediaFileEntity,
        val uri: Uri,
        val startPositionMs: Long,
        val rating: Int,
        val favorite: Boolean,
    ) : PlayerState
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
) : ViewModel() {

    private val uuid: String = checkNotNull(savedStateHandle["uuid"])
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Loading)
    val state: StateFlow<PlayerState> = _state

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val media = mediaDao.getByUuid(uuid)
        if (media == null) {
            _state.value = PlayerState.NotFound
            return
        }
        val tree = sourceDao.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) }
        val docId = media.documentIdCache
        if (tree == null || docId == null) {
            _state.value = PlayerState.Offline
            return
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        // Beim Öffnen wird die Existenz geprüft; fehlt die Datei, wird der Eintrag offline.
        val exists = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.query(
                    uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
                )?.use { it.moveToFirst() } ?: false
            } catch (e: Exception) {
                false
            }
        }
        if (!exists) {
            mediaDao.markOffline(uuid, System.currentTimeMillis())
            _state.value = PlayerState.Offline
            return
        }
        // Bereits gesehene Videos starten von vorn.
        val start = if (media.seenState == SeenState.SEEN) 0L else media.resumePositionMs
        _state.value = PlayerState.Ready(media, uri, start, media.rating, media.favorite)
    }

    /** Speichert Position und Gesehen-Status; ab 90 % Wiedergabe gilt das Video als gesehen. */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs < 0) return
        val seen = when {
            positionMs >= durationMs * SEEN_THRESHOLD -> SeenState.SEEN
            positionMs > 0 -> SeenState.IN_PROGRESS
            else -> SeenState.UNSEEN
        }
        val stored = if (seen == SeenState.SEEN) 0L else positionMs
        // Läuft auch nach dem Verlassen des Bildschirms zu Ende, damit die letzte Position nicht verloren geht.
        viewModelScope.launch(NonCancellable) { mediaDao.setProgress(uuid, stored, seen, System.currentTimeMillis()) }
    }

    fun setRating(rating: Int) {
        val current = _state.value as? PlayerState.Ready ?: return
        // Zweiter Tipp auf denselben Stern nimmt die Bewertung zurück.
        val newRating = if (current.rating == rating) 0 else rating
        _state.value = current.copy(rating = newRating)
        viewModelScope.launch { mediaDao.setRating(uuid, newRating, System.currentTimeMillis()) }
    }

    fun toggleFavorite() {
        val current = _state.value as? PlayerState.Ready ?: return
        _state.value = current.copy(favorite = !current.favorite)
        viewModelScope.launch { mediaDao.setFavorite(uuid, !current.favorite, System.currentTimeMillis()) }
    }

    private companion object {
        const val SEEN_THRESHOLD = 0.9
    }
}
