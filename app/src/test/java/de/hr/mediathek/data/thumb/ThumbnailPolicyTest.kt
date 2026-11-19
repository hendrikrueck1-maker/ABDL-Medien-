package de.hr.mediathek.data.thumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPolicyTest {
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun genugPlatz_hartesLimit() {
        assertEquals(ThumbnailPolicy.HARD_LIMIT_BYTES, ThumbnailPolicy.effectiveLimit(1 * gb, 5 * gb))
    }

    @Test
    fun wenigPlatz_cacheSchrumpftUmFehlbetrag() {
        // 1 GB belegt, nur 600 MB frei -> 400 MB fehlen -> Limit 1 GB - 400 MB
        val limit = ThumbnailPolicy.effectiveLimit(1 * gb, 600 * mb)
        assertEquals(1 * gb - 400 * mb, limit)
    }

    @Test
    fun wenigPlatz_nieUnterMindestgroesse() {
        assertEquals(ThumbnailPolicy.MIN_LIMIT_BYTES, ThumbnailPolicy.effectiveLimit(150 * mb, 10 * mb))
    }

    @Test
    fun verdraengung_nurWennUeberLimit() {
        val plan = ThumbnailPolicy.planEviction(listOf("a" to 10L, "b" to 10L), usedBytes = 20, limit = 20)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun verdraengung_aeltesteZuerst_bisNeunzigProzent() {
        val candidates = (1..10).map { "k$it" to 10L }
        // 100 belegt, Limit 80 -> Ziel 72 -> es müssen 3 Einträge (je 10) weg
        val plan = ThumbnailPolicy.planEviction(candidates, usedBytes = 100, limit = 80)
        assertEquals(listOf("k1", "k2", "k3"), plan)
    }

    @Test
    fun verdraengung_ohneGenugKandidaten_gibtAllesWasDaIst() {
        val plan = ThumbnailPolicy.planEviction(listOf("a" to 5L), usedBytes = 100, limit = 50)
        assertEquals(listOf("a"), plan)
    }
}
