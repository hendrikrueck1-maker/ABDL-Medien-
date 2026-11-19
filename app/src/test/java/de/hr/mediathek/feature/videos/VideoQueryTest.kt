package de.hr.mediathek.feature.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQueryTest {
    @Test
    fun standard_nurVideosOhneGeloeschte_neuesteZuerst() {
        val q = VideoQuery.build(VideoFilter(), 1)
        assertTrue(q.sql.contains("type = 'VIDEO'"))
        assertTrue(q.sql.contains("deletedAt IS NULL"))
        assertTrue(q.sql.endsWith("ORDER BY createdAt DESC, id"))
        assertTrue(q.args.isEmpty())
    }

    @Test
    fun filter_setztBedingungenUndArgumente() {
        val q = VideoQuery.build(
            VideoFilter(favoriteOnly = true, minRating = 4, seen = SeenFilter.UNSEEN, onlineOnly = true, search = "Test", format = ".MKV"),
            1,
        )
        assertTrue(q.sql.contains("favorite = 1"))
        assertTrue(q.sql.contains("rating >= ?"))
        assertTrue(q.sql.contains("seenState = 'UNSEEN'"))
        assertTrue(q.sql.contains("status = 'ONLINE'"))
        assertEquals(listOf<Any>(4, "%test%", "%.mkv"), q.args)
    }

    @Test
    fun suchbegriff_sonderzeichenWerdenMaskiert() {
        val q = VideoQuery.build(VideoFilter(search = "100%_a\\b"), 1)
        assertEquals("%100\\%\\_a\\\\b%", q.args.single())
    }

    @Test
    fun suchbegriff_keineEinschleusungInDieAbfrage() {
        val q = VideoQuery.build(VideoFilter(search = "x'; DROP TABLE media_file; --"), 1)
        assertFalse(q.sql.contains("DROP"))
    }

    @Test
    fun zufall_gleicherSeedGleicheReihenfolge_andererSeedAndere() {
        val a = VideoQuery.build(VideoFilter(sort = VideoSort.RANDOM), 42).sql
        val b = VideoQuery.build(VideoFilter(sort = VideoSort.RANDOM), 42).sql
        val c = VideoQuery.build(VideoFilter(sort = VideoSort.RANDOM), 43).sql
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun bewertungWirdAufGueltigenBereichBegrenzt() {
        val q = VideoQuery.build(VideoFilter(minRating = 9), 1)
        assertEquals(listOf<Any>(5), q.args)
    }
}
