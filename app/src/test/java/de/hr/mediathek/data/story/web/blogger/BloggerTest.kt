package de.hr.mediathek.data.story.web.blogger

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Neutrale Testdaten: blog-a.example / testblog.blogspot.com, Platzhaltertexte. */
class BloggerTest {

    // ---------- Adressen ----------

    @Test
    fun target_from_post_link_mobile_and_bare_host() {
        val a = BloggerFeed.target("https://testblog.blogspot.com/2014/05/kapitel-eins.html?m=1#comments")!!
        assertEquals("https://testblog.blogspot.com", a.origin)
        assertNull(a.label)
        assertEquals("https://testblog.blogspot.com", BloggerFeed.target("testblog.blogspot.com")!!.origin)
        assertEquals("https://testblog.blogspot.com", BloggerFeed.target("http://testblog.blogspot.com/")!!.origin)
    }

    @Test
    fun target_country_domain_becomes_com() {
        assertEquals("https://testblog.blogspot.com", BloggerFeed.target("https://testblog.blogspot.de/")!!.origin)
        assertEquals("https://testblog.blogspot.com", BloggerFeed.target("testblog.blogspot.co.uk")!!.origin)
    }

    @Test
    fun target_takes_label_from_label_page() {
        val t = BloggerFeed.target("https://testblog.blogspot.com/search/label/Lange%20Geschichten?max-results=20")!!
        assertEquals("Lange Geschichten", t.label)
        val url = BloggerFeed.feedUrl(t, 1)
        assertTrue(url, url.startsWith("https://testblog.blogspot.com/feeds/posts/summary/-/Lange%20Geschichten?"))
    }

    @Test
    fun target_custom_domain_keeps_scheme_and_host() {
        val t = BloggerFeed.target("http://blog-a.example/2019/01/teil-1.html")!!
        assertEquals("http://blog-a.example", t.origin)
    }

    @Test
    fun target_rejects_garbage() {
        assertNull(BloggerFeed.target(""))
        assertNull(BloggerFeed.target("kein link hier"))
        assertNull(BloggerFeed.target("ftp://testblog.blogspot.com"))
        assertNull(BloggerFeed.target("localhost"))
    }

    @Test
    fun feed_url_has_paging_and_no_redirect() {
        val u = BloggerFeed.feedUrl(BloggerFeed.Target("https://testblog.blogspot.com", null), 151)
        assertEquals(
            "https://testblog.blogspot.com/feeds/posts/summary?redirect=false&orderby=published&max-results=150&start-index=151", u,
        )
        assertTrue(BloggerFeed.feedUrl(BloggerFeed.Target("https://x.blogspot.com", null), 1, fullText = true).contains("/feeds/posts/default?"))
    }

    @Test
    fun post_url_is_normalized() {
        assertEquals(
            "https://testblog.blogspot.com/2014/05/kapitel-eins.html",
            BloggerFeed.normalizePostUrl("http://testblog.blogspot.de/2014/05/kapitel-eins.html?m=1&utm_source=x#more"),
        )
    }

    // ---------- Feed lesen ----------

    @Test
    fun parses_feed_entries() {
        val page = BloggerFeed.parse(feed(total = 2, start = 1, entries = listOf(
            entry(1, "Kapitel eins", "2014-05-12T19:04:00.000+02:00", labels = listOf("Serie A", "Teil 1"), content = "<p>Absatz eins</p><p>Absatz &amp; zwei</p>"),
            entry(2, "", "2014-06-01T08:00:00.000+02:00", author = "Unknown"),
        )))
        assertTrue(page.isBlogger)
        assertEquals("Testblog", page.blogTitle)
        assertEquals(2, page.totalResults)
        assertEquals(2, page.posts.size)
        val p = page.posts[0]
        assertEquals("Kapitel eins", p.title)
        assertEquals("https://testblog.blogspot.com/2014/05/post-1.html", p.url)
        assertEquals("777", p.blogId)
        assertEquals("1", p.postId)
        assertEquals(listOf("Serie A", "Teil 1"), p.labels)
        assertEquals(1399914240000L, p.publishedAt)
        assertEquals("Verfasser", p.author)
        assertEquals("Absatz eins\n\nAbsatz & zwei", BloggerPage.htmlToText(p.contentHtml!!))
        // Beitrag ohne Titel und mit verstecktem Verfasser
        assertEquals("", page.posts[1].title)
        assertNull(page.posts[1].author)
        assertNull(page.posts[1].contentHtml)
    }

    @Test
    fun non_blogger_feed_or_html_is_rejected() {
        assertFalse(BloggerFeed.parse("<html><body>Hallo</body></html>").isBlogger)
        assertFalse(BloggerFeed.parse("""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><id>urn:x</id><generator>WordPress</generator></feed>""").isBlogger)
    }

    // ---------- Sammler ----------

    private class FakeWeb(val routes: (String) -> HttpResult) {
        val calls = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        fun collector(pageSize: Int = 2) = BloggerCollector(
            fetch = { url -> calls += url; routes(url) },
            sleep = { ms -> sleeps += ms },
            pauseMs = 1_000,
            backoffMs = listOf(30_000, 120_000),
            pageSize = pageSize,
        )
    }

    @Test
    fun collects_all_pages_oldest_first() = runBlocking {
        val web = FakeWeb { url ->
            when {
                "start-index=1&" in "$url&" -> ok(feed(5, 1, listOf(entry(5, "E", "2015-01-05T00:00:00Z"), entry(4, "D", "2015-01-04T00:00:00Z"))))
                "start-index=3&" in "$url&" -> ok(feed(5, 3, listOf(entry(3, "C", "2015-01-03T00:00:00Z"), entry(2, "B", "2015-01-02T00:00:00Z"))))
                "start-index=5&" in "$url&" -> ok(feed(5, 5, listOf(entry(1, "A", "2015-01-01T00:00:00Z"))))
                else -> HttpResult(500, null)
            }
        }
        val r = web.collector().collect(listOf("https://testblog.blogspot.com/"))
        assertEquals(3, web.calls.size)
        assertNull(r.blogs[0].problem)
        assertEquals(listOf("A", "B", "C", "D", "E"), r.blogs[0].posts.map { it.title })
        assertEquals(5, r.links.size)
        assertTrue("Pause zwischen den Abrufen", web.sleeps.all { it == 1_000L } && web.sleeps.size == 2)
    }

    @Test
    fun several_blogs_and_duplicates_are_collected_once() = runBlocking {
        val web = FakeWeb { url ->
            when {
                url.startsWith("https://testblog.blogspot.com/feeds/posts/summary/-/") ->
                    ok(feed(1, 1, listOf(entry(1, "A", "2015-01-01T00:00:00Z"))))
                url.startsWith("https://testblog.blogspot.com/") ->
                    ok(feed(2, 1, listOf(entry(2, "B", "2015-01-02T00:00:00Z"), entry(1, "A", "2015-01-01T00:00:00Z"))))
                url.startsWith("https://zweiter.blogspot.com/") ->
                    ok(feed(1, 1, listOf(entry(9, "Z", "2016-01-01T00:00:00Z", host = "zweiter.blogspot.com"))))
                else -> HttpResult(404, null)
            }
        }
        val r = web.collector().collect(listOf(
            "testblog.blogspot.com",
            "https://testblog.blogspot.de/",               // gleicher Blog → übersprungen
            "https://testblog.blogspot.com/search/label/Serie", // Label: Beitrag A schon da
            "https://zweiter.blogspot.com/2016/01/x.html",
        ))
        assertEquals(3, r.blogs.size)
        assertEquals(0, r.blogs[1].posts.size)
        assertEquals(
            listOf(
                "https://testblog.blogspot.com/2014/05/post-1.html",
                "https://testblog.blogspot.com/2014/05/post-2.html",
                "https://zweiter.blogspot.com/2014/05/post-9.html",
            ),
            r.links,
        )
    }

    @Test
    fun waits_longer_after_429_then_continues() = runBlocking {
        var n = 0
        val web = FakeWeb { _ -> n++; if (n <= 2) HttpResult(429, null) else ok(feed(1, 1, listOf(entry(1, "A", "2015-01-01T00:00:00Z")))) }
        val progress = mutableListOf<CollectProgress>()
        val r = web.collector().collect(listOf("testblog.blogspot.com")) { progress += it }
        assertNull(r.blogs[0].problem)
        assertEquals(1, r.blogs[0].posts.size)
        assertEquals(listOf(30_000L, 120_000L), web.sleeps)
        assertTrue(progress.any { it.waitingMs == 120_000L })
    }

    @Test
    fun gives_up_after_backoff_but_keeps_what_was_found() = runBlocking {
        val web = FakeWeb { url ->
            if ("start-index=1&" in "$url&") ok(feed(4, 1, listOf(entry(2, "B", "2015-01-02T00:00:00Z"), entry(1, "A", "2015-01-01T00:00:00Z"))))
            else HttpResult(429, null)
        }
        val r = web.collector().collect(listOf("testblog.blogspot.com"))
        assertEquals(BlogProblem.TOO_MANY_REQUESTS, r.blogs[0].problem)
        assertEquals(2, r.blogs[0].posts.size)
        assertEquals(2, r.links.size)
    }

    @Test
    fun feed_switched_off_falls_back_to_html_pages() = runBlocking {
        val web = FakeWeb { url ->
            when (url) {
                "https://testblog.blogspot.com/" -> ok(listingHtml(listOf(3, 2), older = "https://testblog.blogspot.com/search?updated-max=2015-01-02&max-results=2"), url)
                "https://testblog.blogspot.com/search?updated-max=2015-01-02&max-results=2" -> ok(listingHtml(listOf(1), older = null), url)
                else -> HttpResult(404, null) // Feed abgeschaltet
            }
        }
        val r = web.collector().collect(listOf("testblog.blogspot.com"))
        val b = r.blogs[0]
        assertNull(b.problem)
        assertTrue(b.viaHtml)
        assertEquals("Testblog", b.blogTitle)
        assertEquals(
            listOf(1, 2, 3).map { "https://testblog.blogspot.com/2014/05/post-$it.html" },
            r.links,
        )
    }

    @Test
    fun not_blogger_private_and_missing_are_reported() = runBlocking {
        val web = FakeWeb { url ->
            when {
                url.startsWith("https://wordpress.example/feeds") -> HttpResult(404, null)
                url == "https://wordpress.example/" -> ok("<html><head><meta name=generator content='WordPress 6'></head><body></body></html>", url)
                url.startsWith("https://privat.blogspot.com") -> HttpResult(200, "<html>Anmelden</html>", "https://accounts.google.com/ServiceLogin?continue=x")
                url.startsWith("https://weg.blogspot.com") -> HttpResult(404, null)
                else -> HttpResult(0, null)
            }
        }
        val r = web.collector().collect(listOf("wordpress.example", "privat.blogspot.com", "weg.blogspot.com", "nur text"))
        assertEquals(
            listOf(BlogProblem.NOT_BLOGGER, BlogProblem.PRIVATE, BlogProblem.NOT_FOUND, BlogProblem.INVALID_ADDRESS),
            r.blogs.map { it.problem },
        )
        assertEquals(4, r.failed.size)
    }

    @Test
    fun network_exception_does_not_crash() = runBlocking {
        val c = BloggerCollector(fetch = { throw java.io.IOException("offline") }, sleep = {})
        val r = c.collect(listOf("testblog.blogspot.com"))
        assertEquals(BlogProblem.NETWORK, r.blogs[0].problem)
    }

    // ---------- Beitragsseite ----------

    @Test
    fun parses_single_post_page() {
        val html = """
            <html><head><meta content='blogger' name='generator'/><title>Testblog: Kapitel eins</title></head><body>
            <div id="Blog1"><div class="date-outer"><h2 class="date-header"><span>Montag, 12. Mai 2014</span></h2>
            <div class="post-outer"><div class="post hentry">
              <h3 class="post-title entry-title">Kapitel eins</h3>
              <div class="post-body entry-content">Erste Zeile<br>zweite Zeile<p>Neuer Absatz&nbsp;hier</p>
                <script>var x=1;</script><div class="post-share-buttons">Teilen</div></div>
              <div class="post-footer"><span class="post-author vcard">Eingestellt von <span class="fn">Verfasser</span></span>
                <abbr class="published" title="2014-05-12T19:04:00+02:00">19:04</abbr>
                <span class="post-labels">Labels: <a href="/search/label/Serie%20A" rel="tag">Serie A</a></span></div>
            </div></div></div></div></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://testblog.blogspot.com/2014/05/kapitel-eins.html")
        assertTrue(BloggerPage.isBlogger(doc))
        val p = BloggerPage.post(doc)!!
        assertEquals("Kapitel eins", p.title)
        assertEquals("Erste Zeile\nzweite Zeile\n\nNeuer Absatz hier", p.text)
        assertEquals("2014-05-12T19:04:00+02:00", p.publishedIso)
        assertEquals("Montag, 12. Mai 2014", p.dateText)
        assertEquals("Verfasser", p.author)
        assertEquals(listOf("Serie A"), p.labels)
    }

    @Test
    fun listing_ignores_comment_static_and_foreign_links() {
        val html = """<html><body><div id="Blog1">
            <h3 class="post-title"><a href="/2014/05/post-1.html">Eins</a></h3>
            <h3 class="post-title"><a href="https://testblog.blogspot.com/2014/05/post-1.html#comment-form">Eins Kommentare</a></h3>
            <h3 class="post-title"><a href="/p/impressum.html">Impressum</a></h3>
            <h3 class="post-title"><a href="https://anderer.blogspot.com/2014/05/fremd.html">Fremd</a></h3>
            <a class="blog-pager-older-link" href="/search?updated-max=x">Ältere Posts</a>
            </div></body></html>"""
        val l = BloggerPage.listing(Jsoup.parse(html, "https://testblog.blogspot.com/"))
        assertEquals(listOf("https://testblog.blogspot.com/2014/05/post-1.html"), l.links)
        assertNotNull(l.olderPageUrl)
    }

    // ---------- Hilfen ----------

    private fun ok(body: String, url: String? = null) = HttpResult(200, body, url)

    private fun feed(total: Int, start: Int, entries: List<String>) = """<?xml version='1.0' encoding='UTF-8'?>
        <feed xmlns='http://www.w3.org/2005/Atom' xmlns:openSearch='http://a9.com/-/spec/opensearchrss/1.0/'>
        <id>tag:blogger.com,1999:blog-777</id><title type='text'>Testblog</title>
        <generator version='7.00' uri='http://www.blogger.com'>Blogger</generator>
        <openSearch:totalResults>$total</openSearch:totalResults>
        <openSearch:startIndex>$start</openSearch:startIndex>
        <openSearch:itemsPerPage>2</openSearch:itemsPerPage>
        ${entries.joinToString("\n")}
        </feed>"""

    private fun entry(
        n: Int, title: String, published: String, labels: List<String> = emptyList(),
        author: String = "Verfasser", content: String? = null, host: String = "testblog.blogspot.com",
    ): String {
        val cats = labels.joinToString("") { "<category scheme='http://www.blogger.com/atom/ns#' term='$it'/>" }
        val body = content?.let { "<content type='html'>${it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</content>" }
            ?: "<summary type='text'>Kurz</summary>"
        return """<entry><id>tag:blogger.com,1999:blog-777.post-$n</id>
            <published>$published</published><updated>$published</updated>$cats
            <title type='text'>$title</title>$body
            <link rel='replies' type='text/html' href='https://$host/2014/05/post-$n.html#comment-form'/>
            <link rel='alternate' type='text/html' href='https://$host/2014/05/post-$n.html' title='$title'/>
            <author><name>$author</name></author></entry>"""
    }

    private fun listingHtml(posts: List<Int>, older: String?) = """<html><head><meta content='blogger' name='generator'/>
        <meta property='og:site_name' content='Testblog'/><title>Testblog</title></head><body><div id="Blog1">
        ${posts.joinToString("") { "<h3 class='post-title'><a href='/2014/05/post-$it.html'>Teil $it</a></h3>" }}
        ${older?.let { "<a class='blog-pager-older-link' href='$it'>Ältere Posts</a>" } ?: ""}
        </div></body></html>"""
}
