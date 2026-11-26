package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryFolderEntity
import de.hr.mediathek.data.db.StoryRow
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.data.story.organize.StoryOrganizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StoriesViewModel @Inject constructor(
    private val repository: StoryRepository,
    private val organizer: StoryOrganizer,
    private val settings: SettingDao,
    private val storage: de.hr.mediathek.data.storage.StorageRepository,
) : ViewModel() {
    /** Kein Arbeitsordner gewählt: Geschichten werden nicht automatisch gesichert (z. B. auf dem Handy). */
    private val _backupMissing = MutableStateFlow(false)
    val backupMissing: StateFlow<Boolean> = _backupMissing

    init {
        viewModelScope.launch { _backupMissing.value = storage.getWorkFolderUri() == null }
    }

    private val _filter = MutableStateFlow(StoryFilter())
    val filter: StateFlow<StoryFilter> = _filter

    private val expanded = MutableStateFlow(StoryCategory.entries.map { "cat:${it.name}" }.toSet())

    private val data: StateFlow<StoryData> = combine(
        repository.observeActive(),
        repository.observeSeriesRows(),
        repository.observeFolders(),
        repository.observeFolderItems(),
    ) { active, series, folders, items -> StoryData(active, series, folders, items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryData())

    val folders: StateFlow<List<StoryFolderEntity>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folderItems = repository.observeFolderItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val deleted: StateFlow<List<StoryRow>> = repository.observeDeleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<ListItem>> = combine(data, _filter, expanded) { d, f, open ->
        if (f.trash) emptyList() else StoryListBuilder.build(d, f, open)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trash: StateFlow<List<StoryRow>> = deleted

    private val page = MutableStateFlow(0)
    private val ready = MutableStateFlow(false)

    /** Die aktuelle Seite mit 10 Einträgen. */
    val paged: StateFlow<PagedUi> = combine(items, page, ready) { list, p, ok ->
        if (!ok) PagedUi() else StoryPaging.build(list, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagedUi())

    /** Zuletzt geöffnete Geschichte (wird vom Leser gesetzt). */
    val lastOpened: StateFlow<String?> = settings.observe(K_LAST_OPEN)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val continueReading: StateFlow<List<StoryRow>> = data.combine(_filter) { d, f ->
        if (f.trash || f.query.isNotBlank()) emptyList() else StoryListBuilder.continueReading(d.active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Ansicht, Sortierung, Ordner und Seite vom letzten Mal wiederherstellen.
            val view = settings.get(K_VIEW)?.let { runCatching { StoryView.valueOf(it) }.getOrNull() } ?: StoryView.LIST
            val sort = settings.get(K_SORT)?.let { runCatching { StorySort.valueOf(it) }.getOrNull() } ?: StorySort.NEWEST
            val read = settings.get(K_READ)?.let { runCatching { ReadState.valueOf(it) }.getOrNull() } ?: ReadState.ALL
            val folder = settings.get(K_FOLDER)?.takeIf { it.isNotEmpty() }
            _filter.value = StoryFilter(view = view, sort = sort, readState = read, folder = folder)
            page.value = settings.get(K_PAGE)?.toIntOrNull() ?: 0
            ready.value = true
        }
        viewModelScope.launch {
            repository.purgeExpired()
            repository.backfillWordCounts()
        }
    }

    /** Jede Änderung von Suche, Filter, Ansicht oder Ordner beginnt wieder auf Seite 1. */
    fun update(block: (StoryFilter) -> StoryFilter) {
        val old = _filter.value
        val new = block(old)
        if (new == old) return
        _filter.value = new
        page.value = 0
        viewModelScope.launch {
            settings.set(SettingEntity(K_VIEW, new.view.name))
            settings.set(SettingEntity(K_SORT, new.sort.name))
            settings.set(SettingEntity(K_READ, new.readState.name))
            settings.set(SettingEntity(K_FOLDER, new.folder.orEmpty()))
            settings.set(SettingEntity(K_PAGE, "0"))
        }
    }

    fun setPage(value: Int) {
        val last = paged.value.pageCount - 1
        val p = value.coerceIn(0, maxOf(last, 0))
        page.value = p
        viewModelScope.launch { settings.set(SettingEntity(K_PAGE, p.toString())) }
    }

    fun toggleExpanded(key: String) = expanded.update { if (key in it) it - key else it + key }

    suspend fun createEmpty(): String = repository.create("", "")

    // Gezielte Einzel-Updates (nie die ganze Geschichte zurückschreiben, siehe Leser).
    fun toggleFavorite(row: StoryRow) = viewModelScope.launch { repository.setFavorite(row.uuid, !row.favorite) }
    fun toggleContinue(row: StoryRow) = viewModelScope.launch { repository.setContinue(row.uuid, !row.continueFlag) }
    fun togglePartsMissing(row: StoryRow) = viewModelScope.launch { repository.setPartsMissing(row.uuid, !row.partsMissing) }
    fun setCategory(row: StoryRow, category: StoryCategory) = viewModelScope.launch { repository.setCategory(row.uuid, category) }
    fun setRating(row: StoryRow, rating: Int) = viewModelScope.launch { repository.setRating(row.uuid, if (row.rating == rating) 0 else rating) }

    // --- Zusammenführen (Mehrfachauswahl) ---
    private val _selection = MutableStateFlow<List<String>?>(null)
    /** null = keine Auswahl aktiv; sonst die gewählten Geschichten in der Reihenfolge des Antippens. */
    val selection: StateFlow<List<String>?> = _selection

    /** Auswahl beginnen, optional gleich mit der lang gedrückten Geschichte (bzw. allen Teilen einer Serie). */
    fun startSelection(initial: List<String> = emptyList()) { _selection.value = initial.distinct() }
    fun cancelSelection() { _selection.value = null }
    fun toggleSelected(uuids: List<String>) = _selection.update { sel ->
        sel?.let { if (uuids.all { it in sel }) sel - uuids.toSet() else sel + uuids.filter { it !in sel } }
    }

    /** Gewählte Geschichten (auch ganze Serien) als Serie zusammenführen; gibt die UUID der Serie zurück. */
    suspend fun mergeSelected(name: String): String? {
        val ids = _selection.value.orEmpty()
        if (ids.size < 2) return null
        val series = organizer.mergeIntoSeries(name, ids)
        _selection.value = null
        return series
    }

    fun delete(row: StoryRow) = viewModelScope.launch { repository.delete(row.uuid) }

    /** Alle Geschichten, die die Liste mit Suche und Filter gerade zeigt (über alle Seiten, Serien mit allen Teilen). */
    fun visibleStoryUuids(): List<String> = items.value.flatMap { i ->
        when (i) {
            is ListItem.Single -> listOf(i.row.uuid)
            is ListItem.SeriesGroup -> i.parts.map { it.uuid }
            else -> emptyList()
        }
    }.distinct()

    private val _lastBulk = MutableStateFlow<List<String>>(emptyList())
    /** Zuletzt gesammelt gelöschte Geschichten, damit „Rückgängig“ möglich ist. */
    val lastBulk: StateFlow<List<String>> = _lastBulk

    /** Die angezeigten Geschichten in den Papierkorb (läuft auch weiter, wenn die Seite verlassen wird). */
    fun deleteVisible(uuids: List<String>) {
        _lastBulk.value = uuids
        de.hr.mediathek.core.AppScope.launch { repository.deleteMany(uuids) }
    }

    fun undoBulkDelete() {
        val ids = _lastBulk.value
        _lastBulk.value = emptyList()
        de.hr.mediathek.core.AppScope.launch { repository.restoreMany(ids) }
    }

    fun dismissBulk() { _lastBulk.value = emptyList() }

    fun restoreAll() {
        val ids = trash.value.map { it.uuid }
        de.hr.mediathek.core.AppScope.launch { repository.restoreMany(ids) }
    }

    fun emptyTrash() {
        val ids = trash.value.map { it.uuid }
        de.hr.mediathek.core.AppScope.launch { repository.purgeMany(ids) }
    }
    fun restore(row: StoryRow) = viewModelScope.launch { repository.restore(row.uuid) }
    fun purge(row: StoryRow) = viewModelScope.launch { repository.purge(row.uuid) }

    // --- Ordner ---
    fun createFolder(name: String, parent: String?) = viewModelScope.launch { organizer.createFolder(name, parent) }
    fun renameFolder(uuid: String, name: String) = viewModelScope.launch { organizer.renameFolder(uuid, name) }
    fun deleteFolder(folder: StoryFolderEntity) = viewModelScope.launch {
        organizer.deleteFolder(folder)
        update { if (it.folder == folder.uuid) it.copy(folder = folder.parentUuid) else it }
    }
    fun setInFolder(folderUuid: String, storyUuids: List<String>, inFolder: Boolean) =
        viewModelScope.launch { organizer.setManyInFolder(folderUuid, storyUuids, inFolder) }
    fun createFolderAndAdd(name: String, storyUuids: List<String>) = viewModelScope.launch {
        val id = organizer.createFolder(name, null)
        organizer.setManyInFolder(id, storyUuids, true)
    }

    private companion object {
        const val K_VIEW = "stories_view"
        const val K_SORT = "stories_sort"
        const val K_READ = "stories_read_state"
        const val K_FOLDER = "stories_folder"
        const val K_PAGE = "stories_page"
        const val K_LAST_OPEN = "stories_last_open"
    }
}
