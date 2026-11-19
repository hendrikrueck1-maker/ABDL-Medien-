package de.hr.mediathek.feature.library

import androidx.sqlite.db.SimpleSQLiteQuery
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.prefs.BrowseMode
import de.hr.mediathek.data.prefs.CardLayout
import de.hr.mediathek.data.prefs.ViewPrefsRepository
import de.hr.mediathek.data.prefs.ViewSettings
import de.hr.mediathek.data.thumb.ThumbnailCache
import de.hr.mediathek.feature.videos.VideoFilter
import de.hr.mediathek.feature.videos.VideoQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Unterordner der aktuellen Ebene. [path] ist der volle Pfad ab der Quellwurzel. */
data class FolderItem(val name: String, val path: String, val count: Int)

/**
 * Gemeinsame Ordnerlogik für Videos, Bilder und "Alle Medien": aktueller Pfad, Ansichtseinstellungen
 * und die daraus abgeleiteten Filter und Ordnerlisten. Gehört einem ViewModel und läuft in dessen Scope.
 *
 * [type] ist "VIDEO", "IMAGE" oder "ALL".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBrowser(
    private val scope: CoroutineScope,
    private val dao: MediaFileDao,
    private val thumbs: ThumbnailCache,
    private val prefs: ViewPrefsRepository,
    val type: String,
    private val module: String,
    defaults: ViewSettings = ViewSettings(),
) {
    private val _path = MutableStateFlow("")
    val path: StateFlow<String> = _path

    val settings: StateFlow<ViewSettings> =
        prefs.observe(module, defaults).stateIn(scope, SharingStarted.Eagerly, defaults)

    fun enter(name: String) = _path.update { if (it.isEmpty()) name else "$it/$name" }
    fun goTo(path: String) { _path.value = path }

    /** Eine Ebene höher. Gibt false zurück, wenn man schon ganz oben ist. */
    fun up(): Boolean {
        val current = _path.value
        if (current.isEmpty()) return false
        _path.value = current.substringBeforeLast('/', "")
        return true
    }

    fun setMode(mode: BrowseMode) { scope.launch { prefs.setMode(module, mode) } }
    fun setLayout(layout: CardLayout) { scope.launch { prefs.setLayout(module, layout) } }
    fun setShowDetails(value: Boolean) { scope.launch { prefs.setShowDetails(module, value) } }

    private fun foldersActive(filter: VideoFilter, mode: BrowseMode): Boolean =
        mode == BrowseMode.FOLDERS && filter.search.isBlank() && filter.playlistUuid == null

    private val mode: Flow<BrowseMode> = settings.map { it.mode }.distinctUntilChanged()

    /** Der Filter, mit dem die Dateiliste tatsächlich abgefragt wird (in der Ordneransicht nur die aktuelle Ebene). */
    fun effective(filter: Flow<VideoFilter>): Flow<VideoFilter> =
        combine(filter, _path, mode) { f, p, m -> if (foldersActive(f, m)) f.copy(directFolder = p) else f }

    /** Wie [effective], aber für den aktuellen Zustand (beim Öffnen eines Eintrags). */
    fun effectiveNow(f: VideoFilter): VideoFilter =
        if (foldersActive(f, settings.value.mode)) f.copy(directFolder = _path.value) else f

    /** Für "Zufall": in einer Ordneransicht der aktuelle Ordner samt Unterordnern, sonst alles. */
    fun scopeNow(f: VideoFilter): VideoFilter =
        if (foldersActive(f, settings.value.mode) && _path.value.isNotEmpty()) f.copy(folder = _path.value) else f

    /** Unterordner der aktuellen Ebene; in der Ansicht "Alle" oder bei aktiver Suche leer. */
    fun folders(filter: Flow<VideoFilter>): Flow<List<FolderItem>> =
        combine(filter, _path, mode) { f, p, m -> Triple(f, p, m) }.flatMapLatest { (f, p, m) ->
            if (!foldersActive(f, m)) {
                flowOf(emptyList())
            } else {
                val built = VideoQuery.buildFolderRows(f, type, p)
                dao.observeFolderRows(SimpleSQLiteQuery(built.sql, built.args.toTypedArray())).map { rows ->
                    rows.map { FolderItem(it.name, if (p.isEmpty()) it.name else "$p/${it.name}", it.cnt) }
                }
            }
        }

    /** Bis zu vier vorhandene Vorschaubilder für die Ordner-Collage. */
    suspend fun preview(filter: VideoFilter, folderPath: String): List<File> = withContext(Dispatchers.IO) {
        val built = VideoQuery.buildFolderPreview(filter, type, folderPath)
        dao.uuids(SimpleSQLiteQuery(built.sql, built.args.toTypedArray())).mapNotNull { thumbFile(it) }.take(4)
    }

    fun thumbFile(uuid: String): File? =
        thumbs.fileIfPresent(uuid, ThumbVariant.NORMAL) ?: thumbs.fileIfPresent(uuid, ThumbVariant.SMALL)
}
