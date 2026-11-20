package de.hr.mediathek.feature.tracker

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.ProductDao
import de.hr.mediathek.data.product.ProductPhotoStore
import de.hr.mediathek.data.product.ProductRepository
import de.hr.mediathek.data.tracker.EntryView
import de.hr.mediathek.data.tracker.TrackerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

class Tile(val uuid: String, val title: String, val favorite: Boolean, val brandUuid: String?, val brandName: String?)

data class TrackerFilter(val query: String = "", val brandUuid: String? = null, val showAll: Boolean = false)

@HiltViewModel
class TrackerViewModel @Inject constructor(
    private val tracker: TrackerRepository,
    private val products: ProductRepository,
    private val productDao: ProductDao,
    private val photoStore: ProductPhotoStore,
) : ViewModel() {
    private val _filter = MutableStateFlow(TrackerFilter())
    val filter: StateFlow<TrackerFilter> = _filter

    val entries: StateFlow<List<EntryView>> = tracker.observeViews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val brands = products.observeBrands().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reihenfolge: Favoriten, zuletzt genutzt, alle übrigen. */
    val tiles: StateFlow<List<Tile>> = combine(products.observeRows(), brands, entries, _filter) { rows, brands, entries, f ->
        val brandName = brands.associate { it.uuid to it.name }
        val all = rows.map { Tile(it.uuid, it.title.ifBlank { "Ohne Titel" }, it.favorite, it.brandUuid, it.brandUuid?.let { b -> brandName[b] }) }
        val recentOrder = entries.mapNotNull { it.productUuid }.distinct()
        val rank = HashMap<String, Int>().also { m -> recentOrder.forEachIndexed { i, id -> m[id] = i } }
        val q = f.query.trim().lowercase()
        all.filter { (f.brandUuid == null || it.brandUuid == f.brandUuid) && (q.isEmpty() || it.title.lowercase().contains(q)) }
            .sortedWith(compareBy<Tile> { !it.favorite }.thenBy { rank[it.uuid] ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Zuletzt angelegter Eintrag, für "Rückgängig". */
    private val _undo = MutableStateFlow<Pair<String, String>?>(null)
    val undo: StateFlow<Pair<String, String>?> = _undo

    private val locks = HashMap<String, Long>()
    private val photoPaths by lazy(LazyThreadSafetyMode.NONE) { HashMap<String, String?>() }

    fun update(block: (TrackerFilter) -> TrackerFilter) = _filter.update(block)

    /** Ein Tipp = ein Eintrag; pro Kachel gilt eine kurze Sperre gegen Doppeleinträge. */
    fun tap(tile: Tile, note: String? = null) {
        val now = System.currentTimeMillis()
        if (now - (locks[tile.uuid] ?: 0L) < LOCK_MS) return
        locks[tile.uuid] = now
        viewModelScope.launch {
            val id = tracker.add(tile.uuid, note) ?: return@launch
            _undo.value = id to tile.title
            delay(UNDO_MS)
            if (_undo.value?.first == id) _undo.value = null
        }
    }

    fun undoLast() {
        val u = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch { tracker.undo(u.first) }
    }

    fun dismissUndo() { _undo.value = null }

    fun edit(entryUuid: String, instant: Long, productUuid: String?, note: String?) {
        viewModelScope.launch { tracker.edit(entryUuid, instant, productUuid, note) }
    }

    fun delete(entryUuid: String) { viewModelScope.launch { tracker.delete(entryUuid) } }

    /** Verpackungsfoto einer Kachel (klein), oder null. */
    suspend fun photoOf(productUuid: String): Bitmap? {
        if (photoPaths.isEmpty()) {
            for (p in productDao.allPackagePhotos()) photoPaths.putIfAbsent(p.productUuid, p.filePath)
            photoPaths.putIfAbsent("", null)
        }
        val path = photoPaths[productUuid] ?: return null
        return photoStore.load(path, 240)
    }

    private companion object {
        const val LOCK_MS = 2_000L
        const val UNDO_MS = 8_000L
    }
}
