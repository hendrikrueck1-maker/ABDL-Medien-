package de.hr.mediathek.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.core.update.LatestRelease
import de.hr.mediathek.core.update.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: LatestRelease) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                val latest = repository.fetchLatest()
                when {
                    latest == null -> UpdateState.Error("Kein Release mit APK gefunden")
                    latest.build > BuildConfig.RUN_NUMBER -> UpdateState.Available(latest)
                    else -> UpdateState.UpToDate
                }
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Prüfung fehlgeschlagen")
            }
        }
    }

    fun download(release: LatestRelease) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(0)
        viewModelScope.launch {
            _state.value = try {
                val file = repository.download(release) { percent ->
                    _state.value = UpdateState.Downloading(percent)
                }
                UpdateState.Ready(file)
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Download fehlgeschlagen")
            }
        }
    }
}
