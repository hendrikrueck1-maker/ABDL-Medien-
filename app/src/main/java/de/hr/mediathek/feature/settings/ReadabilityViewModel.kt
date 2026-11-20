package de.hr.mediathek.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.ui.Readability
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Regler "Lesbarkeit" und Schalter "Automatisch je nach Bild" (wirken sofort in der ganzen App). */
@HiltViewModel
class ReadabilityViewModel @Inject constructor(private val settings: SettingDao) : ViewModel() {
    val level: StateFlow<Float> = settings.observe("ui_readability").map { it?.toFloatOrNull() ?: Readability.DEFAULT_LEVEL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Readability.DEFAULT_LEVEL)
    val auto: StateFlow<Boolean> = settings.observe("ui_readability_auto").map { it != "0" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setLevel(v: Float) { viewModelScope.launch { settings.set(SettingEntity("ui_readability", v.toString())) } }
    fun setAuto(v: Boolean) { viewModelScope.launch { settings.set(SettingEntity("ui_readability_auto", if (v) "1" else "0")) } }
}
