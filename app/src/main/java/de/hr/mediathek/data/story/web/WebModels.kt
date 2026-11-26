package de.hr.mediathek.data.story.web

/** Ein Beitrag (Forum) bzw. der Artikel (Blog) einer Seite. */
data class WebPost(
    val id: String,
    val number: Int?,
    val author: String?,
    val date: WebDate?,
    val title: String?,
    val text: String,
    /** Schlagwörter der Seite (bei Blogger: Labels); werden beim ersten Import als Tags übernommen. */
    val tags: List<String> = emptyList(),
)

/** Eine gelesene Seite. [nextUrl] ist die nächste Seite eines mehrseitigen Themas, sonst null. */
data class WebPage(
    val threadTitle: String?,
    val posts: List<WebPost>,
    val nextUrl: String?,
    val loginRequired: Boolean,
    /** "forum" (vBulletin o. ä.) oder "article" (Blog/Artikel). */
    val kind: String,
)

/** Ein Kapitel in der Vorschau. */
data class WebPart(
    val id: String,
    val author: String?,
    val dateMillis: Long?,
    val dateRelative: Boolean,
    val postTitle: String?,
    val text: String,
    val selected: Boolean,
    val tags: List<String> = emptyList(),
)

/** Alles, was zu einem Link gelesen wurde. */
data class WebThread(
    val url: String,
    val host: String,
    val key: String,
    val title: String,
    val kind: String,
    val pages: Int,
    val parts: List<WebPart>,
    val warnings: List<String>,
)
