package de.hr.mediathek.feature.videos

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
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.thumb.ThumbnailCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class VideosViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val thumbs: ThumbnailCache,
    private val categories: CategoryRepository,
    private val trash: de.hr.mediathek.data.trash.TrashRepository,
    private val tags: de.hr.mediathek.data.tag.TagRepository,
    private val playlistRepo: de.hr.mediathek.data.playlist.PlaylistRepository,
    private val playback: PlaybackContext,
    prefs: de.hr.mediathek.data.prefs.ViewPrefsRepository,
    compressionDao: de.hr.mediathek.data.db.CompressionDao,
) : ViewModel() {

    private val _filter = MutableStateFlow(VideoFilter())
    val filter: StateFlow<VideoFilter> = _filter

    val browser = de.hr.mediathek.feature.library.LibraryBrowser(
        scope = viewModelScope, dao = dao, thumbs = thumbs, prefs = prefs, type = "VIDEO", module = "videos",
    )

    /** Videos, die komprimiert wurden (auch wenn das Original schon gelöscht ist); für die Kennzeichnung. */
    val compressedUuids: StateFlow<Set<String>> = compressionDao.observeCompressedUuids()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val debouncedFilter = _filter.debounce(250)
    val folders = browser.folders(debouncedFilter)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Seed für "Zufall": bleibt während der Sitzung gleich, damit beim Blättern nichts doppelt oder fehlt. */
    private var randomSeed = System.currentTimeMillis() % 1_000_000_000L

    val tagList: StateFlow<List<de.hr.mediathek.data.db.TagEntity>> = tags.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<de.hr.mediathek.data.db.PlaylistEntity>> = playlistRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val videos: Flow<PagingData<MediaFileEntity>> = browser.effective(debouncedFilter)
        .flatMapLatest { f ->
            val built = VideoQuery.build(f, randomSeed)
            Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = false)) {
                dao.pagingVideos(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            }.flow
        }
        .cachedIn(viewModelScope)

    val categoryList: StateFlow<List<CategoryEntity>> = categories.observeVideoCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Mehrfachauswahl: UUIDs der markierten Videos. Leer = keine Auswahl. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    /** Kategorien, die mindestens einem ausgewählten Video zugeordnet sind (für den Dialog). */
    private val _usedCategories = MutableStateFlow<Set<String>>(emptySet())
    val usedCategories: StateFlow<Set<String>> = _usedCategories

    fun toggleSelected(uuid: String) {
        _selected.update { if (uuid in it) it - uuid else it + uuid }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun favoriteSelected(favorite: Boolean) = withSelection { dao.setFavoriteBulk(it, favorite, now()) }
    fun rateSelected(rating: Int) = withSelection { dao.setRatingBulk(it, rating.coerceIn(0, 5), now()) }
    fun assignSelected(categoryUuid: String) = withSelection { categories.assign(it, categoryUuid) }
    fun unassignSelected(categoryUuid: String) = withSelection { categories.unassign(it, categoryUuid) }

    fun loadUsedCategories() {
        val ids = _selected.value.toList()
        viewModelScope.launch { _usedCategories.value = categories.usedBy(ids) }
    }

    private val _trashResult = MutableStateFlow<de.hr.mediathek.data.trash.TrashResult?>(null)
    val trashResult: StateFlow<de.hr.mediathek.data.trash.TrashResult?> = _trashResult

    fun trashSelected() {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _trashResult.value = trash.trash(ids)
            _selected.value = emptySet()
        }
    }

    fun dismissTrashResult() {
        _trashResult.value = null
    }

    private val _usedTags = MutableStateFlow<Set<String>>(emptySet())
    val usedTags: StateFlow<Set<String>> = _usedTags

    fun loadUsedTags() {
        val ids = _selected.value.toList()
        viewModelScope.launch { _usedTags.value = tags.usedBy(ids) }
    }

    fun assignTag(uuid: String) = withSelection { tags.assign(it, uuid); _usedTags.value = tags.usedBy(it) }
    fun unassignTag(uuid: String) = withSelection { tags.unassign(it, uuid); _usedTags.value = tags.usedBy(it) }

    fun createTag(name: String) {
        viewModelScope.launch { tags.create(name) }
    }

    fun deleteTag(uuid: String) {
        viewModelScope.launch {
            tags.delete(uuid)
            if (_filter.value.tagUuid == uuid) update { it.copy(tagUuid = null) }
        }
    }

    // --- Playlists ---------------------------------------------------------------------------

    fun createPlaylist(name: String) {
        viewModelScope.launch { playlistRepo.createManual(name) }
    }

    fun saveSmartPlaylist(name: String) {
        viewModelScope.launch { playlistRepo.createSmart(name, _filter.value.copy(playlistUuid = null, sort = _filter.value.sort)) }
    }

    fun deletePlaylist(uuid: String) {
        viewModelScope.launch {
            playlistRepo.delete(uuid)
            if (_filter.value.playlistUuid == uuid) update { it.copy(playlistUuid = null, sort = VideoSort.ADDED_DESC) }
        }
    }

    fun addSelectedToPlaylist(uuid: String) = withSelection { playlistRepo.add(uuid, it) }

    fun removeSelectedFromCurrentPlaylist() {
        val pl = _filter.value.playlistUuid ?: return
        withSelection { playlistRepo.remove(pl, it) }
    }

    /** Öffnet eine Playlist: manuell = nur ihre Videos in Listenreihenfolge, smart = gespeicherter Filter. */
    fun applyPlaylist(playlist: de.hr.mediathek.data.db.PlaylistEntity) {
        if (playlist.type == de.hr.mediathek.data.db.PlaylistType.MANUAL) {
            _filter.value = VideoFilter(playlistUuid = playlist.uuid, sort = VideoSort.PLAYLIST_ORDER)
        } else {
            _filter.value = de.hr.mediathek.data.playlist.FilterJson.decode(playlist.filterJson, playlist.filterVersion)
            playlist.randomSeed?.let { randomSeed = it }
        }
    }

    // --- Öffnen und Zufall -------------------------------------------------------------------

    /** Merkt die Liste, aus der geöffnet wird, damit der Player "Nächstes" kennt. */
    fun rememberContext() {
        playback.filter = browser.effectiveNow(_filter.value)
        playback.seed = randomSeed
    }

    /** Ein zufälliges verfügbares Video aus dem aktuellen Filter; danach läuft der Player zufällig weiter. */
    fun openRandom(onFound: (String) -> Unit, onNone: () -> Unit) {
        viewModelScope.launch {
            val scope = browser.scopeNow(_filter.value)
            val built = VideoQuery.buildRandomPick(scope)
            val id = dao.uuids(SimpleSQLiteQuery(built.sql, built.args.toTypedArray())).firstOrNull()
            if (id == null) {
                onNone()
            } else {
                randomSeed = System.currentTimeMillis() % 1_000_000_000L
                playback.filter = scope.copy(sort = VideoSort.RANDOM)
                playback.seed = randomSeed
                onFound(id)
            }
        }
    }

    fun createCategory(name: String) {
        viewModelScope.launch { categories.create(name) }
    }

    fun deleteCategory(uuid: String) {
        viewModelScope.launch {
            categories.delete(uuid)
            if (_filter.value.categoryUuid == uuid) update { it.copy(categoryUuid = null) }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun withSelection(block: suspend (List<String>) -> Unit) {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            block(ids)
            loadUsedCategories()
        }
    }

    fun update(change: (VideoFilter) -> VideoFilter) {
        _filter.update(change)
    }

    fun reshuffle() {
        randomSeed = System.currentTimeMillis() % 1_000_000_000L
        _filter.update { it.copy() }
    }

    fun thumbnailOf(uuid: String): File? =
        thumbs.fileIfPresent(uuid, ThumbVariant.NORMAL) ?: thumbs.fileIfPresent(uuid, ThumbVariant.SMALL)
}
