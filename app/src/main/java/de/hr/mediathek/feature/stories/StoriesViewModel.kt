package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryRow
import de.hr.mediathek.data.story.StoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StorySort(val label: String) { NEWEST("Neueste"), TITLE("Titel"), RATING("Bewertung") }

data class StoryFilter(
    val query: String = "",
    val category: StoryCategory? = null,
    val favoritesOnly: Boolean = false,
    val continueOnly: Boolean = false,
    val sort: StorySort = StorySort.NEWEST,
    val trash: Boolean = false,
)

@HiltViewModel
class StoriesViewModel @Inject constructor(private val repository: StoryRepository) : ViewModel() {
    private val _filter = MutableStateFlow(StoryFilter())
    val filter: StateFlow<StoryFilter> = _filter

    val stories: StateFlow<List<StoryRow>> = combine(
        repository.observeActive(), repository.observeDeleted(), _filter,
    ) { active, deleted, f ->
        if (f.trash) {
            deleted
        } else {
            val q = f.query.trim().lowercase()
            val filtered = active.filter { s ->
                (f.category == null || s.category == f.category) &&
                    (!f.favoritesOnly || s.favorite) &&
                    (!f.continueOnly || s.continueFlag) &&
                    (q.isEmpty() || s.title.lowercase().contains(q) || (s.sourceText?.lowercase()?.contains(q) == true))
            }
            when (f.sort) {
                StorySort.NEWEST -> filtered.sortedByDescending { it.createdAt }
                StorySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                StorySort.RATING -> filtered.sortedByDescending { it.rating }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.purgeExpired() }
    }

    fun update(block: (StoryFilter) -> StoryFilter) = _filter.update(block)

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
}
