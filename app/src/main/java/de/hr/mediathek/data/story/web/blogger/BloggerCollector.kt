package de.hr.mediathek.data.story.web.blogger

import org.jsoup.Jsoup

/** Antwort eines Abrufs. code = HTTP-Status (0 = Netzwerkfehler), finalUrl = nach Weiterleitungen. */
data class HttpResult(val code: Int, val body: String?, val finalUrl: String? = null)

enum class BlogProblem {
    /** Eingabe ist keine Webadresse */
    INVALID_ADDRESS,
    /** Seite ist kein Blogger-Blog */
    NOT_BLOGGER,
    /** Blog nur für eingeladene Leser (verlangt Google-Anmeldung) */
    PRIVATE,
    /** Blog gelöscht oder nicht gefunden */
    NOT_FOUND,
    /** zu viele Anfragen (429), auch nach Wartezeiten */
    TOO_MANY_REQUESTS,
    /** Netzwerk- oder Serverfehler */
    NETWORK,
}

data class BlogResult(
    val input: String,
    val origin: String?,
    val label: String?,
    val blogTitle: String?,
    /** älteste zuerst, damit Serienteile in der richtigen Reihenfolge in der Link-Liste stehen */
    val posts: List<BloggerPost>,
    /** null = vollständig. Bei gesetztem Problem können trotzdem schon Beiträge gesammelt sein. */
    val problem: BlogProblem?,
    /** true = Feed war abgeschaltet, Links wurden aus den HTML-Seiten gesammelt (ohne Datum/Labels) */
    val viaHtml: Boolean = false,
)

data class CollectProgress(
    val blogIndex: Int,
    val blogCount: Int,
    val blogLabel: String,
    val found: Int,
    val total: Int?,
    /** gesetzt, solange nach einer 429-Antwort gewartet wird */
    val waitingMs: Long? = null,
)

data class CollectResult(val blogs: List<BlogResult>) {
    /** alle Beitragslinks, Blog für Blog, ohne Doppelte */
    val links: List<String> get() = blogs.flatMap { b -> b.posts.map { it.url } }.distinct()
    val failed: List<BlogResult> get() = blogs.filter { it.problem != null }
}

/**
 * Sammelt alle Beitragslinks eines oder mehrerer Blogger-Blogs.
 *
 * Weg 1 (Normalfall): Atom-Feed, 150 Beiträge je Abruf.
 * Weg 2 (Feed abgeschaltet): Startseite bzw. Label-Seite und "Ältere Posts" durchgehen.
 *
 * Abrufe und Pausen sind von außen übergeben, damit der Sammler mit dem HTTP-Client
 * der App (User-Agent, Größenlimit) läuft und sich ohne Netz testen lässt.
 */
class BloggerCollector(
    private val fetch: suspend (url: String) -> HttpResult,
    private val sleep: suspend (ms: Long) -> Unit,
    /** Pause zwischen zwei Abrufen beim selben Blog */
    private val pauseMs: Long = 1_500,
    /** Wartezeiten nach 429, danach gibt der Sammler für diesen Blog auf */
    private val backoffMs: List<Long> = listOf(30_000, 120_000, 300_000),
    private val pageSize: Int = BloggerFeed.PAGE_SIZE,
    /** Schutz gegen Endlosschleifen: 400 × 150 = 60.000 Beiträge je Blog */
    private val maxRequestsPerBlog: Int = 400,
) {

    suspend fun collect(
        inputs: List<String>,
        fullText: Boolean = false,
        onProgress: (CollectProgress) -> Unit = {},
    ): CollectResult {
        val cleaned = inputs.map { it.trim() }.filter { it.isNotEmpty() }
        // Dieselbe Adresse (Ziel) nur einmal sammeln
        val seenTargets = HashSet<BloggerFeed.Target>()
        val seenUrls = HashSet<String>()
        val results = ArrayList<BlogResult>()
        cleaned.forEachIndexed { i, input ->
            val target = BloggerFeed.target(input)
            if (target == null) {
                results += BlogResult(input, null, null, null, emptyList(), BlogProblem.INVALID_ADDRESS)
                return@forEachIndexed
            }
            if (!seenTargets.add(target)) return@forEachIndexed
            if (results.isNotEmpty()) sleep(pauseMs)
            val r = collectOne(input, target, fullText) { found, total, wait ->
                onProgress(CollectProgress(i + 1, cleaned.size, target.label?.let { "${target.origin} · $it" } ?: target.origin, found, total, wait))
            }
            // Beiträge, die schon über einen anderen Eintrag gesammelt wurden (z. B. Label + ganzer Blog), weglassen
            results += r.copy(posts = r.posts.filter { seenUrls.add(it.url) })
        }
        return CollectResult(results)
    }

    private suspend fun collectOne(
        input: String,
        target: BloggerFeed.Target,
        fullText: Boolean,
        progress: (found: Int, total: Int?, waitMs: Long?) -> Unit,
    ): BlogResult {
        val posts = LinkedHashMap<String, BloggerPost>()
        var title: String? = null
        var start = 1
        var requests = 0
        while (requests < maxRequestsPerBlog) {
            val res = getWithBackoff(BloggerFeed.feedUrl(target, start, fullText, pageSize)) { w -> progress(posts.size, null, w) }
            requests++
            if (res.code == 429) return done(input, target, title, posts, BlogProblem.TOO_MANY_REQUESTS)
            val page = if (res.code == 200 && res.body != null) BloggerFeed.parse(res.body) else null

            if (page == null || !page.isBlogger) {
                // Erste Seite kein Feed: Ursache klären (privat, kein Blogger, Feed aus, weg)
                if (start == 1) return diagnose(input, target, res, progress)
                return done(input, target, title, posts, BlogProblem.NETWORK)
            }
            title = title ?: page.blogTitle
            page.posts.forEach { posts.putIfAbsent(it.url, it) }
            progress(posts.size, page.totalResults, null)

            val got = page.posts.size
            val total = page.totalResults
            if (got == 0) break
            start += got
            if (total != null && start > total) break
            if (total == null && got < pageSize) break
            sleep(pauseMs)
        }
        return done(input, target, title, posts, null)
    }

    private fun done(
        input: String, target: BloggerFeed.Target, title: String?,
        posts: Map<String, BloggerPost>, problem: BlogProblem?, viaHtml: Boolean = false,
    ) = BlogResult(
        input, target.origin, target.label, title,
        // älteste zuerst; ohne Datum (HTML-Weg) bleibt die gefundene Reihenfolge umgedreht (Blog zeigt Neueste zuerst)
        if (viaHtml) posts.values.reversed() else posts.values.sortedWith(compareBy(nullsLast()) { it.publishedAt }),
        problem, viaHtml,
    )

    private suspend fun getWithBackoff(url: String, onWait: (Long) -> Unit): HttpResult {
        var res = safeFetch(url)
        for (wait in backoffMs) {
            if (res.code != 429) return res
            onWait(wait)
            sleep(wait)
            res = safeFetch(url)
        }
        return res
    }

    private suspend fun safeFetch(url: String): HttpResult =
        try { fetch(url) } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e // Abbruch des Auftrags durchreichen
            HttpResult(0, null)
        }

    /** Erste Feed-Seite fehlgeschlagen: Startseite (bzw. Label-Seite) laden und Ursache bestimmen. */
    private suspend fun diagnose(
        input: String,
        target: BloggerFeed.Target,
        feedRes: HttpResult,
        progress: (Int, Int?, Long?) -> Unit,
    ): BlogResult {
        if (feedRes.code == 401 || feedRes.code == 403 || isGoogleLogin(feedRes.finalUrl))
            return done(input, target, null, emptyMap(), BlogProblem.PRIVATE)
        sleep(pauseMs)
        val first = listingUrl(target)
        val home = getWithBackoff(first) { w -> progress(0, null, w) }
        if (home.code == 429) return done(input, target, null, emptyMap(), BlogProblem.TOO_MANY_REQUESTS)
        if (home.code == 401 || home.code == 403 || isGoogleLogin(home.finalUrl))
            return done(input, target, null, emptyMap(), BlogProblem.PRIVATE)
        if (home.code == 404 || home.code == 410) return done(input, target, null, emptyMap(), BlogProblem.NOT_FOUND)
        if (home.code != 200 || home.body == null) return done(input, target, null, emptyMap(), BlogProblem.NETWORK)
        val doc = Jsoup.parse(home.body, home.finalUrl ?: first)
        if (!BloggerPage.isBlogger(doc)) return done(input, target, null, emptyMap(), BlogProblem.NOT_BLOGGER)
        // Blogger, aber Feed abgeschaltet → Übersichtsseiten durchgehen
        return crawlHtml(input, target, doc, progress)
    }

    private suspend fun crawlHtml(
        input: String,
        target: BloggerFeed.Target,
        firstDoc: org.jsoup.nodes.Document,
        progress: (Int, Int?, Long?) -> Unit,
    ): BlogResult {
        val title = firstDoc.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: firstDoc.title().trim().takeIf { it.isNotEmpty() }
        val found = LinkedHashMap<String, BloggerPost>()
        val visited = HashSet<String>()
        var doc = firstDoc
        var requests = 1
        while (true) {
            visited += doc.location()
            val listing = BloggerPage.listing(doc)
            listing.links.forEach { url ->
                found.putIfAbsent(url, BloggerPost(url, null, null, url, "", null, null, null, emptyList(), null))
            }
            progress(found.size, null, null)
            val next = listing.olderPageUrl ?: break
            if (next in visited || requests >= maxRequestsPerBlog) break
            sleep(pauseMs)
            val res = getWithBackoff(next) { w -> progress(found.size, null, w) }
            requests++
            if (res.code == 429) return done(input, target, title, found, BlogProblem.TOO_MANY_REQUESTS, viaHtml = true)
            if (res.code != 200 || res.body == null) return done(input, target, title, found, BlogProblem.NETWORK, viaHtml = true)
            doc = Jsoup.parse(res.body, res.finalUrl ?: next)
        }
        return done(input, target, title, found, null, viaHtml = true)
    }

    private fun listingUrl(t: BloggerFeed.Target): String =
        if (t.label != null) "${t.origin}/search/label/${BloggerFeed.encodePathSegment(t.label)}" else "${t.origin}/"

    private fun isGoogleLogin(url: String?): Boolean =
        url != null && (url.contains("accounts.google.com") || url.contains("/ServiceLogin"))
}
