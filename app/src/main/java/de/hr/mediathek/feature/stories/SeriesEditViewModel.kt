package de.hr.mediathek.feature.stories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.SeriesPart
import de.hr.mediathek.data.db.StoryRow
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.data.story.organize.StoryOrganizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Eine Geschichte, die in die Serie aufgenommen werden könnte, samt Hinweis auf ihre bisherige Serie. */
data class AddCandidate(val row: StoryRow, val currentSeries: String?)

@HiltViewModel
class SeriesEditViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: StoryRepository,
    private val organizer: StoryOrganizer,
) : ViewModel() {
    val seriesUuid: String = checkNotNull(handle["uuid"])

    val name = MutableStateFlow("")
    private val _dissolved = MutableStateFlow(false)
    val dissolved: StateFlow<Boolean> = _dissolved

    val parts: StateFlow<List<SeriesPart>> = organizer.observeParts(seriesUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Alle Geschichten außerhalb dieser Serie für den Hinzufügen-Dialog. */
    val candidates: StateFlow<List<AddCandidate>> = combine(
        repository.observeActive(), repository.observeSeriesRows(),
    ) { rows, series ->
        val names = series.associate { it.uuid to it.name }
        rows.filter { it.seriesUuid != seriesUuid }
            .sortedBy { it.title.lowercase() }
            .map { AddCandidate(it, it.seriesUuid?.let { id -> names[id] }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { name.value = repository.seriesName(seriesUuid).orEmpty() }
    }

    fun saveName(value: String) {
        name.value = value
        viewModelScope.launch { organizer.renameSeries(seriesUuid, value) }
    }

    fun move(part: SeriesPart, up: Boolean) = viewModelScope.launch { organizer.movePart(seriesUuid, part.uuid, up) }
    fun remove(part: SeriesPart) = viewModelScope.launch { organizer.removePart(seriesUuid, part.uuid) }
    fun add(uuids: List<String>) = viewModelScope.launch { organizer.addParts(seriesUuid, uuids) }
    fun sortByTitle() = viewModelScope.launch { organizer.sortPartsByTitle(seriesUuid) }
    fun dissolve() = viewModelScope.launch {
        organizer.dissolveSeries(seriesUuid)
        _dissolved.value = true
    }
}
