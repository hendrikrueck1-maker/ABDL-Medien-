package de.hr.mediathek.feature.images

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
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
    savedState: SavedStateHandle,
    private val settingDao: SettingDao,
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

    /** Gesetzt, wenn die Diashow aus der Bildansicht gestartet wurde: dann geht es ohne Einrichtung gleich los. */
    private val startUuid: String? = savedState.get<String>("start")?.takeIf { it.isNotBlank() }
    val fromViewer: Boolean = startUuid != null

    init {
        viewModelScope.launch {
            // Die zuletzt gewählten Einstellungen bleiben erhalten.
            val interval = settingDao.get(K_INTERVAL)?.toIntOrNull()?.takeIf { it > 0 } ?: 5
            val crossfade = settingDao.get(K_CROSSFADE)?.let { it == "1" } ?: true
            val random = settingDao.get(K_RANDOM)?.let { it == "1" } ?: true
            val built = VideoQuery.buildOrderedUuids(context.filter, context.seed, type = "IMAGE")
            uuids = dao.uuids(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
            _state.update {
                it.copy(loading = false, total = uuids.size, intervalSec = interval, crossfade = crossfade, random = random)
            }
            if (startUuid != null && uuids.isNotEmpty()) start(uuids.indexOf(startUuid).coerceAtLeast(0))
        }
    }

    fun setInterval(seconds: Int) {
        _state.update { it.copy(intervalSec = seconds) }
        viewModelScope.launch { settingDao.set(SettingEntity(K_INTERVAL, seconds.toString())) }
    }

    fun setCrossfade(on: Boolean) {
        _state.update { it.copy(crossfade = on) }
        viewModelScope.launch { settingDao.set(SettingEntity(K_CROSSFADE, if (on) "1" else "0")) }
    }

    fun setRandom(on: Boolean) {
        _state.update { it.copy(random = on) }
        viewModelScope.launch { settingDao.set(SettingEntity(K_RANDOM, if (on) "1" else "0")) }
    }

    fun start(startIndex: Int = 0) {
        if (uuids.isEmpty()) return
        val o = SlideshowOrder(uuids.size, _state.value.random, startIndex)
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

    private companion object {
        const val K_INTERVAL = "slideshow_interval"
        const val K_CROSSFADE = "slideshow_crossfade"
        const val K_RANDOM = "slideshow_random"
    }
}
