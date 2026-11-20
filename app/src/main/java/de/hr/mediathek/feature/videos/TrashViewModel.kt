package de.hr.mediathek.feature.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.TrashRow
import de.hr.mediathek.data.trash.TrashRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trash: TrashRepository,
    mediaDao: MediaFileDao,
    private val thumbs: de.hr.mediathek.data.thumb.ThumbnailCache,
    private val playback: PlaybackContext,
) : ViewModel() {
    val items: StateFlow<List<TrashRow>> = trash.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Duplikat-Gruppen: je Fingerabdruck die Einträge, die sich gleichen. */
    val duplicateGroups: StateFlow<List<List<MediaFileEntity>>> = mediaDao.observeDuplicates()
        .map { rows -> rows.groupBy { it.fingerprint }.values.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun restore(item: TrashRow) {
        viewModelScope.launch {
            _message.value = if (trash.restore(item)) null else "Wiederherstellen nicht möglich. Ist die Karte eingelegt?"
        }
    }

    fun deleteForever(item: TrashRow) {
        viewModelScope.launch {
            _message.value = if (trash.deleteForever(item)) null else "Endgültiges Löschen nicht möglich. Ist die Karte eingelegt?"
        }
    }

    fun deleteAll() {
        val current = items.value
        viewModelScope.launch {
            val failed = current.count { !trash.deleteForever(it) }
            _message.value = if (failed > 0) "$failed Dateien konnten nicht gelöscht werden." else null
        }
    }

    fun trashDuplicate(uuid: String) {
        viewModelScope.launch {
            val r = trash.trash(listOf(uuid))
            _message.value = if (r.failed > 0) "Konnte nicht in den Papierkorb verschoben werden." else null
        }
    }

    /** Vorschaubild eines Videos, falls schon erzeugt. */
    fun thumbnailOf(uuid: String): java.io.File? =
        thumbs.fileIfPresent(uuid, de.hr.mediathek.data.db.ThumbVariant.NORMAL)
            ?: thumbs.fileIfPresent(uuid, de.hr.mediathek.data.db.ThumbVariant.SMALL)

    /** Aus den Duplikaten geöffnete Videos laufen einzeln, ohne "Nächstes" aus einer früheren Liste. */
    fun prepareOpen() {
        playback.filter = null
    }

    fun dismissMessage() {
        _message.value = null
    }
}
