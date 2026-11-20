package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.story.organize.SeriesProposal
import de.hr.mediathek.data.story.organize.StoryOrganizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SuggestItem(val id: String, val proposal: SeriesProposal, val name: String, val selected: Boolean = true)

data class SuggestUi(val loading: Boolean = true, val items: List<SuggestItem> = emptyList(), val message: String? = null)

@HiltViewModel
class SeriesSuggestViewModel @Inject constructor(private val organizer: StoryOrganizer) : ViewModel() {
    private val _state = MutableStateFlow(SuggestUi())
    val state: StateFlow<SuggestUi> = _state

    init { load() }

    fun load() {
        _state.value = SuggestUi(loading = true)
        viewModelScope.launch {
            val items = runCatching { organizer.suggestSeries() }.getOrDefault(emptyList())
                .map { SuggestItem(it.parts.joinToString { p -> p.uuid }, it, it.name) }
            _state.value = SuggestUi(loading = false, items = items)
        }
    }

    fun toggle(index: Int) = edit(index) { it.copy(selected = !it.selected) }
    fun rename(index: Int, name: String) = edit(index) { it.copy(name = name) }
    fun selectAll(on: Boolean) = _state.update { s -> s.copy(items = s.items.map { it.copy(selected = on) }) }

    private fun edit(index: Int, block: (SuggestItem) -> SuggestItem) =
        _state.update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == index) block(it) else it }) }

    fun applySelected() {
        val chosen = _state.value.items.filter { it.selected && it.name.isNotBlank() }
        viewModelScope.launch {
            for (c in chosen) organizer.applySeries(c.proposal, c.name)
            _state.update { s -> s.copy(items = s.items.filter { it !in chosen }, message = "${chosen.size} Serien übernommen.") }
        }
    }
}
