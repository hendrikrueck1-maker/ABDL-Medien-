package de.hr.mediathek.data.story.web

import org.jsoup.nodes.Document

/** Liest die Übersichtsseite eines Forum-Bereichs (vBulletin: Liste der Themen, mehrere Seiten) und sammelt die Themen-Links. */
object ListingParser {
    data class Listing(val links: List<String>, val nextUrl: String?)

    private val SESSION = Regex("""([?&])s=[0-9a-fA-F]{32}(&|$)""")
    private val PAGE_PARAM = Regex("""([?&])page=\d+(&|$)""")
    private val PAGE_PATH = Regex("""/page\d+""")

    /** Entfernt eine Seitenangabe, damit das Sammeln bei Seite 1 beginnt, auch wenn der Nutzer Seite 62 kopiert hat. */
    fun firstPageUrl(url: String): String {
        var u = url.trim()
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
}
