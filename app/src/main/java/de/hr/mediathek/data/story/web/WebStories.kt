package de.hr.mediathek.data.story.web

import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.mdimport.ParsedStory
import de.hr.mediathek.data.story.mdimport.SeriesRef
import de.hr.mediathek.data.story.mdimport.StoryHash

/** Macht aus den gewählten Kapiteln eines Links fertige Geschichten (mit stabiler Kennung, Serie, Quelle und Link). */
object WebStories {
    /** [chosen]: Kapitel mit dem Datum, das gespeichert werden soll (null = kein Datum). */
    fun parsed(thread: WebThread, title: String, category: StoryCategory, chosen: List<Pair<WebPart, Long?>>, now: Long): List<ParsedStory> {
        val multiple = chosen.size > 1
        return chosen.mapIndexed { i, (part, date) ->
            val n = i + 1
            ParsedStory(
                slug = ThreadBuilder.slug(thread, part),
                title = partTitle(title, part, n, multiple),
                category = category,
                tags = emptyList(),
                status = "published",
                author = part.author,
                createdAt = now,
                updatedAt = now,
                publishedAt = date,
                body = part.text,
                hash = StoryHash.of(part.text),
                sourceText = thread.host,
                sourceUrl = thread.url,
                series = if (multiple) SeriesRef(title, n) else null,
            )
        }
    }

    fun partTitle(title: String, part: WebPart, n: Int, multiple: Boolean): String {
        if (!multiple) return title
        val own = part.postTitle?.trim().orEmpty()
        return if (own.isEmpty() || own.equals(title, ignoreCase = true) || own.startsWith("Re:", ignoreCase = true)) "$title – Teil $n" else own
    }

    /** Alle Links aus einem eingefügten Text (eine pro Zeile oder durch Leerzeichen getrennt), ohne Doppelte, in der Reihenfolge. */
    fun extractLinks(text: String): List<String> =
        Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ';') }.distinct().toList()
}
