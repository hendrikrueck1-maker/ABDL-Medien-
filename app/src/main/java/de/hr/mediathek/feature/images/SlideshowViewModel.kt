package de.hr.mediathek.feature.images

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.images.ImageLoader
import de.hr.mediathek.data.images.SlideshowOrder
import de.hr.mediathek.feature.videos.VideoQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SlideshowState(
    val loading: Boolean = true,
    val total: Int = 0,
    val started: Boolean = false,
    val intervalSec: Int = 5,
    val crossfade: Boolean = true,
    val random: Boolean = true,
    val paused: Boolean = false,
    val currentUuid: String? = null,
    /** Zähler, der bei jedem Bildwechsel steigt; damit startet der Wechsel-Timer auch bei gleichem Bild neu. */
    val tick: Int = 0,
)

@HiltViewModel
class SlideshowViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val sources: SourceDao,
    private val context: ImageContext,
    val loader: ImageLoader,
) : ViewModel() {
    private val _state = MutableStateFlow(SlideshowState())
    val state: StateFlow<SlideshowState> = _state

    private var uuids: List<String> = emptyList()
    private var order: SlideshowOrder? = null
    private val trees = HashMap<String, Uri?>()

    init {
        viewModelScope.launch {
            val built = VideoQuery.buildOrderedUuids(context.filter, context.seed, type = "IMAGE")
            uuids = dao.uuids(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            _state.update { it.copy(loading = false, total = uuids.size) }
        }
    }

    fun setInterval(seconds: Int) = _state.update { it.copy(intervalSec = seconds) }
    fun setCrossfade(on: Boolean) = _state.update { it.copy(crossfade = on) }
    fun setRandom(on: Boolean) = _state.update { it.copy(random = on) }

    fun start() {
        if (uuids.isEmpty()) return
        val o = SlideshowOrder(uuids.size, _state.value.random)
        order = o
        _state.update { it.copy(started = true, paused = false, currentUuid = uuids[o.current], tick = it.tick + 1) }
    }

    fun stop() {
        order = null
        _state.update { it.copy(started = false, currentUuid = null, paused = false) }
    }

    fun next() = move { it.next() }
    fun previous() = move { it.previous() }

    fun togglePause() = _state.update { it.copy(paused = !it.paused) }

    private fun move(step: (SlideshowOrder) -> Int) {
        val o = order ?: return
        val index = step(o)
        _state.update { it.copy(currentUuid = uuids[index], tick = it.tick + 1) }
    }

    /** Adresse eines Bildes der Diashow; null, wenn Datei oder Quelle nicht verfügbar ist. */
    suspend fun uriOf(uuid: String): Uri? {
        val media = dao.getByUuid(uuid) ?: return null
        if (media.status != de.hr.mediathek.data.db.MediaStatus.ONLINE) return null
        val docId = media.documentIdCache ?: return null
        val tree = trees.getOrPut(media.sourceUuid) { sources.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } }
            ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }
}
