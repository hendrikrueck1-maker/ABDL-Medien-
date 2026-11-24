package de.hr.mediathek.data.story.web

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Liest eine Webseite: zuerst als Forum (vBulletin-ähnlich: Beiträge mit id="post_message_…"), sonst als Artikel/Blog.
 * Enthält kein Android und keinen Netzzugriff, damit es mit gespeichertem HTML getestet werden kann.
 */
object PageParser {
    fun parse(doc: Document): WebPage {
        val forum = parseForum(doc)
        if (forum.posts.isNotEmpty()) return forum
        val article = parseArticle(doc)
        if (article != null) return article
        return WebPage(pageTitle(doc), emptyList(), null, looksLikeLogin(doc), "forum")
    }

    // ---------------------------------------------------------------- Forum

    private val POST_ID = Regex("""(?i)^post_?\d+$""")
    private val DIGITS = Regex("""\d+""")

    private fun parseForum(doc: Document): WebPage {
        val messages = doc.select("[id^=post_message_]")
        val posts = ArrayList<WebPost>()
        for ((index, msg) in messages.withIndex()) {
            val postId = msg.id().removePrefix("post_message_")
            if (postId.isEmpty()) continue
            val container = postContainer(msg)
            val text = plainText(msg)
            if (text.isBlank()) continue
            posts += WebPost(
                id = postId,
                number = postNumber(container) ?: (index + 1),
                author = author(container),
                date = postDate(container, msg),
                title = postTitle(container, msg),
                text = text,
            )
        }
        return WebPage(threadTitle(doc), posts, nextUrl(doc), posts.isEmpty() && looksLikeLogin(doc), "forum")
    }

    /** Der Kasten eines Beitrags (Tabelle oder Listeneintrag), in dem Datum, Autor und Text zusammen liegen. */
    private fun postContainer(msg: Element): Element {
        var depth = 0
        var current: Element? = msg.parent()
        var top: Element? = null
        while (current != null && depth < 10 && current.tagName() != "body") {
            val idOk = POST_ID.matches(current.id())
            val classOk = current.classNames().any { it == "postbit" || it == "postcontainer" || it == "post" }
            if (idOk || classOk) return current
            // Oberster Vorfahr, der genau einen Beitragstext enthält (Rückfall, wenn die Seite keine bekannten Kennungen hat).
            if (current.select("[id^=post_message_]").size == 1) top = current
            current = current.parent()
            depth++
        }
        return top ?: msg
    }

    private fun postNumber(container: Element): Int? {
        val el = container.selectFirst("a[id^=postcount], .postcounter, a.postcounter") ?: return null
        return DIGITS.find(el.text())?.value?.toIntOrNull()
    }

    private fun author(container: Element): String? {
        val el = container.selectFirst("a.bigusername, a.username, .bigusername, .username, [itemprop=author]") ?: return null
        return el.text().trim().ifEmpty { null }
    }

    private fun postDate(container: Element, msg: Element): WebDate? {
        container.selectFirst("time[datetime]")?.let { WebDates.parseMachine(it.attr("datetime")) }?.let { return it }
        // Nur Text vor dem Beitragstext durchsuchen, damit Datumsangaben im Text oder in Fußbereichen nicht zählen.
        val before = textBefore(container, msg)
        WebDates.findIn(before)?.let { return it }
        return container.selectFirst(".date, .postdate, .time")?.let { WebDates.findIn(it.text()) }
    }

    private fun textBefore(container: Element, msg: Element): String {
        val sb = StringBuilder()
        var stop = false
        fun walk(node: Node) {
            if (stop) return
            if (node === msg) { stop = true; return }
            if (node is TextNode) sb.append(node.text()).append(' ')
            for (child in node.childNodes()) walk(child)
        }
        walk(container)
        return sb.toString()
    }

    private fun postTitle(container: Element, msg: Element): String? {
        val el = container.selectFirst("h2.title, .posttitle, td[id^=td_post_] > div.smallfont > strong, td[id^=td_post_] > div.smallfont > b")
        val text = el?.text()?.trim().orEmpty()
        return text.ifEmpty { null }
    }

    private fun threadTitle(doc: Document): String? {
        val candidates = listOf(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("h1.threadtitle, .threadtitle, span.threadtitle a")?.text(),
            doc.selectFirst("td.navbar strong")?.text(),
            pageTitle(doc),
        )
        return candidates.map { it?.trim().orEmpty() }.firstOrNull { it.isNotEmpty() }?.let { cleanTitle(it) }
    }

    private fun pageTitle(doc: Document): String? = cleanTitle(doc.title(), stripSite = true).ifEmpty { null }

    private val SITE_SUFFIX = Regex("""\s+[-–—|»«]\s+(?:(?!\s[-–—|»«]\s).){2,60}$""")
    private val PAGE_SUFFIX = Regex("""(?i)\s*[-–—|]?\s*(seite|page)\s+\d+(\s+(von|of)\s+\d+)?\s*$""")

    /**
     * "Thema: Leere" → "Leere"; ein Seitenhinweis wie "Seite 2" fällt weg. Mit [stripSite] (nur für den Seitentitel des
     * Browsers) auch der Name der Seite: "Leere - WB-Community" → "Leere".
     */
    fun cleanTitle(raw: String, stripSite: Boolean = false): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("""(?i)^thema:\s*"""), "")
        t = t.replace(PAGE_SUFFIX, "")
        if (stripSite && t.length > 8) t = t.replace(SITE_SUFFIX, "")
        t = t.replace(PAGE_SUFFIX, "")
        return t.trim()
    }

    fun nextUrl(doc: Document): String? {
        val rel = doc.selectFirst("a[rel=next][href], link[rel=next][href]")
        if (rel != null) return rel.absUrl("href").ifEmpty { null }
        val arrow = doc.select(".pagenav a[href], .pagination a[href], .pageNav a[href]")
            .firstOrNull { it.text().trim() in setOf(">", "›", "»", "Weiter", "Nächste", "Next") }
        return arrow?.absUrl("href")?.ifEmpty { null }
    }

    private fun looksLikeLogin(doc: Document): Boolean {
        if (doc.select("input[name=vb_login_username], input[name=login], form[action*=login]").isNotEmpty() &&
            doc.select("[id^=post_message_]").isEmpty()
        ) {
            val text = doc.body().text()
            return Regex("""(?i)nicht angemeldet|bitte anmelden|nicht berechtigt|keine berechtigung|registrieren|must be logged|log in""").containsMatchIn(text)
        }
        return false
    }

    // -------------------------------------------------------------- Artikel

    private val ARTICLE_BODY = listOf(
        "[itemprop=articleBody]", "article .entry-content", ".entry-content", ".post-content", ".post-body", ".article-body",
        ".article__body", "article", "main",
    )

    private fun parseArticle(doc: Document): WebPage? {
        val body = ARTICLE_BODY.firstNotNullOfOrNull { sel -> doc.selectFirst(sel)?.takeIf { plainText(it).length > 200 } }
            ?: largestTextBlock(doc)
            ?: return null
        val text = plainText(body)
        if (text.length < 200) return null
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.title().let { cleanTitle(it, stripSite = true) }).let { cleanTitle(it) }.ifEmpty { "Ohne Titel" }
        val date = articleDate(doc, body)
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("[rel=author], [itemprop=author], .author")?.text()?.trim()?.ifEmpty { null }
        val post = WebPost("article", 1, author, date, null, text)
        return WebPage(title, listOf(post), null, false, "article")
    }

    private fun articleDate(doc: Document, body: Element): WebDate? {
        for (sel in listOf(
            "meta[property=article:published_time]", "meta[itemprop=datePublished]", "meta[name=date]", "meta[name=DC.date.issued]",
        )) {
            doc.selectFirst(sel)?.let { WebDates.parseMachine(it.attr("content")) }?.let { return it }
        }
        for (script in doc.select("script[type=application/ld+json]")) {
            Regex(""""datePublished"\s*:\s*"([^"]+)"""").find(script.data())?.let { m ->
                WebDates.parseMachine(m.groupValues[1])?.let { return it }
            }
        }
        (doc.selectFirst("time[datetime]") ?: body.selectFirst("time[datetime]"))?.let {
            WebDates.parseMachine(it.attr("datetime"))?.let { d -> return d }
        }
        return doc.selectFirst("time, .date, .post-date, .entry-date, .published")?.let { WebDates.findIn(it.text()) }
    }

    private fun paragraphLength(el: Element): Int = el.children().filter { it.tagName() == "p" }.sumOf { it.text().length }

    private fun largestTextBlock(doc: Document): Element? =
        doc.select("div, section").maxByOrNull { paragraphLength(it) }?.takeIf { paragraphLength(it) > 400 }

    // ------------------------------------------------------------ Textumbau

    private val BLOCKS = setOf(
        "p", "div", "blockquote", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "section",
        "article", "header", "footer", "dd", "dt", "dl",
    )

    /** Beitragstext als Klartext: Absätze und Zeilenumbrüche bleiben, Zitate, Signaturen und Bilder fallen weg. */
    fun plainText(source: Element): String {
        val el = source.clone()
        el.select("script, style, noscript, img, iframe, form, button, .signature, .postbit-attachment, .attachments, .edit, .after_content").remove()
        // vBulletin 4: Zitate liegen in div.bbcode_container > div.bbcode_quote
        for (q in el.select("div.bbcode_quote, .quote")) {
            val parent = q.parent()
            if (parent != null && parent.hasClass("bbcode_container")) parent.remove() else q.remove()
        }
        // vBulletin 3: Zitate beginnen mit einem Kasten "Zitat:" im äußeren div
        for (label in el.select("div.smallfont")) {
            if (Regex("""(?i)^\s*(zitat|quote)\b.*""").matches(label.ownText().trim()) || label.text().trim().equals("Zitat:", true)) {
                val parent = label.parent()
                if (parent != null && parent.tagName() == "div" && parent !== el) parent.remove()
            }
        }
        val sb = StringBuilder()
        render(el, sb)
        return tidy(sb.toString())
    }

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private fun lineBreak(sb: StringBuilder) {
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
    }

    private fun paragraphBreak(sb: StringBuilder) {
        if (sb.isEmpty()) return
        lineBreak(sb)
        if (sb.length < 2 || sb[sb.length - 2] != '\n') sb.append('\n')
    }

    private fun render(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val tag = node.tagName()
                if (tag == "br") { sb.append('\n'); return }
                val block = tag in BLOCKS
                val paragraph = tag == "p" || tag == "blockquote" || tag in HEADINGS
                if (block) { if (paragraph) paragraphBreak(sb) else lineBreak(sb) }
                if (tag == "li") sb.append("• ")
                for (child in node.childNodes()) render(child, sb)
                if (block) { if (paragraph) paragraphBreak(sb) else lineBreak(sb) }
            }
        }
    }

    private fun tidy(raw: String): String {
        val lines = raw.replace(' ', ' ').replace("\r", "").split('\n').map { it.trim() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) { blank++; continue }
            if (out.isNotEmpty()) out.append(if (blank >= 1) "\n\n" else "\n")
            out.append(line)
            blank = 0
        }
        return out.toString().trim()
    }
}
