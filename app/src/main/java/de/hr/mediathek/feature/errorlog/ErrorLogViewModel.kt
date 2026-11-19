package de.hr.mediathek.feature.errorlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.data.db.ErrorLogDao
import de.hr.mediathek.data.db.ErrorLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ErrorLogViewModel @Inject constructor(
    dao: ErrorLogDao,
    private val logger: ErrorLogger,
) : ViewModel() {

    val entries: StateFlow<List<ErrorLogEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() {
        viewModelScope.launch { logger.clear() }
    }
}
