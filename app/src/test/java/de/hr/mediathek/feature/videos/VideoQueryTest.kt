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

    @Test
    fun kategorie_filterNutztZuordnungenOhneGeloeschte() {
        val q = VideoQuery.build(VideoFilter(categoryUuid = "abc"), 1)
        assertTrue(q.sql.contains("FROM category_assignment"))
        assertTrue(q.sql.contains("deletedAt IS NULL)"))
        assertEquals(listOf<Any>("abc"), q.args)
    }

    @Test
    fun tag_undPlaylistFilter() {
        val q = VideoQuery.build(VideoFilter(tagUuid = "t1", playlistUuid = "p1", sort = VideoSort.PLAYLIST_ORDER), 1)
        assertTrue(q.sql.contains("FROM tag_assignment"))
        assertTrue(q.sql.contains("FROM playlist_item WHERE playlistUuid = ?"))
        assertTrue(q.sql.contains("p.mediaUuid = media_file.uuid"))
        // Argumente stehen in der Reihenfolge der Platzhalter: Tag, Playlist (WHERE), Playlist (ORDER BY)
        assertEquals(listOf<Any>("t1", "p1", "p1"), q.args)
    }

    @Test
    fun zufallsPick_nurVerfuegbare_einErgebnis_ohneUeberzaehligeArgumente() {
        val q = VideoQuery.buildRandomPick(VideoFilter(playlistUuid = "p1", sort = VideoSort.PLAYLIST_ORDER))
        assertTrue(q.sql.startsWith("SELECT uuid FROM media_file"))
        assertTrue(q.sql.contains("status = 'ONLINE'"))
        assertTrue(q.sql.contains("ORDER BY RANDOM()"))
        assertTrue(q.sql.endsWith("LIMIT 1"))
        assertEquals(listOf<Any>("p1"), q.args)
    }
}
