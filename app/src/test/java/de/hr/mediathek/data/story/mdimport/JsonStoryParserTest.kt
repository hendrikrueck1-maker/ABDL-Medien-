package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStoryParserTest {
    private val json = """
        [
          {"title": "Die Reise ans Meer", "author": "Anna", "category": "frauen", "tags": ["meer"],
           "content": "Es war einmal ein Boot.", "createdAt": "2026-08-12T10:00:00.000Z", "status": "published"},
          {"title": "Ohne alles", "content": "Nur Text.", "category": ""},
          {"content": "Titel fehlt"},
          42
        ]
    """.trimIndent()

    @Test
    fun parsesElements() {
        val arr = JsonStoryParser.parseArray(json)!!
        assertEquals(4, arr.length())
        val first = (JsonStoryParser.parseElement(0, arr.get(0), 1L) as ParseResult.Ok).story
        assertEquals("die-reise-ans-meer", first.slug)
        assertEquals(StoryCategory.WOMEN, first.category)
        assertEquals(listOf("meer"), first.tags)
        assertEquals("Anna", first.author)
        val second = (JsonStoryParser.parseElement(1, arr.get(1), 1L) as ParseResult.Ok).story
        assertEquals("draft", second.status)
        assertNull(second.author)
        assertEquals(1L, second.createdAt)
        assertTrue(JsonStoryParser.parseElement(2, arr.get(2), 1L) is ParseResult.Fail)
        assertTrue(JsonStoryParser.parseElement(3, arr.get(3), 1L) is ParseResult.Fail)
    }

    @Test
    fun notAnArray() {
        assertNull(JsonStoryParser.parseArray("{\"a\":1}"))
    }
}
