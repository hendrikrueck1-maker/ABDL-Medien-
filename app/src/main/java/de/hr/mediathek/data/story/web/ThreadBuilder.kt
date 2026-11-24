package de.hr.mediathek.data.story.web

import java.net.URI

/** Fasst die gelesenen Seiten eines Links zu einem Thema mit vorgewählten Kapiteln zusammen. */
object ThreadBuilder {
    private val KEY_PATTERNS = listOf(
        Regex("""showthread\.php\?(?:[^#]*?[&?])?(?:t|threadid)=(\d+)"""),
        Regex("""showthread\.php\?(\d+)"""),
        Regex("""/threads?/[^/?#]*?[.-](\d+)(?:/|$|\?|#)"""),
        Regex("""[?&](?:t|topic|tid)=(\d+)"""),
        Regex("""/(?:t|topic)/[^/?#]*/(\d+)"""),
    )

    fun hostOf(url: String): String = try {
        URI(url).host.orEmpty().removePrefix("www.").ifEmpty { "web" }
    } catch (e: Exception) {
        "web"
    }

    /** Stabile Kennung des Themas (Thema-Nummer des Forums), damit erneutes Importieren nichts doppelt anlegt. */
    fun threadKey(url: String): String {
        for (p in KEY_PATTERNS) p.find(url)?.let { return it.groupValues[1] }
        val path = try {
            URI(url).let { (it.host.orEmpty() + it.path.orEmpty()).trimEnd('/') }
        } catch (e: Exception) {
            url
        }
        return "u" + Integer.toHexString(path.hashCode())
    }

    fun slug(thread: WebThread, part: WebPart): String =
        ("web-" + thread.host + "-" + thread.key + "-" + part.id).lowercase().replace(Regex("[^a-z0-9.-]+"), "-").trim('-')

    fun build(url: String, pages: List<WebPage>): WebThread {
        val first = pages.first()
        val posts = pages.flatMap { it.posts }.distinctBy { it.id }
        val warnings = ArrayList<String>()
        val host = hostOf(url)
        val title = first.threadTitle?.ifBlank { null } ?: "Ohne Titel"
        val forum = first.kind == "forum"
        val opAuthor = posts.firstOrNull()?.author
        val parts = posts.mapIndexed { i, p ->
            WebPart(
                id = p.id,
                author = p.author,
                dateMillis = p.date?.millis,
                dateRelative = p.date?.relative == true,
                postTitle = p.title,
                text = p.text,
                selected = when {
                    !forum -> true
                    opAuthor != null -> p.author == opAuthor
                    else -> i == 0
                },
            )
        }
        if (parts.any { it.dateRelative }) {
            warnings += "Bei einigen Beiträgen steht im Forum nur „Heute“ oder „Gestern“. Das Datum wurde daraus berechnet und sollte geprüft werden."
        }
        if (parts.any { it.dateMillis == null }) {
            warnings += "Bei manchen Beiträgen wurde kein Datum gefunden. Du kannst es in der Vorschau eintragen."
        }
        if (forum && posts.isNotEmpty() && (posts.first().number ?: 1) > 1) {
            warnings += "Der Link zeigt nicht die erste Seite des Themas, der Anfang fehlt vielleicht."
        }
        if (forum && opAuthor == null && posts.size > 1) {
            warnings += "Der Verfasser der Beiträge wurde nicht erkannt. Nur der erste Beitrag ist vorgewählt."
        }
        return WebThread(url, host, threadKey(url), title, first.kind, pages.size, parts, warnings)
    }
}
