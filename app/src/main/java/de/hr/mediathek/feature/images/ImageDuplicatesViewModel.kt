package de.hr.mediathek.feature.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.jobs.JobScheduler
import de.hr.mediathek.data.db.HashStats
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.images.HashedItem
import de.hr.mediathek.data.images.SimilarImageGroups
import de.hr.mediathek.data.thumb.ThumbnailCache
import de.hr.mediathek.data.trash.TrashRepository
import de.hr.mediathek.data.trash.TrashResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class DuplicateMode { EXACT, SIMILAR }

class DuplicateGroup(val images: List<MediaFileEntity>)

data class DuplicateState(
    val mode: DuplicateMode = DuplicateMode.EXACT,
    val threshold: Int = 6,
    val groups: List<DuplicateGroup> = emptyList(),
    val loading: Boolean = false,
    val selected: Set<String> = emptySet(),
)

@HiltViewModel
class ImageDuplicatesViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val thumbs: ThumbnailCache,
    private val trash: TrashRepository,
    private val scheduler: JobScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(DuplicateState())
    val state: StateFlow<DuplicateState> = _state

    private val _trashResult = MutableStateFlow<TrashResult?>(null)
    val trashResult: StateFlow<TrashResult?> = _trashResult

    val stats: StateFlow<HashStats> = dao.observeHashStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HashStats(0, 0))

    init { reload() }

    fun setMode(mode: DuplicateMode) {
        _state.update { it.copy(mode = mode, selected = emptySet()) }
        reload()
    }

    fun setThreshold(value: Int) {
        _state.update { it.copy(threshold = value) }
    }

    /** Schwelle wird erst beim Loslassen des Reglers angewendet, damit nicht bei jedem Schritt neu gerechnet wird. */
    fun applyThreshold() = reload()

    fun startAnalysis() {
        viewModelScope.launch { scheduler.enqueueHashes() }
    }

    fun reload() {
        val current = _state.value
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val groups = withContext(Dispatchers.Default) {
                when (current.mode) {
                    DuplicateMode.EXACT -> dao.exactImageDuplicates()
                        .groupBy { it.contentHash }
                        .values
                        .filter { it.size > 1 }
                        .map { DuplicateGroup(it.sortedByDescending { image -> image.size }) }
                    DuplicateMode.SIMILAR -> {
                        val rows = dao.perceptualHashes()
                        val ids = SimilarImageGroups.build(rows.map { HashedItem(it.uuid, it.hash) }, current.threshold)
                        ids.map { group ->
                            val entities = group.chunked(500).flatMap { dao.byUuids(it) }
                            DuplicateGroup(entities.sortedByDescending { it.size })
                        }.filter { it.images.size > 1 }
                    }
                }
            }
            _state.update { it.copy(groups = groups, loading = false, selected = emptySet()) }
        }
    }

    fun toggle(uuid: String) {
        _state.update { s -> s.copy(selected = if (uuid in s.selected) s.selected - uuid else s.selected + uuid) }
    }

    /** Markiert in einer Gruppe alle außer dem größten Bild (die Gruppe ist nach Größe sortiert). */
    fun markAllButLargest(group: DuplicateGroup) {
        val rest = group.images.drop(1).map { it.uuid }
        _state.update { it.copy(selected = it.selected - group.images.map { image -> image.uuid }.toSet() + rest) }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun trashSelected() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _trashResult.value = trash.trash(ids)
            reload()
        }
    }

    fun dismissTrashResult() { _trashResult.value = null }

    fun thumbnailOf(uuid: String): File? =
        thumbs.fileIfPresent(uuid, ThumbVariant.NORMAL) ?: thumbs.fileIfPresent(uuid, ThumbVariant.SMALL)
}
