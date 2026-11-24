package de.hr.mediathek.data.story.web

import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.mdimport.SeriesRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebStoriesTest {
    private fun part(id: String, title: String? = null) = WebPart(id, "Anna", 1_000L, false, title, "Text $id", true)
    private val thread = WebThread("https://www.wb-community.com/showthread.php?24745-Leere", "wb-community.com", "24745", "Leere", "forum", 1, emptyList(), emptyList())

    @Test
    fun extractLinks() {
        val text = "https://a.de/1\nhttps://a.de/2, https://a.de/1\n  Hier: http://b.de/x). und nochmal https://a.de/2"
        assertEquals(listOf("https://a.de/1", "https://a.de/2", "http://b.de/x"), WebStories.extractLinks(text))
        assertEquals(emptyList<String>(), WebStories.extractLinks("kein Link"))
    }

    @Test
    fun singleStoryHasNoSeries() {
        val s = WebStories.parsed(thread, "Leere", StoryCategory.MIXED, listOf(part("1") to 5L), 9L).single()
        assertEquals("Leere", s.title)
        assertNull(s.series)
        assertEquals(5L, s.publishedAt)
        assertEquals("https://www.wb-community.com/showthread.php?24745-Leere", s.sourceUrl)
        assertEquals("wb-community.com", s.sourceText)
    }

    @Test
    fun severalChaptersBecomeSeries() {
        val list = WebStories.parsed(
            thread, "Leere", StoryCategory.WOMEN, listOf(part("1") to null, part("2", "Kapitel 2") to null, part("3", "Re: Leere") to null), 9L,
        )
        assertEquals(listOf("Leere – Teil 1", "Kapitel 2", "Leere – Teil 3"), list.map { it.title })
        assertEquals(listOf(SeriesRef("Leere", 1), SeriesRef("Leere", 2), SeriesRef("Leere", 3)), list.map { it.series })
        assertEquals(3, list.map { it.slug }.distinct().size)
    }
}
