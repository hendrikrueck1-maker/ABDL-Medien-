package de.hr.mediathek.feature.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.compress.CompressionLevel
import de.hr.mediathek.data.compress.CompressionPlan
import de.hr.mediathek.data.compress.CompressionRepository
import de.hr.mediathek.data.db.GraceStats
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlanState {
    data object Loading : PlanState
    data object Unavailable : PlanState
    data class Ready(val plan: CompressionPlan) : PlanState
}

data class QueueItem(
    val jobUuid: String,
    val name: String,
    val running: Boolean,
    val phase: String?,
    val progress: Float?,
)

sealed interface TestState {
    data class Running(val progress: Float) : TestState
    data class Ready(val result: de.hr.mediathek.data.compress.TestResult, val level: CompressionLevel) : TestState
    data class Failed(val reason: String? = null) : TestState
}

@HiltViewModel
class CompressionViewModel @Inject constructor(
    private val repo: CompressionRepository,
    private val scheduler: de.hr.mediathek.core.jobs.JobScheduler,
    private val settings: de.hr.mediathek.data.compress.CompressionSettings,
    private val mediaDao: de.hr.mediathek.data.db.MediaFileDao,
    jobDao: JobDao,
) : ViewModel() {
    private val _plan = MutableStateFlow<PlanState?>(null)
    val plan: StateFlow<PlanState?> = _plan

    /** null = keine Rückmeldung offen; true = eingereiht, false = für dieses Video läuft schon eine. */
    private val _queued = MutableStateFlow<Boolean?>(null)
    val queued: StateFlow<Boolean?> = _queued

    val grace: StateFlow<GraceStats> = repo.observeGrace()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GraceStats(0, 0))
    val blockedCount: StateFlow<Int> = repo.observeBlocked().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val latestJob: StateFlow<JobEntity?> = jobDao.observeLatest(JobType.COMPRESS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _purged = MutableStateFlow<Int?>(null)
    val purged: StateFlow<Int?> = _purged

    // --- Warteschlange ---

    val paused: StateFlow<Boolean> = settings.observePaused()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val onlyCharging: StateFlow<Boolean> = settings.observeOnlyCharging()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val keepOriginals: StateFlow<Boolean> = settings.observeKeepOriginals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setKeepOriginals(value: Boolean) {
        viewModelScope.launch { settings.setKeepOriginals(value) }
    }

    val queue: StateFlow<List<QueueItem>> = jobDao.observeCompressQueue().map { jobs ->
        jobs.map { j ->
            val uuid = runCatching { de.hr.mediathek.data.compress.CompressionParams.parse(j.paramsJson).mediaUuid }.getOrNull()
            QueueItem(
                jobUuid = j.uuid,
                name = uuid?.let { mediaDao.getByUuid(it)?.displayName } ?: "",
                running = j.status == de.hr.mediathek.data.db.JobStatus.RUNNING,
                phase = j.phase,
                progress = j.progress,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPaused(value: Boolean) {
        viewModelScope.launch { scheduler.setCompressPaused(value) }
    }

    fun setOnlyCharging(value: Boolean) {
        viewModelScope.launch { scheduler.setCompressOnlyCharging(value) }
    }

    fun move(jobUuid: String, up: Boolean) {
        viewModelScope.launch { repo.move(jobUuid, up) }
    }

    fun cancel(jobUuid: String) {
        viewModelScope.launch { scheduler.cancel(jobUuid) }
    }

    // --- Testlauf und Serie ---

    private val _test = MutableStateFlow<TestState?>(null)
    val test: StateFlow<TestState?> = _test
    private var testJob: kotlinx.coroutines.Job? = null

    private val _series = MutableStateFlow<List<String>?>(null)
    val series: StateFlow<List<String>?> = _series

    fun beginSeries(mediaUuids: List<String>) {
        clearTest()
        _series.value = mediaUuids
    }

    fun dismissSeries() {
        clearTest()
        _series.value = null
    }

    /** Pflicht vor einer Serie: etwa 20 Sekunden mit der gewählten Stufe kodieren. */
    fun runTest(mediaUuid: String, level: CompressionLevel) {
        testJob?.cancel()
        _test.value = TestState.Running(0f)
        testJob = viewModelScope.launch {
            try {
                val result = repo.runTest(mediaUuid, level) { f -> _test.value = TestState.Running(f) }
                _test.value = if (result != null) TestState.Ready(result, level) else TestState.Failed()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                _test.value = TestState.Failed(e.message)
            }
        }
    }

    fun clearTest() {
        testJob?.cancel()
        _test.value = null
        repo.clearTest()
    }

    /** Ergebnis der letzten Serie: eingereiht und insgesamt; null = keine Rückmeldung offen. */
    private val _seriesResult = MutableStateFlow<Pair<Int, Int>?>(null)
    val seriesResult: StateFlow<Pair<Int, Int>?> = _seriesResult

    fun dismissSeriesResult() {
        _seriesResult.value = null
    }

    fun startSeries(level: CompressionLevel) {
        val uuids = _series.value ?: return
        clearTest()
        _series.value = null
        viewModelScope.launch { _seriesResult.value = repo.enqueueAll(uuids, level) to uuids.size }
    }

    override fun onCleared() {
        repo.clearTest()
    }

    fun load(mediaUuid: String) {
        _plan.value = PlanState.Loading
        viewModelScope.launch {
            val p = repo.analyze(mediaUuid)
            _plan.value = if (p != null) PlanState.Ready(p) else PlanState.Unavailable
        }
    }

    fun dismissPlan() {
        _plan.value = null
    }

    fun start(mediaUuid: String, level: CompressionLevel, confirmed: Boolean) {
        _plan.value = null
        viewModelScope.launch { _queued.value = repo.enqueue(mediaUuid, level, confirmed) }
    }

    fun dismissQueued() {
        _queued.value = null
    }

    fun purgeNow() {
        viewModelScope.launch { _purged.value = repo.purgeAllNow() }
    }

    fun dismissPurged() {
        _purged.value = null
    }
}
