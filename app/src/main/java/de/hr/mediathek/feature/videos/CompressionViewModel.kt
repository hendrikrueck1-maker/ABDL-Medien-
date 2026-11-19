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

@HiltViewModel
class CompressionViewModel @Inject constructor(
    private val repo: CompressionRepository,
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
