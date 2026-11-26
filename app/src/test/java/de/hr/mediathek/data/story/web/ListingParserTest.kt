package de.hr.mediathek.data.story.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ListingParserTest {
    private val base = "https://www.wb-community.com/forumdisplay.php?f=12&page=62"

    private val html = """
        <html><body>
        <div class="pagenav"><table><tr>
          <td class="alt2"><span class="mfont">Seite 62 von 71</span></td>
          <td class="alt2"><strong>62</strong></td>
          <td class="alt1"><a href="forumdisplay.php?f=12&amp;page=63">63</a></td>
          <td class="alt1"><a rel="next" href="forumdisplay.php?f=12&amp;page=63&amp;s=0123456789abcdef0123456789abcdef">&gt;</a></td>
        </tr></table></div>
        <table id="threadslist">
          <tr><td><a id="thread_title_24798" href="showthread.php?24798-Andy%B4s-Geschichte&amp;s=0123456789abcdef0123456789abcdef">Andys Geschichte</a></td></tr>
          <tr><td><a id="thread_title_24745" href="showthread.php?24745-Leere">Leere</a>
                  <a href="showthread.php?24745-Leere&amp;goto=lastpost">Letzter Beitrag</a></td></tr>
          <tr><td><a id="thread_title_24798" href="showthread.php?24798-Andy%B4s-Geschichte">Doppelt</a></td></tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun collectsThreadLinksWithoutSessionAndDuplicates() {
        val listing = ListingParser.parse(Jsoup.parse(html, base), 62)
        assertEquals(
            listOf(
                "https://www.wb-community.com/showthread.php?24798-Andy%B4s-Geschichte",
                "https://www.wb-community.com/showthread.php?24745-Leere",
            ),
            listing.links,
        )
        assertEquals("https://www.wb-community.com/forumdisplay.php?f=12&page=63", listing.nextUrl)
    }

    @Test
    fun nextPageFromNumberWhenNoArrow() {
        val doc = Jsoup.parse(
            """<div class="pagenav"><a href="forumdisplay.php?f=12&amp;page=2">2</a></div>
               <table id="threadslist"><tr><td><a id="thread_title_1" href="showthread.php?1-A">A</a></td></tr></table>""",
            "https://x.de/forumdisplay.php?f=12",
        )
        assertEquals("https://x.de/forumdisplay.php?f=12&page=2", ListingParser.parse(doc, 1).nextUrl)
    }

    @Test
    fun firstPageUrl() {
        assertEquals("https://x.de/forumdisplay.php?f=12", ListingParser.firstPageUrl("https://x.de/forumdisplay.php?f=12&page=62"))
        assertEquals("https://x.de/forumdisplay.php?f=12&order=desc", ListingParser.firstPageUrl("https://x.de/forumdisplay.php?f=12&page=3&order=desc"))
        assertEquals("https://x.de/forumdisplay.php?12-Geschichten", ListingParser.firstPageUrl("https://x.de/forumdisplay.php?12-Geschichten/page62"))
        assertEquals("https://x.de/forumdisplay.php?f=12", ListingParser.firstPageUrl("https://x.de/forumdisplay.php?f=12"))
    }
}

class WordPressListingTest {
    private fun page(n: Int, pager: String) = """
        <html><body><nav><a href="/category/jungs/">Jungs</a></nav>
        <article><h3><a href="https://www.windelgeschichten.org/ein-super-urlaub-2/">Ein Super Urlaub (2)</a></h3>
          <div><a href="#comments">1 comment</a> <a href="/category/jungs/">Jungs</a> <a href="/gastautor-beitraege/?guest_author=Nils">Nils</a></div>
          <a title="Read More" href="https://www.windelgeschichten.org/ein-super-urlaub-2/">Read More</a></article>
        <article><h3><a href="/ein-traum-mit-folgen-7/">Ein Traum mit Folgen (7)</a></h3>
          <a title="Read More" href="/ein-traum-mit-folgen-7/">Read More</a></article>
        <div class="pager">$pager</div></body></html>
    """.trimIndent()

    @Test
    fun collectsPostLinksAndBuildsNextPage() {
        val doc = Jsoup.parse(page(1, """<span>1</span><a href="/page/2/">2</a><a href="/page/3/">3</a><a href="/page/211/">211</a>"""), "https://www.windelgeschichten.org/")
        val l = ListingParser.parse(doc, 1)
        assertEquals(
            listOf("https://www.windelgeschichten.org/ein-super-urlaub-2/", "https://www.windelgeschichten.org/ein-traum-mit-folgen-7/"),
            l.links,
        )
        assertEquals("https://www.windelgeschichten.org/page/2/", l.nextUrl)
    }

    @Test
    fun lastPageHasNoNext() {
        val doc = Jsoup.parse(page(211, """<a href="/page/209/">209</a><a href="/page/210/">210</a><span>211</span>"""), "https://www.windelgeschichten.org/page/211/")
        assertEquals(null, ListingParser.parse(doc, 211).nextUrl)
    }

    @Test
    fun firstPageUrlStripsWordPressPaging() {
        assertEquals("https://www.windelgeschichten.org/", ListingParser.firstPageUrl("https://www.windelgeschichten.org/page/62/"))
        assertEquals("https://x.org/category/jungs/", ListingParser.firstPageUrl("https://x.org/category/jungs/page/3/"))
    }
}
