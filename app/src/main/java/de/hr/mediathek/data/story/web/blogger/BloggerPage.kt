package de.hr.mediathek.data.story.web.blogger

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML-Seiten von Blogger-Blogs: Erkennung, Übersichtsseiten (Rückfall, wenn der Feed
 * abgeschaltet ist) und einzelne Beitragsseiten.
 */
object BloggerPage {

    /** Beitragslinks bei Blogger: /2014/05/titel.html (eigene Seiten /p/… zählen nicht dazu). */
    private val POST_PATH = Regex("^/\\d{4}/\\d{2}/[^/]+\\.html$")

    fun isBlogger(doc: Document): Boolean {
        val gen = doc.selectFirst("meta[name=generator]")?.attr("content").orEmpty()
        if (gen.equals("blogger", ignoreCase = true)) return true
        return doc.select("link[rel=alternate][type=application/atom+xml]")
            .any { it.attr("href").contains("/feeds/posts/default") }
    }

    fun isPostUrl(url: String): Boolean =
        runCatching { java.net.URI(url).rawPath.orEmpty() }.getOrDefault("").matches(POST_PATH)

    data class Listing(val links: List<String>, val olderPageUrl: String?)

    /**
     * Übersichtsseite (Startseite, Label-Seite, "Ältere Posts"): sammelt Beitragslinks desselben Blogs
     * und den Link "Ältere Posts". Kommentar-, Teilen- und Archiv-Links fallen weg.
     */
    fun listing(doc: Document): Listing {
        val ownHost = BloggerFeed.normalizeHost(runCatching { java.net.URI(doc.location()).host }.getOrNull().orEmpty())
        // Bevorzugt Titel-Links der Beiträge; sonst alle Beitragslinks im Hauptbereich
        val main = doc.selectFirst("#Blog1, .blog-posts, main, #main") ?: doc.body()
        val titled = main.select(".post-title a[href], .entry-title a[href], h3 a[href], h2 a[href]")
        val candidates = if (titled.isNotEmpty()) titled else main.select("a[href]")
        val links = candidates.asSequence()
            .map { it.absUrl("href") }
            .filter { it.isNotEmpty() && isPostUrl(it) }
            .map(BloggerFeed::normalizePostUrl)
            .filter { runCatching { java.net.URI(it).host }.getOrNull() == ownHost }
            .distinct()
            .toList()
        val older = doc.selectFirst("a.blog-pager-older-link[href], #Blog1_blog-pager-older-link[href], a.older-link[href]")
            ?.absUrl("href")?.takeIf { it.isNotEmpty() }
        return Listing(links, older)
    }

    data class Post(
        val title: String,
        val text: String,
        /** Datum als ISO-Text (aus datetime/title-Attribut) oder null */
        val publishedIso: String?,
        /** sichtbarer Datumstext ("Montag, 12. Mai 2014"), für WebDates.findIn */
        val dateText: String?,
        val author: String?,
        val labels: List<String>,
    )

    /** Einzelne Beitragsseite. Null, wenn kein Beitragstext gefunden wird. */
    fun post(doc: Document): Post? {
        val body = doc.selectFirst(".post-body, .entry-content, .post-content, [itemprop=articleBody]") ?: return null
        val scope = body.parents().firstOrNull { it.hasClass("post") || it.hasClass("post-outer") || it.tagName() == "article" }
            ?: doc.body()
        val title = (scope.selectFirst(".post-title, .entry-title, [itemprop=name]:not(meta)")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()).trim()
        val iso = scope.selectFirst("time.published[datetime], abbr.published[title], .published[title], time[datetime]")
            ?.let { it.attr("datetime").ifEmpty { it.attr("title") } }
            ?: doc.selectFirst("meta[itemprop=datePublished], meta[property=article:published_time]")?.attr("content")
        val dateText = doc.selectFirst(".date-header span, .date-header, .post-timestamp, .publishdate, span[itemprop=datePublished], .published")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val author = scope.selectFirst(".post-author .fn, .post-author-name, .author .fn, [itemprop=author] [itemprop=name]")
            ?.text()?.trim()?.removePrefix("Eingestellt von")?.removePrefix("Posted by")?.trim()?.takeIf { it.isNotEmpty() }
        val labels = scope.select(".post-labels a, .labels a, a[rel=tag]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        // Manche Vorlagen (z. B. Googles eigene Blogs) liefern den Text als <script type="text/template">
        val text = toText(body).ifBlank {
            body.select("script[type=text/template]").joinToString("\n\n") { htmlToText(it.data()) }.trim()
        }
        if (text.isBlank()) return null
        return Post(title, text, iso?.takeIf { it.isNotBlank() }, dateText, author, labels)
    }

    private val BLOCK = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "blockquote",
        "pre", "table", "tr", "section", "article", "hr", "center",
    )
    private val SKIP = setOf("script", "style", "noscript", "iframe", "form", "button")

    /** Beitrags-HTML in lesbaren Text: Absätze bleiben, Zeilenumbrüche aus <br> bleiben. */
    fun toText(el: Element): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n) {
                is TextNode -> sb.append(n.text().replace(' ', ' '))
                is Element -> {
                    val tag = n.tagName().lowercase()
                    if (tag in SKIP || n.hasClass("post-share-buttons") || n.hasClass("post-footer")) return
                    if (tag == "br") { sb.append('\n'); return }
                    val block = tag in BLOCK
                    if (block) sb.append("\n\n")
                    n.childNodes().forEach(::walk)
                    if (block) sb.append("\n\n")
                }
            }
        }
        el.childNodes().forEach(::walk)
        return sb.toString()
            .lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Für den Feed "default": Inhalt (HTML-Text) direkt in Lesetext umwandeln. */
    fun htmlToText(html: String): String =
        toText(org.jsoup.Jsoup.parseBodyFragment(html).body())
}
