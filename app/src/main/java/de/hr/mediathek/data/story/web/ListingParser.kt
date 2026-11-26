package de.hr.mediathek.data.story.web

import org.jsoup.nodes.Document

/** Liest die Übersichtsseite eines Forum-Bereichs (vBulletin: Liste der Themen, mehrere Seiten) und sammelt die Themen-Links. */
object ListingParser {
    data class Listing(val links: List<String>, val nextUrl: String?)

    private val SESSION = Regex("""([?&])s=[0-9a-fA-F]{32}(&|$)""")
    private val PAGE_PARAM = Regex("""([?&])page=\d+(&|$)""")
    private val PAGE_PATH = Regex("""/page\d+""")
    /** WordPress: /page/2/ am Ende des Pfads. */
    private val WP_PAGE = Regex("""/page/\d+/?(?=$|[?#])""")
    private val WP_SKIP = Regex("""/(?:page|category|tag|author|feed|wp-content|wp-json|wp-admin|comments)/|guest_author=|[?&](?:s|replytocom)=|/(?:feed|embed)/?$""")

    /** Entfernt eine Seitenangabe, damit das Sammeln bei Seite 1 beginnt, auch wenn der Nutzer Seite 62 kopiert hat. */
    fun firstPageUrl(url: String): String {
        var u = url.trim()
        u = WP_PAGE.replace(u, "/").replace("//?", "/?")
        u = PAGE_PATH.replace(u, "")
        u = PAGE_PARAM.replace(u) { m -> if (m.groupValues[2] == "&") m.groupValues[1] else "" }
        return u.trimEnd('?', '&')
    }

    /** Sitzungskennung ("&s=…") aus dem Link entfernen, damit gleiche Themen gleich aussehen. */
    fun cleanLink(url: String): String {
        var u = SESSION.replace(url) { m -> if (m.groupValues[2] == "&") m.groupValues[1] else "" }
        u = u.trimEnd('?', '&')
        return u.substringBefore('#')
    }

    /** Beitragslinks (`?p=123` / `&p=123`), nicht Themenlinks; `sp=` usw. bleiben erlaubt. */
    private val POST_PARAM = Regex("[?&]p=")

    /** [pageNumber]: Nummer der gerade gelesenen Seite; hilft, die nächste Seite in der Seitenleiste zu finden. */
    fun parse(doc: Document, pageNumber: Int): Listing {
        var anchors = doc.select("a[id^=thread_title_]")
        if (anchors.isEmpty()) {
            anchors = doc.select("a[href*=showthread.php]").filter { a ->
                val href = a.attr("href")
                a.text().isNotBlank() && "goto=" !in href && "page=" !in href && !POST_PARAM.containsMatchIn(href) && "#" !in href &&
                    a.closest("#threadslist, #threads, .threadlist, table.tborder") != null
            }.let { org.jsoup.select.Elements(it) }
        }
        if (anchors.isEmpty()) return parseWordPress(doc, pageNumber)
        val links = LinkedHashMap<String, String>()
        for (a in anchors) {
            val abs = a.absUrl("href")
            if (abs.isEmpty()) continue
            val clean = cleanLink(abs)
            links.putIfAbsent(ThreadBuilder.threadKey(clean), clean)
        }
        val next = PageParser.nextUrl(doc)?.let { cleanLink(it) }
            ?: doc.select(".pagenav a[href], .pagination a[href]")
                .firstOrNull { it.text().trim() == (pageNumber + 1).toString() }?.absUrl("href")?.takeIf { it.isNotEmpty() }?.let { cleanLink(it) }
        return Listing(links.values.toList(), next)
    }

    /**
     * WordPress-Blog (Beitragskarten mit Titel-Link bzw. „Read More“, Seiten unter /page/N/): sammelt die Beitrags-Links
     * derselben Seite. Es gibt eine nächste Seite, solange die Seitenleiste eine höhere Seitenzahl nennt.
     */
    private fun parseWordPress(doc: Document, pageNumber: Int): Listing {
        val host = runCatching { java.net.URI(doc.location()).host?.removePrefix("www.") }.getOrNull()
        val candidates = ArrayList<org.jsoup.nodes.Element>()
        candidates += doc.select("article h1 a[href], article h2 a[href], article h3 a[href], .hentry h2 a[href], .hentry h3 a[href], .entry-title a[href], .post-title a[href]")
        candidates += doc.select("a[title=Read More][href], a.more-link[href], a.read-more[href]")
        candidates += doc.select("a[href]").filter { it.text().trim().equals("Read More", ignoreCase = true) }
        val links = LinkedHashMap<String, String>()
        for (a in candidates) {
            val abs = a.absUrl("href")
            if (abs.isEmpty()) continue
            val clean = cleanLink(abs)
            val h = runCatching { java.net.URI(clean).host?.removePrefix("www.") }.getOrNull()
            if (h != host || WP_SKIP.containsMatchIn(clean)) continue
            // Startseite selbst ist kein Beitrag
            if (runCatching { java.net.URI(clean).path.orEmpty() }.getOrDefault("").trim('/').isEmpty() && "?" !in clean) continue
            links.putIfAbsent(ThreadBuilder.threadKey(clean), clean)
        }
        val nextExplicit = PageParser.nextUrl(doc)?.let { cleanLink(it) }
            ?: doc.select("a.next[href], a.next.page-numbers[href]").firstOrNull()?.absUrl("href")?.takeIf { it.isNotEmpty() }?.let { cleanLink(it) }
        val highest = doc.select("a[href*=/page/]").mapNotNull { Regex("""/page/(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        val next = nextExplicit ?: if (links.isNotEmpty() && highest > pageNumber) {
            val base = firstPageUrl(doc.location()).substringBefore('?').trimEnd('/')
            "$base/page/${pageNumber + 1}/"
        } else null
        return Listing(links.values.toList(), next)
    }
}
