package de.hr.mediathek.feature.tracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.tracker.Bucket
import de.hr.mediathek.data.tracker.EntryView
import de.hr.mediathek.data.tracker.Slice
import de.hr.mediathek.data.tracker.StatPeriod
import de.hr.mediathek.data.tracker.TrackerRepository
import de.hr.mediathek.data.tracker.TrackerStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class RangeFilter(val label: String, val days: Long?) { D14("14 Tage", 14), D30("30 Tage", 30), D90("90 Tage", 90), YEAR("1 Jahr", 365), ALL("Alles", null) }

data class StatsFilter(
    val period: StatPeriod = StatPeriod.DAY,
    val range: RangeFilter = RangeFilter.D30,
    val productName: String? = null,
    val byBrand: Boolean = false,
)

class StatsState(
    val buckets: List<Bucket> = emptyList(),
    val total: Int = 0,
    val average: Double = 0.0,
    val distribution: List<Slice> = emptyList(),
    val totalCost: Double = 0.0,
    val costsByProduct: List<Pair<String, Double>> = emptyList(),
    val withoutPrice: Int = 0,
    val productNames: List<String> = emptyList(),
    val dayStartMinutes: Int = TrackerStats.DEFAULT_DAY_START_MINUTES,
)

@HiltViewModel
class TrackerStatsViewModel @Inject constructor(private val tracker: TrackerRepository) : ViewModel() {
    private val _filter = MutableStateFlow(StatsFilter())
    val filter: StateFlow<StatsFilter> = _filter

    val state: StateFlow<StatsState> = combine(tracker.observeViews(), tracker.observeDayStart(), _filter) { all, dayStart, f ->
        val today = LocalDate.now()
        val names = all.map { it.name }.distinct().sorted()
        val limit: LocalDate? = f.range.days?.let { today.minusDays(it) }
        val filtered = all.filter { e ->
            (f.productName == null || e.name == f.productName) &&
                (limit == null || !TrackerStats.dayOf(e.instant, e.zoneId, dayStart).isBefore(limit))
        }
        val buckets = TrackerStats.buckets(filtered, f.period, dayStart, today)
        val nonEmpty = buckets.size.coerceAtLeast(1)
        StatsState(
            buckets = buckets, total = filtered.size, average = filtered.size.toDouble() / nonEmpty,
            distribution = TrackerStats.distribution(filtered, f.byBrand),
            totalCost = filtered.sumOf { it.unitPrice ?: 0.0 }, costsByProduct = TrackerStats.costsByProduct(filtered),
            withoutPrice = TrackerStats.withoutPrice(filtered), productNames = names, dayStartMinutes = dayStart,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    fun update(block: (StatsFilter) -> StatsFilter) = _filter.update(block)
    fun setDayStart(minutes: Int) { viewModelScope.launch { tracker.setDayStart(minutes) } }
}
