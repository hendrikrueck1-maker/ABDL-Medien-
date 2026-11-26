package de.hr.mediathek.data.story.web.blogger

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * Blogger (blogspot.com und eigene Domains auf Blogger).
 *
 * Jeder Blogger-Blog hat einen Atom-Feed unter /feeds/posts/summary bzw. /feeds/posts/default.
 * Er liefert je Abruf bis zu 150 Beiträge mit Titel, Link, Erstelldatum, Labels
 * (und im Feed "default" den ganzen Text). Damit braucht der Sammler pro 150 Beiträge
 * nur einen einzigen Abruf statt einer Seite je Beitrag.
 */
data class BloggerPost(
    /** Feed-Kennung, z. B. tag:blogger.com,1999:blog-123.post-456 */
    val id: String,
    val blogId: String?,
    val postId: String?,
    /** normalisierter Beitragslink (https, ohne ?m=1, ohne Fragment) */
    val url: String,
    /** kann leer sein – Blogger erlaubt Beiträge ohne Titel */
    val title: String,
    val publishedAt: Long?,
    val updatedAt: Long?,
    val author: String?,
    val labels: List<String>,
    /** nur beim Feed "default" (voller Text) gesetzt, sonst null */
    val contentHtml: String?,
)

data class BloggerFeedPage(
    val isBlogger: Boolean,
    val blogTitle: String?,
    val totalResults: Int?,
    val startIndex: Int?,
    val posts: List<BloggerPost>,
)

object BloggerFeed {
    /** Höchstwert, den Blogger je Feed-Abruf liefert. */
    const val PAGE_SIZE = 150

    data class Target(
        /** z. B. https://name.blogspot.com */
        val origin: String,
        /** nur dieses Label sammeln (aus /search/label/… übernommen) oder null = ganzer Blog */
        val label: String?,
    )

    private val BLOGSPOT_CC = Regex("^(.+)\\.blogspot\\.[a-z]{2,3}(\\.[a-z]{2})?$")
    private val LABEL_PATH = Regex("^/(?:search/label|feeds/posts/(?:default|summary)/-)/([^/?#]+)")

    /**
     * Bestimmt das Ziel aus einer beliebigen Blog-Adresse: Startseite, Beitrag, Label-Seite,
     * mobile Ansicht (?m=1), Feed-Link oder nur "name.blogspot.com".
     */
    fun target(input: String): Target? {
        val trimmed = input.trim().trimEnd('.', ',', ';', ')')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val raw = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = normalizeHost(uri.host ?: return null)
        if ('.' !in host) return null
        val scheme = if (uri.scheme.equals("http", true) && !host.endsWith(".blogspot.com")) "http" else "https"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val label = LABEL_PATH.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return Target("$scheme://$host$port", label)
    }

    /** Alte Länderadressen (name.blogspot.de) zeigen auf denselben Blog wie name.blogspot.com. */
    fun normalizeHost(host: String): String {
        val h = host.lowercase()
        val m = BLOGSPOT_CC.matchEntire(h) ?: return h
        return "${m.groupValues[1]}.blogspot.com"
    }

    /**
     * Feed-Adresse für einen Abruf.
     * redirect=false umgeht eine vom Betreiber eingestellte Weiterleitung (z. B. auf FeedBurner).
     */
    fun feedUrl(target: Target, startIndex: Int, fullText: Boolean = false, pageSize: Int = PAGE_SIZE): String {
        val kind = if (fullText) "default" else "summary"
        val labelPart = target.label?.let { "/-/" + encodePathSegment(it) } ?: ""
        return "${target.origin}/feeds/posts/$kind$labelPart" +
            "?redirect=false&orderby=published&max-results=$pageSize&start-index=$startIndex"
    }

    fun encodePathSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Beitragslink vereinheitlichen, damit dieselbe Geschichte nicht doppelt gesammelt wird. */
    fun normalizePostUrl(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim()
        val host = normalizeHost(uri.host ?: return url.trim())
        val scheme = if (host.endsWith(".blogspot.com")) "https" else (uri.scheme ?: "https").lowercase()
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port${uri.rawPath.orEmpty()}"
    }

    /** Liest eine Feed-Antwort. Gibt isBlogger=false zurück, wenn es kein Blogger-Feed ist. */
    fun parse(xml: String): BloggerFeedPage {
        val notBlogger = BloggerFeedPage(false, null, null, null, emptyList())
        if (!xml.trimStart().let { it.startsWith("<?xml") || it.startsWith("<feed") }) return notBlogger
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return notBlogger
        val feed = doc.children().firstOrNull { it.tagName() == "feed" } ?: return notBlogger

        val feedId = child(feed, "id")?.text().orEmpty()
        val generator = child(feed, "generator")?.text().orEmpty()
        val isBlogger = feedId.startsWith("tag:blogger.com") || generator.contains("Blogger", ignoreCase = true)
        if (!isBlogger) return notBlogger

        val total = child(feed, "openSearch:totalResults")?.text()?.trim()?.toIntOrNull()
        val start = child(feed, "openSearch:startIndex")?.text()?.trim()?.toIntOrNull()
        val posts = feed.children().filter { it.tagName() == "entry" }.mapNotNull(::entry)
        return BloggerFeedPage(true, child(feed, "title")?.text()?.trim(), total, start, posts)
    }

    private val ID_PATTERN = Regex("blog-(\\d+)\\.post-(\\d+)")
    private val HIDDEN_AUTHORS = setOf("unknown", "anonymous", "anonym", "unbekannt")

    private fun entry(e: Element): BloggerPost? {
        val link = e.children()
            .firstOrNull { it.tagName() == "link" && it.attr("rel") == "alternate" && it.attr("href").isNotBlank() }
            ?.attr("href") ?: return null
        val id = child(e, "id")?.text()?.trim().orEmpty()
        val m = ID_PATTERN.find(id)
        val author = child(e, "author")?.let { child(it, "name") }?.text()?.trim()
            ?.takeIf { it.isNotEmpty() && it.lowercase() !in HIDDEN_AUTHORS }
        val labels = e.children().filter { it.tagName() == "category" }
            .map { it.attr("term").trim() }.filter { it.isNotEmpty() }.distinct()
        // wholeText() statt text(): HTML-Inhalt ungekürzt, Zeilenumbrüche bleiben erhalten
        val content = child(e, "content")?.wholeText()?.takeIf { it.isNotBlank() }
        return BloggerPost(
            id = id.ifEmpty { link },
            blogId = m?.groupValues?.get(1),
            postId = m?.groupValues?.get(2),
            url = normalizePostUrl(link),
            title = child(e, "title")?.text()?.trim().orEmpty(),
            publishedAt = time(child(e, "published")?.text()),
            updatedAt = time(child(e, "updated")?.text()),
            author = author,
            labels = labels,
            contentHtml = content,
        )
    }

    private fun child(parent: Element, tag: String): Element? =
        parent.children().firstOrNull { it.tagName().equals(tag, ignoreCase = true) }

    fun time(s: String?): Long? =
        s?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}
