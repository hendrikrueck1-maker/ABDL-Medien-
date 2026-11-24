package de.hr.mediathek.data.story.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WebImportTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun millis(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        java.time.LocalDateTime.of(y, m, d, h, min).atZone(berlin).toInstant().toEpochMilli()

    /** Nachbau eines vBulletin-3.8-Beitrags wie im Forum "WB-Community". */
    private val vb3 = """
        <html><head><title>Leere - WB-Community</title></head><body>
        <table class="tborder"><tr><td class="navbar"><a href="/">Forum</a> &gt; <strong>Leere</strong></td></tr></table>
        <table id="post24745" class="tborder" cellpadding="6" cellspacing="0" border="0" width="100%" align="center">
          <tr><td class="thead"><a name="post24745"></a>
            <a href="showthread.php?p=24745#post24745"><img class="inlineimg" src="x.gif"></a> 12.05.2004, 19:04
            <a id="postcount24745" href="showpost.php?p=24745&postcount=1"><strong>#1</strong></a></td></tr>
          <tr><td class="alt2"><a class="bigusername" href="member.php?u=1">PapaMaxi</a><div>inaktiv</div></td>
            <td class="alt1" id="td_post_24745">
              <div class="smallfont"><strong>Leere</strong></div><hr size="1">
              <div id="post_message_24745">Ein Gedicht was moment sehr gut beschreibt..<br /><br />
                <i>Voller hass auf mich selbst,<br />voller Wut auf die Menschen,<br />die mir das angetan haben,</i></div>
            </td></tr>
        </table>
        <table id="post24746" class="tborder"><tr><td class="thead"><a name="post24746"></a> 13.05.2004, 08:15 <a id="postcount24746"><strong>#2</strong></a></td></tr>
          <tr><td class="alt2"><a class="bigusername">Anna</a></td>
          <td class="alt1" id="td_post_24746"><div id="post_message_24746">
            <div style="margin:20px; margin-top:5px;"><div class="smallfont" style="margin-bottom:2px">Zitat:</div>
              <table><tr><td class="alt2">Ein Gedicht was moment</td></tr></table></div>
            Schön!</div></td></tr>
        </table>
        <div class="pagenav"><a rel="next" href="showthread.php?24745-Leere&amp;page=2">&gt;</a></div>
        <div>Letzter Beitrag: 28.02.2008, 10:46</div>
        </body></html>
    """.trimIndent()

    @Test
    fun forumPostsTitleDateAuthor() {
        val page = PageParser.parse(Jsoup.parse(vb3, "https://www.wb-community.com/showthread.php?24745-Leere"))
        assertEquals("forum", page.kind)
        assertEquals("Leere", page.threadTitle)
        assertEquals(2, page.posts.size)
        val p = page.posts[0]
        assertEquals("24745", p.id)
        assertEquals(1, p.number)
        assertEquals("PapaMaxi", p.author)
        assertEquals("Leere", p.title)
        assertEquals(millis(2004, 5, 12, 19, 4), p.date!!.millis)
        assertTrue(p.text.startsWith("Ein Gedicht was moment sehr gut beschreibt.."))
        assertTrue(p.text.contains("Voller hass auf mich selbst,\nvoller Wut auf die Menschen,"))
        assertFalse(p.text.contains("Letzter Beitrag"))
        assertEquals("https://www.wb-community.com/showthread.php?24745-Leere&page=2", page.nextUrl)
    }

    @Test
    fun quotesAreRemoved() {
        val page = PageParser.parse(Jsoup.parse(vb3, "https://www.wb-community.com/showthread.php?24745-Leere"))
        assertEquals("Schön!", page.posts[1].text)
        assertEquals(millis(2004, 5, 13, 8, 15), page.posts[1].date!!.millis)
    }

    @Test
    fun threadSelectsOnlyStarterPosts() {
        val page = PageParser.parse(Jsoup.parse(vb3, "https://www.wb-community.com/showthread.php?24745-Leere"))
        val thread = ThreadBuilder.build("https://www.wb-community.com/showthread.php?24745-Leere", listOf(page))
        assertEquals("wb-community.com", thread.host)
        assertEquals("24745", thread.key)
        assertEquals(listOf(true, false), thread.parts.map { it.selected })
        assertEquals("web-wb-community.com-24745-24745", ThreadBuilder.slug(thread, thread.parts[0]))
        assertTrue(thread.warnings.isEmpty())
    }

    @Test
    fun vb4Layout() {
        val html = """
            <html><head><title>Die Reise - Seite 2 - Beispielforum</title></head><body>
            <span class="threadtitle"><a>Die Reise</a></span>
            <ol><li class="postbit postcontainer" id="post_555">
              <div class="posthead"><span class="postdate"><span class="date">03.07.2012,<span class="time">21:30</span></span></span>
                <a class="postcounter" href="#">#11</a></div>
              <div class="userinfo"><a class="username" href="m"><strong>Erzähler</strong></a></div>
              <h2 class="title icon">Kapitel 2</h2>
              <div class="postbody"><div class="content"><div id="post_message_555"><blockquote class="postcontent restore">Es war einmal.<br />Und weiter.</blockquote></div></div></div>
            </li></ol></body></html>
        """.trimIndent()
        val page = PageParser.parse(Jsoup.parse(html, "https://forum.example/showthread.php?t=99"))
        val p = page.posts.single()
        assertEquals("Die Reise", page.threadTitle)
        assertEquals("Erzähler", p.author)
        assertEquals("Kapitel 2", p.title)
        assertEquals(11, p.number)
        assertEquals(millis(2012, 7, 3, 21, 30), p.date!!.millis)
        assertEquals("Es war einmal.\nUnd weiter.", p.text)
        val thread = ThreadBuilder.build("https://forum.example/showthread.php?t=99", listOf(page))
        assertEquals("99", thread.key)
        assertTrue(thread.warnings.any { it.contains("erste Seite") })
    }

    @Test
    fun articleFallback() {
        val body = "Ein langer Absatz. ".repeat(30)
        val html = """
            <html><head><title>Mein Blog – Titel des Artikels</title>
            <meta property="article:published_time" content="2023-04-05T10:30:00+02:00">
            <meta property="og:title" content="Titel des Artikels"></head>
            <body><article><h1>Titel des Artikels</h1><p>$body</p><p>Zweiter Absatz.</p></article></body></html>
        """.trimIndent()
        val page = PageParser.parse(Jsoup.parse(html, "https://blog.example/2023/titel"))
        assertEquals("article", page.kind)
        assertEquals("Titel des Artikels", page.threadTitle)
        assertEquals(millis(2023, 4, 5, 10, 30), page.posts.single().date!!.millis)
        assertTrue(page.posts.single().text.contains("Zweiter Absatz."))
    }

    @Test
    fun dates() {
        val today = LocalDate.of(2026, 11, 24)
        assertEquals(millis(2004, 5, 12, 19, 4), WebDates.findIn("12.05.2004, 19:04")!!.millis)
        assertEquals(millis(2004, 5, 12), WebDates.findIn("am 12.05.2004")!!.millis)
        val y = WebDates.findIn("Gestern, 09:15", today)!!
        assertTrue(y.relative)
        assertEquals(millis(2026, 11, 23, 9, 15), y.millis)
        assertEquals(millis(2026, 11, 24, 7, 5), WebDates.findIn("Heute 07:05", today)!!.millis)
        assertNull(WebDates.findIn("31.02.2004"))
        assertNotNull(WebDates.parseMachine("2012-07-03T21:30:00"))
        assertNull(WebDates.parseInput("Heute, 10:00"))
        assertEquals("12.05.2004 19:04", WebDates.format(millis(2004, 5, 12, 19, 4)))
    }

    @Test
    fun threadKeys() {
        assertEquals("24745", ThreadBuilder.threadKey("https://www.wb-community.com/showthread.php?24745-Leere"))
        assertEquals("99", ThreadBuilder.threadKey("https://x.de/showthread.php?t=99&page=2"))
        assertEquals("1234", ThreadBuilder.threadKey("https://x.de/threads/titel.1234/"))
        assertTrue(ThreadBuilder.threadKey("https://blog.example/2023/titel").startsWith("u"))
    }

    @Test
    fun cleanTitle() {
        assertEquals("Leere", PageParser.cleanTitle("Leere - WB-Community", stripSite = true))
        assertEquals("Teil 1 - Der Anfang", PageParser.cleanTitle("Teil 1 - Der Anfang"))
        assertEquals("Die Reise", PageParser.cleanTitle("Die Reise - Seite 2 - Beispielforum", stripSite = true))
        assertEquals("Leere", PageParser.cleanTitle("Thema: Leere"))
    }
}
