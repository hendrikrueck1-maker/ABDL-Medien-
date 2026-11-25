package de.hr.mediathek.feature.pkg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.AppScope
import de.hr.mediathek.core.jobs.JobScheduler
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobType
import de.hr.mediathek.data.db.PackageDao
import de.hr.mediathek.data.db.PackageRow
import de.hr.mediathek.data.db.PackageSourceRow
import de.hr.mediathek.data.db.PlaylistDao
import de.hr.mediathek.data.db.PlaylistEntity
import de.hr.mediathek.data.pkg.PackageLogic
import de.hr.mediathek.data.pkg.PackageSelectionStore
import de.hr.mediathek.data.storage.StorageRepository
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

data class PackageOptions(
    val favVideos: Boolean = true,
    val favImages: Boolean = false,
    val ratedVideos: Boolean = false,
    val ratedImages: Boolean = false,
    val minRating: Int = 4,
    val playlists: Set<String> = emptySet(),
    val unseenVideos: Boolean = false,
    val limitGb: Int = 8,
    val truncate: Boolean = false,
)

data class PackagePreview(val count: Int = 0, val bytes: Long = 0, val dropped: Int = 0, val overLimit: Boolean = false, val loading: Boolean = true)

data class PackageUiState(
    val options: PackageOptions = PackageOptions(),
    val preview: PackagePreview = PackagePreview(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val packages: List<PackageSourceRow> = emptyList(),
    val exportJob: JobEntity? = null,
    val importJob: JobEntity? = null,
    val message: String? = null,
)

@HiltViewModel
class PackageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageDao: PackageDao,
    playlistDao: PlaylistDao,
    jobDao: JobDao,
    private val scheduler: JobScheduler,
    private val selection: PackageSelectionStore,
    private val storage: StorageRepository,
) : ViewModel() {
    private val options = MutableStateFlow(PackageOptions())
    private val preview = MutableStateFlow(PackagePreview())
    private val message = MutableStateFlow<String?>(null)

    private val jobs = combine(
        jobDao.observeLatest(JobType.PACKAGE_EXPORT),
        jobDao.observeLatest(JobType.PACKAGE_IMPORT),
    ) { e, i -> e to i }

    val state: StateFlow<PackageUiState> = combine(
        options, preview, playlistDao.observeAll(), packageDao.observePackageSources(), jobs,
    ) { o, p, pl, pk, j -> PackageUiState(o, p, pl, pk, j.first, j.second, null) }
        .combine(message) { s, m -> s.copy(message = m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PackageUiState())

    init { refresh() }

    fun update(change: (PackageOptions) -> PackageOptions) {
        options.update(change)
        refresh()
    }

    fun dismissMessage() { message.value = null }

    private suspend fun collect(o: PackageOptions): List<PackageRow> {
        val all = LinkedHashMap<String, PackageRow>()
        fun add(rows: List<PackageRow>) { rows.forEach { all.putIfAbsent(it.uuid, it) } }
        if (o.favVideos) add(packageDao.favorites("VIDEO"))
        if (o.favImages) add(packageDao.favorites("IMAGE"))
        if (o.ratedVideos) add(packageDao.rated("VIDEO", o.minRating))
        if (o.ratedImages) add(packageDao.rated("IMAGE", o.minRating))
        for (p in o.playlists) add(packageDao.inPlaylist(p))
        if (o.unseenVideos) add(packageDao.unseenVideos())
        return all.values.toList()
    }

    private fun limitBytes(o: PackageOptions) = o.limitGb * PackageLogic.GB

    private fun refresh() {
        val o = options.value
        viewModelScope.launch {
            preview.value = preview.value.copy(loading = true)
            val rows = withContext(Dispatchers.IO) { collect(o) }
            val total = rows.sumOf { it.size }
            val limit = limitBytes(o)
            if (o.truncate && total > limit) {
                val (kept, dropped) = PackageLogic.truncate(rows.map { PackageLogic.Sized(it.uuid, it.size) }, limit)
                preview.value = PackagePreview(kept.size, kept.sumOf { it.size }, dropped.size, false, false)
            } else {
                preview.value = PackagePreview(rows.size, total, 0, total > limit, false)
            }
        }
    }

    /** Zielordner gewählt: Auswahl festschreiben und den Export-Job einreihen. */
    fun startExport(target: Uri) {
        val o = options.value
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            var rows = withContext(Dispatchers.IO) { collect(o) }
            if (o.truncate) rows = PackageLogic.truncate(rows.map { PackageLogic.Sized(it.uuid, it.size) }, limitBytes(o)).first
                .let { kept -> val keep = kept.map { it.uuid }.toHashSet(); rows.filter { it.uuid in keep } }
            if (rows.isEmpty()) { message.value = "Nichts ausgewählt"; return@launch }
            selection.replace(rows.map { it.uuid })
            val ok = scheduler.enqueuePackageExport(target.toString(), PackageLogic.folderName(LocalDate.now()))
            message.value = if (ok) "Export gestartet" else "Es läuft bereits ein Export"
        }
    }

    fun startImport(folder: Uri) {
        viewModelScope.launch {
            val ok = scheduler.enqueuePackageImport(folder.toString())
            message.value = if (ok) "Einlesen gestartet" else "Es läuft bereits ein Paket-Import"
        }
    }

    fun cancel(job: JobEntity) {
        viewModelScope.launch { scheduler.cancel(job.uuid) }
    }

    /**
     * Paket entkoppeln; die Einträge bleiben offline. Optional werden die Paketdateien gelöscht – nur die Dateien,
     * die im Manifest stehen, dazu Manifest und Metadaten. Fremde Dateien und Unterordner bleiben immer unangetastet.
     */
    fun removePackage(row: PackageSourceRow, deleteFiles: Boolean) {
        // Läuft auch weiter, wenn der Bildschirm verlassen wird (halb gelöschte Pakete vermeiden).
        AppScope.launch {
            var note = ""
            if (deleteFiles) {
                val tree = Uri.parse(row.treeUri)
                note = withContext(Dispatchers.IO) { runCatching { deletePackageFiles(tree) }.getOrElse { " – Dateien konnten nicht gelöscht werden" } }
            }
            storage.removeSource(row.uuid, removeEntries = false)
            message.value = "Paket entfernt$note"
        }
    }

    private fun deletePackageFiles(tree: Uri): String {
        val resolver = context.contentResolver
        val root = DocumentsContract.getTreeDocumentId(tree)
        val top = SafOps.children(resolver, tree, root)
        val manifestDoc = top.firstOrNull { !it.isDir && it.name == PackageLogic.MANIFEST_NAME }
            ?: return " – kein Manifest gefunden, es wurden keine Dateien gelöscht"
        val manifest = PackageLogic.decode(
            PackageLogic.readLimited(resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, manifestDoc.docId))!!),
        )
        val plan = PackageLogic.deletionPlan(manifest)
        var deleted = 0
        // Dateien in videos/ und bilder/: nur Namen aus dem Plan.
        for (dir in listOf(PackageLogic.DIR_VIDEOS, PackageLogic.DIR_IMAGES)) {
            val dirDoc = top.firstOrNull { it.isDir && it.name == dir } ?: continue
            for (c in SafOps.children(resolver, tree, dirDoc.docId)) {
                if (!c.isDir && "$dir/${c.name}" in plan && SafOps.delete(resolver, tree, c.docId)) deleted++
            }
            // Ordner nur löschen, wenn er jetzt leer ist.
            if (SafOps.children(resolver, tree, dirDoc.docId).isEmpty()) SafOps.delete(resolver, tree, dirDoc.docId)
        }
        // Metadaten zuerst, Manifest zuletzt (ohne Manifest ist das Paket als unvollständig erkennbar).
        top.firstOrNull { !it.isDir && it.name == PackageLogic.METADATA_NAME }?.let { if (SafOps.delete(resolver, tree, it.docId)) deleted++ }
        if (SafOps.delete(resolver, tree, manifestDoc.docId)) deleted++
        return " – $deleted Paketdateien gelöscht"
    }
}
