package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {
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

    val continueReading: StateFlow<List<StoryRow>> = data.combine(_filter) { d, f ->
        if (f.trash || f.query.isNotBlank()) emptyList() else StoryListBuilder.continueReading(d.active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repository.purgeExpired()
            repository.backfillWordCounts()
        }
    }

    fun update(block: (StoryFilter) -> StoryFilter) = _filter.update(block)

    fun toggleExpanded(key: String) = expanded.update { if (key in it) it - key else it + key }

    suspend fun createEmpty(): String = repository.create("", "")

    fun toggleFavorite(row: StoryRow) = edit(row.uuid) { it.copy(favorite = !it.favorite) }
    fun toggleContinue(row: StoryRow) = edit(row.uuid) { it.copy(continueFlag = !it.continueFlag) }
    fun setRating(row: StoryRow, rating: Int) = edit(row.uuid) { it.copy(rating = if (it.rating == rating) 0 else rating) }

    private fun edit(uuid: String, block: (de.hr.mediathek.data.db.StoryEntity) -> de.hr.mediathek.data.db.StoryEntity) {
        viewModelScope.launch { repository.get(uuid)?.let { repository.update(block(it)) } }
    }

    fun delete(row: StoryRow) = viewModelScope.launch { repository.delete(row.uuid) }
    fun restore(row: StoryRow) = viewModelScope.launch { repository.restore(row.uuid) }
    fun purge(row: StoryRow) = viewModelScope.launch { repository.purge(row.uuid) }

    // --- Ordner ---
    fun createFolder(name: String, parent: String?) = viewModelScope.launch { organizer.createFolder(name, parent) }
    fun renameFolder(uuid: String, name: String) = viewModelScope.launch { organizer.renameFolder(uuid, name) }
    fun deleteFolder(folder: StoryFolderEntity) = viewModelScope.launch {
        organizer.deleteFolder(folder)
        _filter.update { if (it.folder == folder.uuid) it.copy(folder = folder.parentUuid) else it }
    }
    fun setInFolder(folderUuid: String, storyUuids: List<String>, inFolder: Boolean) =
        viewModelScope.launch { organizer.setManyInFolder(folderUuid, storyUuids, inFolder) }
    fun createFolderAndAdd(name: String, storyUuids: List<String>) = viewModelScope.launch {
        val id = organizer.createFolder(name, null)
        organizer.setManyInFolder(id, storyUuids, true)
    }
}
