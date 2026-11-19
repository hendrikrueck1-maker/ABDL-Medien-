package de.hr.mediathek.feature.storage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.jobs.JobRunner
import de.hr.mediathek.core.jobs.JobScheduler
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.data.db.SourceCount
import de.hr.mediathek.data.db.SourceEntity
import de.hr.mediathek.data.storage.AddSourceResult
import de.hr.mediathek.data.storage.StorageRepository
import de.hr.mediathek.data.storage.WorkFolderResult
import de.hr.mediathek.data.thumb.ThumbnailCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

enum class StorageMessage {
    SOURCE_ADDED, SOURCE_EXISTS, SOURCE_FAILED,
    WORK_FOLDER_SET, WORK_FOLDER_REJECTED, WORK_FOLDER_FAILED,
    SCAN_ALREADY_ACTIVE,
}

/** Der Scan wartet auf die Entscheidung des Nutzers, weil auffällig viele Dateien fehlen. */
data class ConfirmInfo(val jobUuid: String, val missing: Int, val total: Int)

data class SourceItem(
    val source: SourceEntity,
    val reachable: Boolean?,
    val job: JobEntity?,
    val total: Int,
    val offline: Int,
    val confirm: ConfirmInfo?,
)

data class ThumbUiState(
    val count: Int = 0,
    val bytes: Long = 0,
    val full: Boolean = false,
    val lowSpace: Boolean = false,
)

data class StorageUiState(
    val sources: List<SourceItem> = emptyList(),
    val workFolderLabel: String? = null,
    val message: StorageMessage? = null,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val repository: StorageRepository,
    private val scheduler: JobScheduler,
    private val thumbCache: ThumbnailCache,
) : ViewModel() {

    private val reachability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val message = MutableStateFlow<StorageMessage?>(null)

    /** Zeigt den Hinweis zur SD-Karten-Auswahl, bis er einmal bestätigt wurde. */
    val onboardingSeen: StateFlow<Boolean> = repository.observeOnboardingSeen()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val base = combine(
        repository.observeSources(),
        repository.observeWorkFolderLabel(),
        reachability,
        message,
    ) { sources, workLabel, reach, msg -> Base(sources, workLabel, reach, msg) }

    val state: StateFlow<StorageUiState> = combine(
        base,
        repository.observeScanJobs(),
        repository.observeCounts(),
    ) { b, jobs, counts ->
        StorageUiState(
            sources = b.sources.map { source -> toItem(source, b.reach[source.uuid], jobs, counts) },
            workFolderLabel = b.workLabel,
            message = b.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    val thumbs: StateFlow<ThumbUiState> = combine(
        thumbCache.observeStats(),
        thumbCache.observeFull(),
        thumbCache.observeLowSpace(),
    ) { stats, full, low -> ThumbUiState(stats.count, stats.bytes, full, low) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThumbUiState())

    fun generateThumbnails() {
        viewModelScope.launch { scheduler.enqueueThumbnails() }
    }

    fun clearThumbnails() {
        viewModelScope.launch { thumbCache.clearAll() }
    }

    init {
        refreshStatus()
    }

    fun confirmOnboarding() {
        viewModelScope.launch { repository.markOnboardingSeen() }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val result = HashMap<String, Boolean>()
            for (source in repository.getSources()) {
                val ok = repository.isReachable(source)
                result[source.uuid] = ok
                if (ok) repository.markMounted(source)
            }
            reachability.update { result }
        }
    }

    fun addSource(uri: Uri) {
        viewModelScope.launch {
            message.value = when (repository.addMediaSource(uri)) {
                is AddSourceResult.Added -> StorageMessage.SOURCE_ADDED
                AddSourceResult.AlreadyExists -> StorageMessage.SOURCE_EXISTS
                is AddSourceResult.Failed -> StorageMessage.SOURCE_FAILED
            }
            refreshStatus()
        }
    }

    fun setWorkFolder(uri: Uri) {
        viewModelScope.launch {
            message.value = when (repository.setWorkFolder(uri)) {
                WorkFolderResult.Set -> StorageMessage.WORK_FOLDER_SET
                WorkFolderResult.Rejected -> StorageMessage.WORK_FOLDER_REJECTED
                is WorkFolderResult.Failed -> StorageMessage.WORK_FOLDER_FAILED
            }
        }
    }

    fun scan(source: SourceEntity) {
        viewModelScope.launch {
            if (!scheduler.enqueueScan(source.uuid)) message.value = StorageMessage.SCAN_ALREADY_ACTIVE
        }
    }

    fun cancelJob(jobUuid: String) {
        viewModelScope.launch { scheduler.cancel(jobUuid) }
    }

    fun confirmMissing(jobUuid: String) {
        viewModelScope.launch { scheduler.confirmMissing(jobUuid) }
    }

    fun dismissConfirmation(jobUuid: String) {
        viewModelScope.launch { scheduler.dismissConfirmation(jobUuid) }
    }

    fun dismissMessage() {
        message.value = null
    }

    private data class Base(
        val sources: List<SourceEntity>,
        val workLabel: String?,
        val reach: Map<String, Boolean>,
        val message: StorageMessage?,
    )

    private fun toItem(
        source: SourceEntity,
        reachable: Boolean?,
        jobs: List<JobEntity>,
        counts: List<SourceCount>,
    ): SourceItem {
        // Die Liste ist nach Erstellung absteigend sortiert: der erste Treffer ist der neueste Scan dieser Quelle.
        val job = jobs.firstOrNull { sourceUuidOf(it) == source.uuid }
        val count = counts.firstOrNull { it.sourceUuid == source.uuid }
        val confirm = if (job != null && job.status == JobStatus.PAUSED && job.phase == JobRunner.PHASE_CONFIRM_MISSING) {
            val params = runCatching { JSONObject(job.paramsJson) }.getOrNull()
            ConfirmInfo(job.uuid, params?.optInt("missing") ?: 0, params?.optInt("total") ?: 0)
        } else {
            null
        }
        return SourceItem(source, reachable, job, count?.total ?: 0, count?.offline ?: 0, confirm)
    }

    private fun sourceUuidOf(job: JobEntity): String? =
        runCatching { JSONObject(job.paramsJson).getString("sourceUuid") }.getOrNull()
}
