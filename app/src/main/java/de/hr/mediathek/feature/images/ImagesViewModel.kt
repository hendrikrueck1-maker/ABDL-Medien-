package de.hr.mediathek.feature.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.category.CategoryRepository
import de.hr.mediathek.data.db.CategoryEntity
import de.hr.mediathek.data.db.CategoryScope
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.TagEntity
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.tag.TagRepository
import de.hr.mediathek.data.thumb.ThumbnailCache
import de.hr.mediathek.data.trash.TrashRepository
import de.hr.mediathek.data.trash.TrashResult
import de.hr.mediathek.feature.videos.VideoFilter
import de.hr.mediathek.feature.videos.VideoQuery
import de.hr.mediathek.feature.videos.VideoSort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class ImagesViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val thumbs: ThumbnailCache,
    private val categories: CategoryRepository,
    private val tags: TagRepository,
    private val trash: TrashRepository,
    private val context: ImageContext,
) : ViewModel() {

    private val _filter = MutableStateFlow(VideoFilter())
    val filter: StateFlow<VideoFilter> = _filter

    private var randomSeed = System.currentTimeMillis() % 1_000_000_000L

    val images: Flow<PagingData<MediaFileEntity>> = _filter
        .debounce(250)
        .flatMapLatest { f ->
            val built = VideoQuery.build(f, randomSeed, type = "IMAGE")
            Pager(PagingConfig(pageSize = 90, prefetchDistance = 90, enablePlaceholders = false)) {
                dao.pagingVideos(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            }.flow
        }
        .cachedIn(viewModelScope)

    val categoryList: StateFlow<List<CategoryEntity>> = categories.observeImageCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tagList: StateFlow<List<TagEntity>> = tags.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders: StateFlow<List<String>> = dao.observeImageFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected
    private val _usedCategories = MutableStateFlow<Set<String>>(emptySet())
    val usedCategories: StateFlow<Set<String>> = _usedCategories
    private val _usedTags = MutableStateFlow<Set<String>>(emptySet())
    val usedTags: StateFlow<Set<String>> = _usedTags
    private val _trashResult = MutableStateFlow<TrashResult?>(null)
    val trashResult: StateFlow<TrashResult?> = _trashResult

    fun toggleSelected(uuid: String) = _selected.update { if (uuid in it) it - uuid else it + uuid }
    fun clearSelection() { _selected.value = emptySet() }
    fun selectAll(uuids: Collection<String>) { _selected.value = uuids.toSet() }

    fun favoriteSelected(favorite: Boolean) = withSelection { dao.setFavoriteBulk(it, favorite, now()) }
    fun rateSelected(rating: Int) = withSelection { dao.setRatingBulk(it, rating.coerceIn(0, 5), now()) }
    fun assignCategory(uuid: String) = withSelection { categories.assign(it, uuid) }
    fun unassignCategory(uuid: String) = withSelection { categories.unassign(it, uuid) }
    fun assignTag(uuid: String) = withSelection { tags.assign(it, uuid) }
    fun unassignTag(uuid: String) = withSelection { tags.unassign(it, uuid) }

    fun loadUsed() {
        val ids = _selected.value.toList()
        viewModelScope.launch {
            _usedCategories.value = categories.usedBy(ids)
            _usedTags.value = tags.usedBy(ids)
        }
    }

    fun createCategory(name: String) { viewModelScope.launch { categories.create(name, CategoryScope.IMAGE) } }
    fun deleteCategory(uuid: String) {
        viewModelScope.launch {
            categories.delete(uuid)
            if (_filter.value.categoryUuid == uuid) update { it.copy(categoryUuid = null) }
        }
    }
    fun createTag(name: String) { viewModelScope.launch { tags.create(name) } }
    fun deleteTag(uuid: String) {
        viewModelScope.launch {
            tags.delete(uuid)
            if (_filter.value.tagUuid == uuid) update { it.copy(tagUuid = null) }
        }
    }

    fun trashSelected() {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _trashResult.value = trash.trash(ids)
            _selected.value = emptySet()
        }
    }

    fun dismissTrashResult() { _trashResult.value = null }

    fun update(change: (VideoFilter) -> VideoFilter) { _filter.update(change) }

    fun reshuffle() {
        randomSeed = System.currentTimeMillis() % 1_000_000_000L
        _filter.update { it.copy() }
    }

    /** Vor dem Öffnen: Liste für Vollbild-Blättern und Diashow merken. */
    fun rememberContext() {
        context.filter = _filter.value
        context.seed = randomSeed
    }

    fun thumbnailOf(uuid: String): File? =
        thumbs.fileIfPresent(uuid, ThumbVariant.NORMAL) ?: thumbs.fileIfPresent(uuid, ThumbVariant.SMALL)

    private fun now() = System.currentTimeMillis()

    private fun withSelection(block: suspend (List<String>) -> Unit) {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            block(ids)
            loadUsed()
        }
    }
}
