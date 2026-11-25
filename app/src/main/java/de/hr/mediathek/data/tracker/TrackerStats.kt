package de.hr.mediathek.data.tracker

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class StatPeriod { DAY, WEEK, MONTH }

/** Ein Eintrag mit den Angaben, die die Statistik braucht. */
class EntryView(
    val uuid: String,
    val instant: Long,
    val zoneId: String,
    val productUuid: String?,
    val name: String,
    val brand: String?,
    val unitPrice: Double?,
    val note: String?,
)

class Slice(val label: String, val count: Int, val productUuid: String?)

class Bucket(val start: LocalDate, val count: Int, val cost: Double)

object TrackerStats {
    const val DEFAULT_DAY_START_MINUTES = 6 * 60

    private fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

    /** Kalendertag eines Eintrags: Einträge zwischen Mitternacht und Tagesbeginn zählen zum Vortag. */
    fun dayOf(instant: Long, zoneId: String, dayStartMinutes: Int): LocalDate =
        // Auf der lokalen Uhrzeit rechnen (nicht auf der Zeitachse), sonst verschiebt die Zeitumstellung den Tagesbeginn.
        Instant.ofEpochMilli(instant).atZone(zone(zoneId)).toLocalDateTime().minusMinutes(dayStartMinutes.toLong()).toLocalDate()

    fun bucketStart(date: LocalDate, period: StatPeriod): LocalDate = when (period) {
        StatPeriod.DAY -> date
        StatPeriod.WEEK -> date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        StatPeriod.MONTH -> date.withDayOfMonth(1)
    }

    private fun next(date: LocalDate, period: StatPeriod): LocalDate = when (period) {
        StatPeriod.DAY -> date.plusDays(1)
        StatPeriod.WEEK -> date.plusWeeks(1)
        StatPeriod.MONTH -> date.plusMonths(1)
    }

    /** Anzahl und Kosten je Zeitraum, lückenlos von der ersten Anzahl bis [today]. */
    fun buckets(entries: List<EntryView>, period: StatPeriod, dayStartMinutes: Int, today: LocalDate): List<Bucket> {
        if (entries.isEmpty()) return emptyList()
        val counts = HashMap<LocalDate, Int>()
        val costs = HashMap<LocalDate, Double>()
        for (e in entries) {
            val key = bucketStart(dayOf(e.instant, e.zoneId, dayStartMinutes), period)
            counts[key] = (counts[key] ?: 0) + 1
            if (e.unitPrice != null) costs[key] = (costs[key] ?: 0.0) + e.unitPrice
        }
        val first = counts.keys.min()
        val last = maxOf(counts.keys.max(), bucketStart(today, period))
        val result = ArrayList<Bucket>()
        var d = first
        while (!d.isAfter(last)) {
            result += Bucket(d, counts[d] ?: 0, costs[d] ?: 0.0)
            d = next(d, period)
        }
        return result
    }

    /** Verteilung: die zehn häufigsten, der Rest wird zusammengefasst. */
    fun distribution(entries: List<EntryView>, byBrand: Boolean, topN: Int = 10): List<Slice> {
        val groups = entries.groupBy { if (byBrand) it.brand ?: "Ohne Marke" else it.name }
        val sorted = groups.map { (label, list) -> Slice(label, list.size, if (byBrand) null else list.first().productUuid) }
            .sortedByDescending { it.count }
        if (sorted.size <= topN) return sorted
        val rest = sorted.drop(topN).sumOf { it.count }
        return sorted.take(topN) + Slice("Rest", rest, null)
    }

    /** Kosten je Produkt (aus dem eingefrorenen Stückpreis), absteigend. */
    fun costsByProduct(entries: List<EntryView>): List<Pair<String, Double>> =
        entries.filter { it.unitPrice != null }.groupBy { it.name }
            .map { (name, list) -> name to list.sumOf { it.unitPrice!! } }
            .sortedByDescending { it.second }

    fun withoutPrice(entries: List<EntryView>): Int = entries.count { it.unitPrice == null }
}
