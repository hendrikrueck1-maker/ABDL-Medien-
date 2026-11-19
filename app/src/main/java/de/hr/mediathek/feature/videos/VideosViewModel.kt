package de.hr.mediathek.feature.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class VideosViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val thumbs: ThumbnailCache,
) : ViewModel() {

    private val _filter = MutableStateFlow(VideoFilter())
    val filter: StateFlow<VideoFilter> = _filter

    /** Seed für "Zufall": bleibt während der Sitzung gleich, damit beim Blättern nichts doppelt oder fehlt. */
    private var randomSeed = System.currentTimeMillis() % 1_000_000_000L

    val videos: Flow<PagingData<MediaFileEntity>> = _filter
        .debounce(250)
        .flatMapLatest { f ->
            val built = VideoQuery.build(f, randomSeed)
            Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = false)) {
                dao.pagingVideos(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            }.flow
        }
        .cachedIn(viewModelScope)

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
