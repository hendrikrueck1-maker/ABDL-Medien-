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

    /** Abfrage-Parameter, die typischerweise eine Geschichte oder einen Beitrag benennen (Blogs, Archive). */
    private val ID_PARAMS = listOf("p", "id", "sid", "storyid", "story", "article", "aid", "post")

    /** Parameter, die nur Ansicht/Sitzung betreffen und nicht zur Kennung gehören. */
    private val IGNORED_PARAMS = setOf("page", "s", "highlight", "sort", "order", "pp", "mode", "view", "fbclid", "gclid", "ref")

    /**
     * Stabile Kennung des Themas bzw. der Seite, damit erneutes Importieren nichts doppelt anlegt und verschiedene
     * Seiten nie dieselbe Kennung bekommen: zuerst die Thema-Nummer eines Forums, dann bekannte ID-Parameter
     * (z. B. `?p=123` bei Blogs, `?sid=55` bei Archiven), sonst Host + Pfad + übrige Parameter.
     */
    fun threadKey(url: String): String {
        for (p in KEY_PATTERNS) p.find(url)?.let { return it.groupValues[1] }
        val uri = try { URI(url) } catch (e: Exception) { null }
        val params = queryParams(uri?.rawQuery)
        for (name in ID_PARAMS) {
            params[name]?.takeIf { it.isNotBlank() }?.let { value ->
                val clean = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
                if (clean.isNotEmpty() && clean.length <= 24) return name + clean
            }
        }
        val rest = params.filterKeys { k -> k !in IGNORED_PARAMS && !k.startsWith("utm_") }.toSortedMap()
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        val base = if (uri != null) (uri.host.orEmpty() + uri.path.orEmpty()).trimEnd('/') else url.substringBefore('#')
        return "u" + Integer.toHexString((if (rest.isEmpty()) base else "$base?$rest").hashCode())
    }

    /** Abfrage-Parameter als Map (Name klein geschrieben); Sitzungs-IDs (32 Hex-Zeichen) werden ignoriert. */
    private fun queryParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            if (name == "sid" && Regex("[0-9a-fA-F]{32}").matches(value)) continue
            out.putIfAbsent(name, value)
        }
        return out
    }

    /** Bei Foren-Themenlinks die Seitenangabe entfernen (Seite 1); andere Links bleiben unverändert. */
    fun forumFirstPage(url: String): String {
        val forum = KEY_PATTERNS.take(3).any { it.containsMatchIn(url) }
        return if (forum) ListingParser.firstPageUrl(url) else url
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
