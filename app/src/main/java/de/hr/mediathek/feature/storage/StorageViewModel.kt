package de.hr.mediathek.feature.storage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.SourceEntity
import de.hr.mediathek.data.storage.AddSourceResult
import de.hr.mediathek.data.storage.StorageRepository
import de.hr.mediathek.data.storage.WorkFolderResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StorageMessage { SOURCE_ADDED, SOURCE_EXISTS, SOURCE_FAILED, WORK_FOLDER_SET, WORK_FOLDER_REJECTED, WORK_FOLDER_FAILED }

data class SourceItem(val source: SourceEntity, val reachable: Boolean?)

data class StorageUiState(
    val sources: List<SourceItem> = emptyList(),
    val workFolderLabel: String? = null,
    val message: StorageMessage? = null,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val repository: StorageRepository,
) : ViewModel() {

    private val reachability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val message = MutableStateFlow<StorageMessage?>(null)

    /** Zeigt den Hinweis zur SD-Karten-Auswahl, bis er einmal bestätigt wurde. */
    val onboardingSeen: StateFlow<Boolean> = repository.observeOnboardingSeen()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val state: StateFlow<StorageUiState> = combine(
        repository.observeSources(),
        repository.observeWorkFolderLabel(),
        reachability,
        message,
    ) { sources, workLabel, reach, msg ->
        StorageUiState(
            sources = sources.map { SourceItem(it, reach[it.uuid]) },
            workFolderLabel = workLabel,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    init {
        refreshStatus()
    }

    fun confirmOnboarding() {
        viewModelScope.launch { repository.markOnboardingSeen() }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val sources = repository.getSources()
            val result = HashMap<String, Boolean>()
            for (source in sources) {
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

    fun dismissMessage() {
        message.value = null
    }
}
