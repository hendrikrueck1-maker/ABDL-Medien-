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
    /** Das Original gibt es nicht mehr (gelöscht oder nicht auffindbar). */
    data object OriginalGone : PlayerState
    data class Ready(
        val media: MediaFileEntity,
        val uri: Uri,
        val startPositionMs: Long,
        val rating: Int,
        val favorite: Boolean,
        val markedDelete: Boolean = false,
        val markedPee: Boolean = false,
        /** Es wird das Original vor der Komprimierung abgespielt (Position und Gesehen-Status bleiben unberührt). */
        val isOriginal: Boolean = false,
        /** Zu diesem komprimierten Video gibt es noch ein aufbewahrtes Original. */
        val hasOriginal: Boolean = false,
    ) : PlayerState
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
    private val playback: PlaybackContext,
    private val compressionDao: de.hr.mediathek.data.db.CompressionDao,
    private val compression: de.hr.mediathek.data.compress.CompressionRepository,
) : ViewModel() {

    private val uuid: String = checkNotNull(savedStateHandle["uuid"])
    private val playOriginal: Boolean = savedStateHandle.get<Boolean>("original") ?: false
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
        val originalRecord = compressionDao.originalFor(uuid)
        val playDocId = if (playOriginal) originalRecord?.altPath else docId
        if (playDocId == null) {
            _state.value = PlayerState.OriginalGone
            return
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, playDocId)
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
            if (playOriginal) {
                _state.value = PlayerState.OriginalGone
            } else {
                mediaDao.markOffline(uuid, System.currentTimeMillis())
                _state.value = PlayerState.Offline
            }
            return
        }
        // Bereits gesehene Videos starten von vorn; das Original startet immer von vorn.
        val start = if (playOriginal || media.seenState == SeenState.SEEN) 0L else media.resumePositionMs
        _state.value = PlayerState.Ready(
            media, uri, start, media.rating, media.favorite,
            markedDelete = media.markedDelete, markedPee = media.markedPee,
            isOriginal = playOriginal, hasOriginal = originalRecord != null,
        )
        if (!playOriginal) mediaDao.incrementPlayCount(uuid, System.currentTimeMillis())
    }

    /** Löscht das aufbewahrte Original dieses Videos; die komprimierte Version bleibt. */
    fun deleteOriginal(onDone: (wasOriginal: Boolean) -> Unit) {
        val wasOriginal = playOriginal
        viewModelScope.launch {
            val ok = compression.deleteOriginalOf(uuid)
            if (ok) {
                (_state.value as? PlayerState.Ready)?.let { _state.value = it.copy(hasOriginal = false) }
                onDone(wasOriginal)
            }
        }
    }

    /** Speichert Position und Gesehen-Status; ab 90 % Wiedergabe gilt das Video als gesehen. */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (playOriginal || durationMs <= 0 || positionMs < 0) return
        val seen = when {
            positionMs >= durationMs * SEEN_THRESHOLD -> SeenState.SEEN
            positionMs > 0 -> SeenState.IN_PROGRESS
            else -> SeenState.UNSEEN
        }
        val stored = if (seen == SeenState.SEEN) 0L else positionMs
        // Läuft auch nach dem Verlassen des Bildschirms zu Ende, damit die letzte Position nicht verloren geht.
        viewModelScope.launch(NonCancellable) { mediaDao.setProgress(uuid, stored, seen, System.currentTimeMillis()) }
    }

    /** Das Video nach diesem in der Liste, aus der geöffnet wurde (Filter, Sortierung, Playlist). */
    fun next(onFound: (String) -> Unit) {
        val ctx = playback.filter ?: return
        viewModelScope.launch {
            val built = VideoQuery.buildOrderedUuids(ctx, playback.seed)
            val ids = mediaDao.uuids(androidx.sqlite.db.SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            val idx = ids.indexOf(uuid)
            ids.getOrNull(idx + 1)?.let(onFound)
        }
    }

    val hasContext: Boolean get() = playback.filter != null

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

    fun toggleMarkedDelete() {
        val current = _state.value as? PlayerState.Ready ?: return
        _state.value = current.copy(markedDelete = !current.markedDelete)
        viewModelScope.launch { mediaDao.setMarkedDelete(uuid, !current.markedDelete, System.currentTimeMillis()) }
    }

    fun toggleMarkedPee() {
        val current = _state.value as? PlayerState.Ready ?: return
        _state.value = current.copy(markedPee = !current.markedPee)
        viewModelScope.launch { mediaDao.setMarkedPee(uuid, !current.markedPee, System.currentTimeMillis()) }
    }

    private companion object {
        const val SEEN_THRESHOLD = 0.9
    }
}
