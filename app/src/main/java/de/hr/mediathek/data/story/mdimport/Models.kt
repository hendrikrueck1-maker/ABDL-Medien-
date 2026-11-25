package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import java.security.MessageDigest
import java.util.Locale

/** Eine gelesene Geschichte, fertig zum Speichern. Zeiten in Millisekunden (UTC). */
data class ParsedStory(
    val slug: String,
    val title: String,
    val category: StoryCategory,
    val tags: List<String>,
    val status: String,
    val author: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val publishedAt: Long?,
    val body: String,
    val hash: String,
    /** Quelle/Plattform (z. B. "Germandiaperstories"); null bei Markdown-Dateien. */
    val sourceText: String? = null,
    /** Bewertung 1–5, wenn die Quelle eine kennt; null = nicht anfassen. */
    val rating: Int? = null,
    /** "Weiterschreiben"-Markierung, wenn die Quelle sie kennt; null = nicht anfassen. */
    val continueFlag: Boolean? = null,
    val series: SeriesRef? = null,
    /** true (Katalog der Geschichten-PWA): beim erneuten Import Kategorie, Tags und Bewertung der App nicht überschreiben. */
    val preserveUserData: Boolean = false,
    /** Beim Aktualisieren den Titel in der App behalten (Web-Import: eigener Titel bleibt). */
    val keepTitle: Boolean = false,
    /** Favorit (aus einem App-Export); null = unverändert. */
    val favorite: Boolean? = null,
    /** Link zur Originalseite (Web-Import, Export/Import zwischen Geräten); null = nicht anfassen. */
    val sourceUrl: String? = null,
)

/** Zugehörigkeit zu einer Serie (mehrteilige Geschichte). [position] ist die laufende Nummer des Teils. */
data class SeriesRef(val name: String, val position: Int?)

/** Nur Metadaten einer bereits importierten Geschichte ändern (z. B. Bewertung aus der PWA-Sicherung). */
data class StoryPatch(
    val slug: String,
    val rating: Int? = null,
    val category: StoryCategory? = null,
    val continueFlag: Boolean? = null,
    val addTags: List<String> = emptyList(),
)

sealed interface ParseResult {
    data class Ok(val story: ParsedStory, val warnings: List<String> = emptyList()) : ParseResult
    data class Patch(val patch: StoryPatch) : ParseResult
    data class Fail(val reason: String) : ParseResult
}

/** Ein Eintrag der Quelle. Gelesen und geparst wird erst bei [parse], damit nie alle Dateien im Speicher liegen. */
class ImportItem(val name: String, val parse: () -> ParseResult)

/** Quelle eines Imports (Ordner, ZIP oder JSON). Muss nach dem Import geschlossen werden. */
class ImportSource(
    val label: String,
    val total: Int,
    val items: Sequence<ImportItem>,
    /** Hinweise, die schon vor dem Import feststehen (z. B. nicht übernommene Felder). */
    val preNotes: List<ImportIssue> = emptyList(),
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}

data class ImportIssue(val file: String, val reason: String)

data class ImportProgress(val done: Int, val total: Int, val current: String)

data class ImportReport(
    val total: Int,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val duplicates: List<ImportIssue>,
    val notes: List<ImportIssue>,
    val errors: List<ImportIssue>,
    val durationMs: Long,
    val cancelled: Boolean = false,
) {
    /** Lesbarer Bericht für die Datei, die der Nutzer speichern kann. */
    fun toText(): String = buildString {
        appendLine(if (cancelled) "Import abgebrochen" else "Import abgeschlossen")
        appendLine("Dateien insgesamt: $total")
        appendLine("Neu angelegt: $created")
        appendLine("Aktualisiert: $updated")
        appendLine("Unverändert: $unchanged")
        appendLine("Duplikate übersprungen: ${duplicates.size}")
        appendLine("Fehler: ${errors.size}")
        appendLine("Dauer: ${durationMs / 1000} s")
        section("Fehler", errors)
        section("Duplikate", duplicates)
        section("Hinweise", notes)
    }

    private fun StringBuilder.section(title: String, issues: List<ImportIssue>) {
        if (issues.isEmpty()) return
        appendLine()
        appendLine("$title (${issues.size}):")
        for (i in issues) appendLine("- ${i.file}: ${i.reason}")
    }

    companion object {
        const val TRASH_NOTE = "Liegt im Papierkorb"
    }
}

object StoryHash {
    /** Zeilenenden vereinheitlichen und trimmen; Grundlage für den Duplikat-Hash. */
    fun normalize(body: String): String = body.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** SHA-256 (hex) über den normalisierten Text. */
    fun of(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(body).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

object Slugs {
    /** Dateiname ohne Pfad und ohne .md. */
    fun fromFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').let {
            if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it
        }

    /** Für JSON-Einträge ohne eigene Kennung: aus dem Titel abgeleitet. */
    fun fromTitle(title: String): String {
        val base = java.text.Normalizer.normalize(
            title.lowercase(Locale.ROOT).replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue"),
            java.text.Normalizer.Form.NFD,
        ).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return base.take(80).trim('-')
    }
}
