package de.hr.mediathek.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.search.SearchHit
import de.hr.mediathek.data.search.SearchIndexer
import de.hr.mediathek.data.search.SearchQuery
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val kinds: Set<String> = emptySet(),
    val indexing: Boolean = true,
    val results: List<SearchHit> = emptyList(),
    val history: List<String> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val indexer: SearchIndexer,
    private val settings: SettingDao,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val trigger = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            _state.update { it.copy(history = loadHistory()) }
            runCatching { indexer.sync() }.onFailure { e -> _state.update { it.copy(error = "Suchindex konnte nicht aufgebaut werden") } }
            _state.update { it.copy(indexing = false) }
            trigger.update { it + 1 }
        }
        viewModelScope.launch {
            trigger.debounce(250).distinctUntilChanged().collect { runSearch() }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        trigger.update { it + 1 }
    }

    fun toggleKind(kind: String) {
        _state.update { it.copy(kinds = if (kind in it.kinds) it.kinds - kind else it.kinds + kind) }
        trigger.update { it + 1 }
    }

    fun clearKinds() {
        _state.update { it.copy(kinds = emptySet()) }
        trigger.update { it + 1 }
    }

    private suspend fun runSearch() {
        val s = _state.value
        if (s.indexing) return
        val parsed = SearchQuery.parse(s.query)
        if (parsed == null) {
            _state.update { it.copy(results = emptyList(), searched = false, error = null) }
            return
        }
        try {
            val hits = indexer.search(parsed, s.kinds)
            _state.update { it.copy(results = hits, searched = true, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(results = emptyList(), searched = true, error = "Suche nicht möglich") }
        }
    }

    /** Wird beim Öffnen eines Treffers aufgerufen: Suchbegriff in den Verlauf (die letzten 10). */
    fun remember() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            val list = (listOf(q) + loadHistory().filter { it != q }).take(10)
            settings.set(SettingEntity(KEY_HISTORY, list.joinToString("\n")))
            _state.update { it.copy(history = list) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settings.set(SettingEntity(KEY_HISTORY, ""))
            _state.update { it.copy(history = emptyList()) }
        }
    }

    private suspend fun loadHistory(): List<String> =
        settings.get(KEY_HISTORY)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    companion object { private const val KEY_HISTORY = "search_history" }
}
