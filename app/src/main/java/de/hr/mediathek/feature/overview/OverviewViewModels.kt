package de.hr.mediathek.feature.overview

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.backup.BackupStore
import de.hr.mediathek.data.db.ErrorLogDao
import de.hr.mediathek.data.db.MediaStats
import de.hr.mediathek.data.db.ProductStats
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.StatsDao
import de.hr.mediathek.data.db.StoryStats
import de.hr.mediathek.data.stats.Redactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OverallStats(
    val videos: MediaStats, val images: MediaStats, val stories: StoryStats, val products: ProductStats, val trackerEntries: Int,
)

@HiltViewModel
class OverallStatsViewModel @Inject constructor(private val dao: StatsDao) : ViewModel() {
    private val _state = MutableStateFlow<OverallStats?>(null)
    val state: StateFlow<OverallStats?> = _state

    init {
        viewModelScope.launch {
            _state.value = OverallStats(dao.media("VIDEO"), dao.media("IMAGE"), dao.stories(), dao.products(), dao.trackerEntries())
        }
    }
}

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val settings: SettingDao,
    private val errors: ErrorLogDao,
) : ViewModel() {
    val secureScreen: StateFlow<Boolean> = settings.observe(KEY_SECURE).map { it == "1" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _preview = MutableStateFlow<List<String>?>(null)
    val preview: StateFlow<List<String>?> = _preview

    fun setSecure(on: Boolean) {
        viewModelScope.launch { settings.set(SettingEntity(KEY_SECURE, if (on) "1" else "0")) }
    }

    fun loadPreview() {
        viewModelScope.launch {
            _preview.value = errors.getAll().take(20).map { Redactor.redact("[${it.type}] ${it.message}") + if (it.count > 1) " (×${it.count})" else "" }
        }
    }

    fun hidePreview() { _preview.value = null }

    companion object { const val KEY_SECURE = "privacy_secure_screen" }
}

data class BackgroundState(val lastBackup: Long?, val batteryExempt: Boolean)

@HiltViewModel
class BackgroundViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupStore: BackupStore,
) : ViewModel() {
    private val _state = MutableStateFlow(BackgroundState(null, false))
    val state: StateFlow<BackgroundState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            _state.value = BackgroundState(backupStore.lastSuccessAt(), pm.isIgnoringBatteryOptimizations(context.packageName))
        }
    }
}
