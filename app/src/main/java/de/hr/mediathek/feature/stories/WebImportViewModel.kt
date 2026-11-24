package de.hr.mediathek.feature.stories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.mdimport.ImportItem as ImportEntry
import de.hr.mediathek.data.story.mdimport.ImportReport
import de.hr.mediathek.data.story.mdimport.ImportSource
import de.hr.mediathek.data.story.mdimport.ParseResult
import de.hr.mediathek.data.story.mdimport.RoomImportStore
import de.hr.mediathek.data.story.mdimport.StoryImporter
import de.hr.mediathek.data.story.web.WebDates
import de.hr.mediathek.data.story.web.WebFetchException
import de.hr.mediathek.data.story.web.WebPart
import de.hr.mediathek.data.story.web.WebStories
import de.hr.mediathek.data.story.web.WebStoryFetcher
import de.hr.mediathek.data.story.web.WebThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class WebPhase { IDLE, LOADING, PREVIEW, SAVING, DONE }

/** Ein Kapitel in der Vorschau mit den Werten, die der Nutzer noch ändern kann. */
data class WebPartUi(
    val part: WebPart,
    val selected: Boolean,
    /** Datum als Text ("TT.MM.JJJJ" oder "TT.MM.JJJJ HH:MM"); leer = kein Datum. */
    val dateText: String,
)

data class WebImportUi(
    val url: String = "",
    val phase: WebPhase = WebPhase.IDLE,
    val pagesRead: Int = 0,
    val error: String? = null,
    val thread: WebThread? = null,
    val title: String = "",
    val category: StoryCategory = StoryCategory.MIXED,
    val parts: List<WebPartUi> = emptyList(),
    val report: ImportReport? = null,
    /** Die Seite verlangt eine Anmeldung: Der Bildschirm bietet "Anmelden" an. */
    val loginNeeded: Boolean = false,
    /** Der Nutzer ist in die Anmelde-Ansicht gegangen; beim Zurückkommen wird neu geladen. */
    val loginOpened: Boolean = false,
)

@HiltViewModel
class WebImportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val fetcher: WebStoryFetcher,
    private val store: RoomImportStore,
    private val scheduler: de.hr.mediathek.core.jobs.JobScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(WebImportUi(url = savedState.get<String>("url").orEmpty()))
    val state: StateFlow<WebImportUi> = _state
    private var job: Job? = null

    init {
        // Link aus der Teilen-Funktion: gleich laden.
        if (_state.value.url.isNotBlank()) load()
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, error = null) }
    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setCategory(c: StoryCategory) = _state.update { it.copy(category = c) }

    fun setSelected(id: String, value: Boolean) = updatePart(id) { it.copy(selected = value) }
    fun setDateText(id: String, value: String) = updatePart(id) { it.copy(dateText = value) }

    fun selectAll(value: Boolean) = _state.update { s -> s.copy(parts = s.parts.map { it.copy(selected = value) }) }

    private fun updatePart(id: String, change: (WebPartUi) -> WebPartUi) =
        _state.update { s -> s.copy(parts = s.parts.map { if (it.part.id == id) change(it) else it }) }

    fun load() {
        if (job?.isActive == true) return
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(error = "Bitte einen Link eingeben.") }
            return
        }
        job = viewModelScope.launch {
            _state.update {
                it.copy(phase = WebPhase.LOADING, pagesRead = 0, error = null, thread = null, parts = emptyList(), report = null, loginNeeded = false, loginOpened = false)
            }
            try {
                val thread = fetcher.load(url) { n -> _state.update { it.copy(pagesRead = n) } }
                _state.update {
                    it.copy(
                        phase = WebPhase.PREVIEW,
                        thread = thread,
                        title = thread.title,
                        parts = thread.parts.map { p -> WebPartUi(p, p.selected, WebDates.format(p.dateMillis)) },
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = WebPhase.IDLE) }
                throw e
            } catch (e: WebFetchException) {
                _state.update { it.copy(phase = WebPhase.IDLE, error = e.message, loginNeeded = e.loginNeeded) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = WebPhase.IDLE, error = "Das hat nicht geklappt: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Wird gerufen, wenn der Nutzer zur Anmelde-Ansicht wechselt. */
    fun loginOpened() = _state.update { it.copy(loginOpened = true) }

    /** Beim Zurückkommen aus der Anmelde-Ansicht: Seite mit der neuen Anmeldung noch einmal laden. */
    fun retryAfterLogin() {
        if (_state.value.loginOpened && _state.value.phase == WebPhase.IDLE) load()
    }

    /** Speichert die gewählten Kapitel über den normalen Import (Duplikate werden erkannt, Serien angelegt). */
    fun save() {
        val s = _state.value
        val thread = s.thread ?: return
        val chosen = s.parts.filter { it.selected && it.part.text.isNotBlank() }
        if (chosen.isEmpty()) {
            _state.update { it.copy(error = "Kein Kapitel ausgewählt.") }
            return
        }
        if (job?.isActive == true) return
        val title = s.title.trim().ifEmpty { thread.title }
        val now = System.currentTimeMillis()
        val stories = WebStories.parsed(
            thread, title, s.category,
            chosen.map { ui -> ui.part to (WebDates.parseInput(ui.dateText)?.millis ?: if (ui.dateText.isBlank()) null else ui.part.dateMillis) },
            now,
        )
        val items = stories.mapIndexed { i, story -> ImportEntry("$title – Teil ${i + 1}") { ParseResult.Ok(story) } }
        val source = ImportSource(thread.host, items.size, items.asSequence())
        job = viewModelScope.launch {
            _state.update { it.copy(phase = WebPhase.SAVING, error = null) }
            try {
                val report = withContext(Dispatchers.IO) { StoryImporter(store).run(source) }
                _state.update { it.copy(phase = WebPhase.DONE, report = report) }
                runCatching { scheduler.enqueueBackupAfterChange() }
            } catch (e: StoryImporter.CancelledWithReport) {
                _state.update { it.copy(phase = WebPhase.DONE, report = e.report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = WebPhase.PREVIEW, error = "Speichern fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Zurück auf Anfang für den nächsten Link. */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = WebImportUi()
    }
}
