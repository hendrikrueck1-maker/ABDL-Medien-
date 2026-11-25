package de.hr.mediathek.data.story.mdimport

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** Liest eine Markdown-Datei mit YAML-Frontmatter (zwischen zwei "---"-Zeilen). */
object MarkdownStoryParser {
    class Split(val yaml: String, val body: String)

    /** Trennt Frontmatter und Text. Der Text bleibt unverändert; null, wenn kein (geschlossenes) Frontmatter da ist. */
    fun split(raw: String): Split? {
        val text = raw.removePrefix("\uFEFF")
        var pos = 0
        fun nextLine(): Pair<String, Int>? {
            if (pos > text.length || (pos == text.length)) return null
            val nl = text.indexOf('\n', pos)
            val end = if (nl < 0) text.length else nl
            val line = text.substring(pos, end).trimEnd('\r', ' ', '\t')
            val next = if (nl < 0) text.length else nl + 1
            return line to next
        }
        val first = nextLine() ?: return null
        if (first.first != "---") return null
        pos = first.second
        val yamlStart = pos
        while (true) {
            val lineStart = pos
            val (line, next) = nextLine() ?: return null
            if (line == "---") return Split(text.substring(yamlStart, lineStart), text.substring(next))
            pos = next
        }
    }

    fun parse(fileName: String, raw: String, now: Long = System.currentTimeMillis()): ParseResult {
        val slug = Slugs.fromFileName(fileName)
        val parts = split(raw) ?: return ParseResult.Fail(
            if (raw.removePrefix("\uFEFF").trimStart().startsWith("---")) "Frontmatter ist nicht mit --- geschlossen"
            else "Kein YAML-Frontmatter am Dateianfang",
        )
        val data: Any? = try {
            Load(LoadSettings.builder().build()).loadFromString(parts.yaml)
        } catch (e: Exception) {
            val msg = (e.message ?: "unbekannter Fehler").lineSequence().firstOrNull { it.isNotBlank() } ?: "unbekannter Fehler"
            return ParseResult.Fail("YAML ungültig: ${msg.trim().take(160)}")
        }
        val fields: Map<String, Any?> = when (data) {
            null -> emptyMap()
            is Map<*, *> -> data.entries.associate { it.key.toString() to it.value }
            else -> return ParseResult.Fail("Frontmatter ist kein YAML-Objekt")
        }
        return StoryBuilder.build(slug, fields, parts.body, now)
    }
}
