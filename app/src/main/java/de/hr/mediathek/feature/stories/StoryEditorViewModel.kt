package de.hr.mediathek.feature.stories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryDraftVersionEntity
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.core.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorState(
    val loaded: Boolean = false,
    val title: String = "",
    val text: String = "",
    val source: String = "",
    val url: String = "",
    /** Original-Datum der Geschichte als Text ("TT.MM.JJJJ HH:MM"); leer = unbekannt. */
    val published: String = "",
    val category: StoryCategory = StoryCategory.MIXED,
    val saved: Boolean = true,
    val drafts: List<StoryDraftVersionEntity>? = null,
)

@HiltViewModel
class StoryEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: StoryRepository,
) : ViewModel() {
    private val uuid: String = checkNotNull(handle["uuid"])
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state
    private var saveJob: Job? = null

    /** Zuletzt verwendete Quellen und Adressen, als Tasten zum Schnellauswählen. */
    val recentSources: StateFlow<List<String>> = repository.recentSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentUrls: StateFlow<List<String>> = repository.recentUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val story = repository.get(uuid)
            val text = repository.text(uuid)
            _state.value = EditorState(
                loaded = true, title = story?.title ?: "", text = text, source = story?.sourceText ?: "",
                url = story?.sourceUrl ?: "", category = story?.category ?: StoryCategory.MIXED,
                published = de.hr.mediathek.data.story.web.WebDates.format(story?.publishedAt),
            )
        }
    }

    fun change(block: (EditorState) -> EditorState) {
        _state.value = block(_state.value).copy(saved = false)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        val s = _state.value
        val story = repository.get(uuid) ?: return
        repository.update(
            story.copy(
                title = s.title.ifBlank { "Ohne Titel" }, sourceText = s.source.ifBlank { null },
                sourceUrl = s.url.ifBlank { null }, category = s.category,
                // Ungültige Eingabe lässt das bisherige Datum stehen; leer löscht es.
                publishedAt = if (s.published.isBlank()) null
                else de.hr.mediathek.data.story.web.WebDates.parseInput(s.published)?.millis ?: story.publishedAt,
            ),
        )
        repository.saveText(uuid, s.text)
        _state.value = _state.value.copy(saved = true)
    }

    /** Beim Verlassen sofort speichern. */
    fun flush() {
        saveJob?.cancel()
        // App-Bereich: Beim Verlassen des Editors darf das Speichern nicht abgebrochen werden.
        if (!_state.value.saved) AppScope.launch { persist() }
    }

    fun showDrafts(show: Boolean) {
        viewModelScope.launch { _state.value = _state.value.copy(drafts = if (show) repository.drafts(uuid) else null) }
    }

    fun restore(draft: StoryDraftVersionEntity) {
        change { it.copy(text = draft.text, drafts = null) }
    }

    private companion object {
        const val AUTOSAVE_MS = 1500L
    }
}
