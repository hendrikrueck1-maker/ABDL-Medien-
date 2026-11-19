package de.hr.mediathek.feature.media

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
import de.hr.mediathek.data.db.TagEntity
import de.hr.mediathek.data.prefs.BrowseMode
import de.hr.mediathek.data.prefs.CardLayout
import de.hr.mediathek.data.prefs.ViewPrefsRepository
import de.hr.mediathek.data.prefs.ViewSettings
import de.hr.mediathek.data.tag.TagRepository
import de.hr.mediathek.data.thumb.ThumbnailCache
import de.hr.mediathek.feature.images.ImageContext
import de.hr.mediathek.feature.library.LibraryBrowser
import de.hr.mediathek.feature.videos.PlaybackContext
import de.hr.mediathek.feature.videos.VideoFilter
import de.hr.mediathek.feature.videos.VideoQuery
import de.hr.mediathek.feature.videos.VideoSort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

/** Alle Videos und Bilder gemeinsam, wahlweise nach Ordnern gruppiert. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MediaAllViewModel @Inject constructor(
    private val dao: MediaFileDao,
    thumbs: ThumbnailCache,
    categories: CategoryRepository,
    tags: TagRepository,
    prefs: ViewPrefsRepository,
    private val playback: PlaybackContext,
    private val imageContext: ImageContext,
) : ViewModel() {

    private val _filter = MutableStateFlow(VideoFilter())
    val filter: StateFlow<VideoFilter> = _filter

    val browser = LibraryBrowser(
        scope = viewModelScope, dao = dao, thumbs = thumbs, prefs = prefs, type = "ALL", module = "media",
        defaults = ViewSettings(mode = BrowseMode.FOLDERS, layout = CardLayout.TILES, showDetails = false),
    )
    private val debouncedFilter = _filter.debounce(250)
    private var randomSeed = System.currentTimeMillis() % 1_000_000_000L

    val folders = browser.folders(debouncedFilter)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: Flow<PagingData<MediaFileEntity>> = browser.effective(debouncedFilter)
        .flatMapLatest { f ->
            val built = VideoQuery.build(f, randomSeed, type = "ALL")
            Pager(PagingConfig(pageSize = 90, prefetchDistance = 90, enablePlaceholders = false)) {
                dao.pagingVideos(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            }.flow
        }
        .cachedIn(viewModelScope)

    val categoryList: StateFlow<List<CategoryEntity>> = categories.observeVideoCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tagList: StateFlow<List<TagEntity>> = tags.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(change: (VideoFilter) -> VideoFilter) { _filter.update(change) }

    fun reshuffle() {
        randomSeed = System.currentTimeMillis() % 1_000_000_000L
        _filter.update { it.copy() }
    }

    /** Merkt die Liste für "Nächstes" (Video) bzw. Blättern (Bild). */
    fun rememberContext() {
        val f = browser.effectiveNow(_filter.value)
        playback.filter = f
        playback.seed = randomSeed
        imageContext.filter = f
        imageContext.seed = randomSeed
    }

    fun thumbnailOf(uuid: String): File? = browser.thumbFile(uuid)
}
