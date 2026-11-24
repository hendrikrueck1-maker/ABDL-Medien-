package de.hr.mediathek.data.story.mdimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant

/** Datum, Link, Quelle und Serie überstehen Export und Import zwischen Handy und Tablet. */
class WebFieldsTest {
    @Test
    fun jsonKeepsPublishedDateSourceAndSeries() {
        val json = """
            [{"title":"Leere","content":"Ein Gedicht.","category":"mixed","createdAt":"2026-11-24T14:00:00Z",
              "publishedAt":"2004-05-12T17:04:00Z","source":"wb-community.com",
              "sourceUrl":"https://www.wb-community.com/showthread.php?24745-Leere","series":"Meine Serie","seriesOrder":2}]
        """.trimIndent()
        val arr = JsonStoryParser.parseArray(json)!!
        val story = (JsonStoryParser.parseElement(0, arr.get(0), 1L) as ParseResult.Ok).story
        assertEquals(Instant.parse("2004-05-12T17:04:00Z").toEpochMilli(), story.publishedAt)
        assertEquals("wb-community.com", story.sourceText)
        assertEquals("https://www.wb-community.com/showthread.php?24745-Leere", story.sourceUrl)
        assertEquals(SeriesRef("Meine Serie", 2), story.series)
    }

    @Test
    fun markdownKeepsPublishedDateAndUrl() {
        val md = "---\ntitle: \"Leere\"\ncategory: mixed\ncreatedAt: 2026-11-24T14:00:00Z\n" +
            "publishedAt: 2004-05-12T17:04:00Z\nsourceUrl: \"https://example.org/t/1\"\nseries: \"Reihe\"\nseriesOrder: 1\n---\n\nText.\n"
        val story = (MarkdownStoryParser.parse("Leere.md", md, 1L) as ParseResult.Ok).story
        assertNotNull(story.publishedAt)
        assertEquals("https://example.org/t/1", story.sourceUrl)
        assertEquals(SeriesRef("Reihe", 1), story.series)
    }
}
