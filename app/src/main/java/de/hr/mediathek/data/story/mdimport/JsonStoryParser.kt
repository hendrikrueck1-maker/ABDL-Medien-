package de.hr.mediathek.data.story.mdimport

import org.json.JSONArray
import org.json.JSONObject

/** Liest ein JSON-Array [{title, author?, category, tags?, content, createdAt?, status?}] (optional auch slug, updatedAt, publishedAt). */
object JsonStoryParser {
    fun parseArray(text: String): JSONArray? = try {
        JSONArray(text.removePrefix("\uFEFF"))
    } catch (e: Exception) {
        null
    }

    private fun toPlain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until v.length()).map { toPlain(v.opt(it)) }
        is JSONObject -> v.keys().asSequence().associateWith { toPlain(v.opt(it)) }
        else -> v
    }

    fun parseElement(index: Int, element: Any?, now: Long = System.currentTimeMillis()): ParseResult {
        val obj = element as? JSONObject ?: return ParseResult.Fail("Eintrag ist kein Objekt")
        val fields = obj.keys().asSequence().associateWith { toPlain(obj.opt(it)) }
        val content = fields["content"]?.toString() ?: ""
        val title = fields["title"]?.toString()?.trim().orEmpty()
        val given = (fields["slug"] ?: fields["id"])?.toString()?.trim().orEmpty()
        val slug = given.ifEmpty { Slugs.fromTitle(title) }.ifEmpty { "eintrag-${index + 1}" }
        return StoryBuilder.build(slug, fields, content, now)
    }
}
