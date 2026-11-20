package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.tag.TagNames
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date

/** Gemeinsame Abbildung der Felder (Frontmatter oder JSON) auf [ParsedStory]. */
object StoryBuilder {
    private val NAME_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[-_ ]|$)")

    /** Datum aus einem Dateinamen der Form JJJJ-MM-TT-…, sonst null. */
    fun dateFromName(slug: String): Long? {
        val m = NAME_DATE.find(slug) ?: return null
        return try {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    fun parseTime(value: Any?): Long? = when (value) {
        null -> null
        is Date -> value.time
        is Number -> value.toLong()
        else -> {
            val s = value.toString().trim()
            if (s.isEmpty()) null else runCatching { Instant.parse(s).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
                .recoverCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
                .recoverCatching { LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
                .getOrNull()
        }
    }

    fun mapCategory(raw: String?): StoryCategory? = when (raw?.trim()?.lowercase()) {
        null, "" -> StoryCategory.MIXED
        "men", "man", "m", "männer", "maenner", "mann", "männlich" -> StoryCategory.MEN
        "women", "woman", "f", "w", "frauen", "frau", "weiblich" -> StoryCategory.WOMEN
        "mixed", "mix", "gemischt", "divers" -> StoryCategory.MIXED
        else -> null
    }

    private fun scalar(v: Any?): String? = when (v) {
        null -> null
        is Map<*, *>, is Iterable<*>, is Array<*> -> null
        else -> v.toString().trim().ifEmpty { null }
    }

    private fun tagList(v: Any?): List<String> {
        val raw = when (v) {
            null -> emptyList()
            is Iterable<*> -> v.mapNotNull { scalar(it) }
            is Array<*> -> v.mapNotNull { scalar(it) }
            else -> scalar(v)?.split(',')?.map { it.trim() } ?: emptyList()
        }
        return raw.map { it.removePrefix("#").trim().replace(Regex("\\s+"), " ").take(TagNames.MAX) }
            .filter { it.isNotEmpty() }
    }

    /**
     * [fields] sind die Rohwerte (title, category, tags, status, author, createdAt, updatedAt, publishedAt),
     * [body] der Text. Fehlende Felder bekommen Standardwerte; ein fehlender Titel oder Text ist ein Fehler.
     */
    fun build(slug: String, fields: Map<String, Any?>, body: String, now: Long): ParseResult {
        val titleRaw = fields["title"]
        if (titleRaw is Map<*, *> || titleRaw is Iterable<*>) return ParseResult.Fail("Feld title ist kein Text")
        val title = scalar(titleRaw) ?: return ParseResult.Fail("Pflichtfeld title fehlt")
        if (body.isBlank()) return ParseResult.Fail("Text ist leer")
        if (slug.isBlank()) return ParseResult.Fail("Kein Dateiname/Kennung")

        val warnings = ArrayList<String>()
        val tags = ArrayList(tagList(fields["tags"]))

        val categoryRaw = scalar(fields["category"])
        val category = mapCategory(categoryRaw) ?: run {
            // Unbekannte Kategorie (z. B. "abenteuer"): Kategorie MIXED, der Wert bleibt als Tag erhalten.
            tags += categoryRaw!!.take(TagNames.MAX)
            StoryCategory.MIXED
        }
        val distinctTags = tags.distinctBy { TagNames.normalize(it) }

        fun time(key: String): Long? {
            val v = fields[key] ?: return null
            if (scalar(v) == null) return null
            val t = parseTime(v)
            if (t == null) warnings += "$key „${scalar(v)}“ ist kein gültiges Datum und wurde ignoriert"
            return t
        }

        val created = time("createdAt") ?: time("date") ?: dateFromName(slug) ?: now
        val updated = time("updatedAt") ?: created
        val published = time("publishedAt")
        val status = scalar(fields["status"])?.lowercase() ?: "draft"
        val author = scalar(fields["author"])
        val text = body.replace("\r\n", "\n")

        return ParseResult.Ok(
            ParsedStory(
                slug = slug, title = title, category = category, tags = distinctTags, status = status,
                author = author, createdAt = created, updatedAt = updated, publishedAt = published,
                body = text, hash = StoryHash.of(text),
            ),
            warnings,
        )
    }
}
