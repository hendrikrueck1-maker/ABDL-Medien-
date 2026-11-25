package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Formate der Geschichten-PWA (Repo „Geschichten“):
 * - Katalog: data/index.json (Liste mit id, title, language, platform, date, seriesId/seriesTitle/seriesPos/seriesTotal)
 *   plus data/shard_N.json (Objekt id → Text),
 * - eigene Geschichten: user-stories/(Dateien .json) oder customStories der Sicherung ({id, title, text, language, platform, date}),
 * - Sicherung geschichten-backup.json: {ratings, read, tags, wip, customStories, progress, translations, …}.
 */
object PwaFormat {
    const val BACKUP_NAME = "geschichten-backup.json"

    /** "m" / "w" / "mix" aus den PWA-Tags. */
    fun genderToCategory(g: String?): StoryCategory? = when (g) {
        "m" -> StoryCategory.MEN
        "w" -> StoryCategory.WOMEN
        "mix" -> StoryCategory.MIXED
        else -> null
    }

    private fun str(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).trim().ifEmpty { null }

    private fun languageTags(lang: String?): List<String> =
        if (lang == null || lang.equals("Deutsch", ignoreCase = true)) emptyList() else listOf(lang)

    private fun seriesOf(o: JSONObject): SeriesRef? {
        val name = str(o, "seriesTitle") ?: str(o, "seriesId") ?: return null
        val pos = if (o.has("seriesPos") && !o.isNull("seriesPos")) o.optInt("seriesPos", -1).takeIf { it >= 0 } else null
        return SeriesRef(name, pos)
    }

    /** Beschreibt den Katalog als Liste; der Text wird erst beim Parsen aus dem passenden Shard geholt. */
    fun catalogItems(indexJson: String, shard: (Int) -> Map<String, String>?, now: Long): List<ImportItem> {
        val arr = JSONArray(indexJson.removePrefix("\uFEFF"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id") ?: return@mapNotNull null
            val title = str(o, "title") ?: id
            ImportItem("$id ($title)") {
                val text = shard(o.optInt("shard", -1))?.get(id)
                    ?: return@ImportItem ParseResult.Fail("Text fehlt im Shard ${o.optInt("shard", -1)}")
                val r = StoryBuilder.build(
                    id,
                    mapOf("title" to title, "date" to str(o, "date"), "status" to "published", "tags" to languageTags(str(o, "language"))),
                    text, now,
                )
                if (r is ParseResult.Ok) {
                    ParseResult.Ok(r.story.copy(sourceText = str(o, "platform"), series = seriesOf(o), preserveUserData = true), r.warnings)
                } else r
            }
        }
    }

    /** Parst Shard-Dateien (Objekt id → Text). */
    fun parseShard(json: String): Map<String, String>? = try {
        val o = JSONObject(json.removePrefix("\uFEFF"))
        o.keys().asSequence().associateWith { o.optString(it) }
    } catch (e: Exception) {
        null
    }

    /** Eine eigene Geschichte ({id, title, text, language, platform, date}). */
    fun customStory(
        name: String, o: JSONObject, now: Long,
        rating: Int? = null, gender: String? = null, wip: Boolean? = null, ai: Boolean = false,
    ): ParseResult {
        val id = str(o, "id") ?: Slugs.fromFileName(name)
        val tags = languageTags(str(o, "language")) + if (ai) listOf("KI") else emptyList()
        val r = StoryBuilder.build(
            id,
            mapOf(
                "title" to str(o, "title"), "date" to str(o, "date"), "status" to "published", "tags" to tags,
                "category" to when (genderToCategory(gender)) {
                    StoryCategory.MEN -> "men"; StoryCategory.WOMEN -> "women"; else -> "mixed"
                },
            ),
            o.optString("text"), now,
        )
        return if (r is ParseResult.Ok) {
            ParseResult.Ok(
                r.story.copy(sourceText = str(o, "platform"), rating = rating?.takeIf { it in 1..5 }, continueFlag = wip), r.warnings,
            )
        } else r
    }

    /** True, wenn das Objekt wie eine Sicherung der PWA aussieht. */
    fun isBackup(o: JSONObject) = listOf("ratings", "read", "tags", "wip", "customStories", "progress", "translations").any { o.has(it) }

    /**
     * Sicherung: eigene Geschichten werden vollständig angelegt, für alle anderen IDs mit Bewertung, Geschlecht-Tag oder
     * Weiterschreiben-Markierung entsteht ein Metadaten-Patch (die Geschichte muss schon in der App sein).
     */
    fun backupItems(o: JSONObject, now: Long): Pair<List<ImportItem>, List<ImportIssue>> {
        val ratings = o.optJSONObject("ratings") ?: JSONObject()
        val tags = o.optJSONObject("tags") ?: JSONObject()
        val wip = o.optJSONObject("wip") ?: JSONObject()
        val custom = o.optJSONArray("customStories") ?: JSONArray()

        fun meta(id: String): Triple<Int?, String?, Boolean?> {
            val t = tags.optJSONObject(id)
            return Triple(
                if (ratings.has(id)) ratings.optInt(id, 0) else null,
                t?.let { str(it, "gender") },
                if (wip.has(id)) wip.optBoolean(id, false) else null,
            )
        }
        fun ai(id: String) = tags.optJSONObject(id)?.optBoolean("ai", false) == true

        val items = ArrayList<ImportItem>()
        val customIds = HashSet<String>()
        for (i in 0 until custom.length()) {
            val c = custom.optJSONObject(i)
            val id = c?.let { str(it, "id") }
            if (c == null || id == null) {
                items += ImportItem("Eigene Geschichte ${i + 1}") { ParseResult.Fail("Eintrag ohne id") }
                continue
            }
            customIds += id
            val (r, g, w) = meta(id)
            items += ImportItem("$id (${str(c, "title") ?: "ohne Titel"})") { customStory(id, c, now, r, g, w, ai(id)) }
        }
        val ids = LinkedHashSet<String>()
        for (obj in listOf(ratings, tags, wip)) obj.keys().forEach { ids += it }
        for (id in ids.sorted()) {
            if (id in customIds) continue
            val (r, g, w) = meta(id)
            val patch = StoryPatch(
                slug = id,
                rating = r?.takeIf { it in 1..5 },
                category = genderToCategory(g),
                continueFlag = w?.takeIf { it },
                addTags = if (ai(id)) listOf("KI") else emptyList(),
            )
            if (patch.rating == null && patch.category == null && patch.continueFlag == null && patch.addTags.isEmpty()) continue
            items += ImportItem(id) { ParseResult.Patch(patch) }
        }
        val notes = ArrayList<ImportIssue>()
        val skipped = listOf("read" to "gelesen-Markierungen", "progress" to "Lesefortschritt", "translations" to "gespeicherte Übersetzungen")
            .filter { (k, _) -> (o.optJSONObject(k)?.length() ?: 0) > 0 }.map { it.second }
        if (skipped.isNotEmpty()) notes += ImportIssue(BACKUP_NAME, "Nicht übernommen: ${skipped.joinToString(", ")}")
        return items to notes
    }
}
