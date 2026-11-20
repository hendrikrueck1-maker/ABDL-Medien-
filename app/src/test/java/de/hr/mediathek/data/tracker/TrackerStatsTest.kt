package de.hr.mediathek.data.tracker

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerStatsTest {
    private val zone = "Europe/Berlin"
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun entry(t: Long, name: String = "A", brand: String? = "X", price: Double? = 1.0, p: String? = "p1") =
        EntryView("u$t", t, zone, p, name, brand, price, null)

    @Test fun nightEntriesBelongToPreviousDay() {
        val start = 6 * 60
        assertEquals(LocalDate.of(2026, 3, 9), TrackerStats.dayOf(at(2026, 3, 10, 2), zone, start))
        assertEquals(LocalDate.of(2026, 3, 10), TrackerStats.dayOf(at(2026, 3, 10, 6, 0), zone, start))
        assertEquals(LocalDate.of(2026, 3, 10), TrackerStats.dayOf(at(2026, 3, 10, 2), zone, 0))
    }

    @Test fun weekStartsMonday() {
        assertEquals(LocalDate.of(2026, 3, 9), TrackerStats.bucketStart(LocalDate.of(2026, 3, 15), StatPeriod.WEEK))
        assertEquals(LocalDate.of(2026, 3, 1), TrackerStats.bucketStart(LocalDate.of(2026, 3, 15), StatPeriod.MONTH))
    }

    @Test fun bucketsAreGaplessAndSummed() {
        val entries = listOf(entry(at(2026, 3, 1, 12)), entry(at(2026, 3, 1, 15), price = 2.0), entry(at(2026, 3, 4, 12)))
        val b = TrackerStats.buckets(entries, StatPeriod.DAY, 360, LocalDate.of(2026, 3, 5))
        assertEquals(5, b.size)
        assertEquals(2, b[0].count)
        assertEquals(3.0, b[0].cost, 0.001)
        assertEquals(0, b[1].count)
        assertEquals(1, b[3].count)
        assertEquals(0, b[4].count)
    }

    @Test fun distributionTopTenPlusRest() {
        val entries = (1..12).flatMap { i -> List(13 - i) { entry(it.toLong() + i * 1000L, name = "P$i") } }
        val d = TrackerStats.distribution(entries, false)
        assertEquals(11, d.size)
        assertEquals("P1", d[0].label)
        assertEquals("Rest", d.last().label)
        assertEquals(2 + 1, d.last().count)
    }

    @Test fun costsAndMissingPrices() {
        val entries = listOf(entry(1, name = "A", price = 1.5), entry(2, name = "A", price = 1.5), entry(3, name = "B", price = null))
        assertEquals(3.0, TrackerStats.costsByProduct(entries)[0].second, 0.001)
        assertEquals(1, TrackerStats.withoutPrice(entries))
    }
}
