package de.hr.mediathek.data.story.web.blogger

import de.hr.mediathek.data.story.web.PageParser
import de.hr.mediathek.data.story.web.ThreadBuilder
import de.hr.mediathek.data.story.web.WebDates
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Beitragsseite eines Blogger-Blogs läuft durch PageParser und ThreadBuilder (Titel, Datum, Labels als Tags). */
class BloggerImportTest {
    private val html = """
        <html><head><meta content='blogger' name='generator'/><title>Testblog: Kapitel eins</title></head><body>
        <div id="Blog1"><h2 class="date-header"><span>Maret 22, 2017</span></h2>
        <div class="post-outer"><div class="post hentry">
          <h3 class="post-title entry-title">Kapitel eins</h3>
          <div class="post-body entry-content">Erste Zeile<br>zweite Zeile<p>Neuer Absatz</p></div>
          <div class="post-footer"><abbr class="published" title="2017-03-22T19:04:00+01:00">19:04</abbr>
            <span class="post-labels"><a rel="tag" href="/search/label/Serie%20A">Serie A</a></span></div>
        </div></div></div></body></html>
    """.trimIndent()

    @Test
    fun blogger_post_becomes_one_part_with_iso_date_and_tags() {
        val url = "https://testblog.blogspot.com/2017/03/kapitel-eins.html"
        val page = PageParser.parse(Jsoup.parse(html, url))
        assertEquals("Kapitel eins", page.threadTitle)
        assertEquals(1, page.posts.size)
        assertEquals(listOf("Serie A"), page.posts[0].tags)
        // ISO-Datum hat Vorrang vor dem indonesischen Datumstext
        assertEquals("22.03.2017 19:04", WebDates.format(page.posts[0].date!!.millis))
        val thread = ThreadBuilder.build(url, listOf(page))
        assertEquals(1, thread.parts.size)
        assertTrue(thread.parts[0].selected)
        assertEquals(listOf("Serie A"), thread.parts[0].tags)
    }
}
