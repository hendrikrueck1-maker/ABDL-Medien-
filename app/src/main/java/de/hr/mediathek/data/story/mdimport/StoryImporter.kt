package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bereits vorhandene Geschichte zu einem Slug, mit allem, was für den Vergleich nötig ist. */
data class ExistingStory(
    val uuid: String,
    val trashed: Boolean,
    val hash: String?,
    val title: String,
    val category: StoryCategory,
    val author: String?,
    val status: String?,
    val publishedAt: Long?,
    /** Normalisierte Tag-Namen. */
    val tags: Set<String>,
    val rating: Int = 0,
    val continueFlag: Boolean = false,
    val sourceUrl: String? = null,
)

/** Speicherzugriff des Importers; getrennt, damit die Logik ohne Datenbank getestet werden kann. */
interface ImportStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun find(slug: String): ExistingStory?

    /** Titel einer anderen aktiven Geschichte mit gleichem Text, sonst null. */
    suspend fun duplicateOf(story: ParsedStory): String?
    suspend fun insert(story: ParsedStory)
    suspend fun update(existing: ExistingStory, story: ParsedStory)

    /** Ändert nur Bewertung, Kategorie, Markierung und fügt Tags hinzu. */
    suspend fun patch(existing: ExistingStory, patch: StoryPatch)
}

/**
 * Importiert in Batches (eine Transaktion je Batch). Ein Fehler in einer Datei bricht nie den Import ab: Er landet im
 * Report. Schlägt ein ganzer Batch fehl, wird er Datei für Datei in eigenen Transaktionen wiederholt.
 *
 * Reihenfolge je Datei: Slug schon vorhanden → aktualisieren (oder "unverändert"); sonst gleicher Text wie eine andere
 * Geschichte → als Duplikat überspringen; sonst neu anlegen. Mehrfaches Importieren legt so nichts doppelt an.
 */
class StoryImporter(private val store: ImportStore, private val batchSize: Int = 100) {

    private sealed interface Outcome {
        data object Created : Outcome
        data object Updated : Outcome
        data object Unchanged : Outcome
        data class Duplicate(val of: String) : Outcome
        data class Note(val text: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data object Missing : Outcome
    }

    private class Slot(val name: String, val result: ParseResult)

    suspend fun run(source: ImportSource, onProgress: (ImportProgress) -> Unit = {}): ImportReport {
        val started = System.currentTimeMillis()
        var created = 0
        var updated = 0
        var unchanged = 0
        val duplicates = ArrayList<ImportIssue>()
        val notes = ArrayList<ImportIssue>()
        val errors = ArrayList<ImportIssue>()
        val seenSlugs = HashMap<String, String>()
        var done = 0
        var cancelled = false
        val missing = ArrayList<String>()
        notes += source.preNotes

        fun report(): ImportReport {
            val allNotes = if (missing.isEmpty()) notes else notes + ImportIssue(
                "${missing.size} Einträge der Sicherung",
                "gehören zu Geschichten, die nicht in der App sind (zuerst den Katalog importieren; Duplikate des Katalogs werden übersprungen). Beispiele: " +
                    missing.take(8).joinToString(", "),
            )
            return ImportReport(
                source.total, created, updated, unchanged, duplicates, allNotes, errors,
                System.currentTimeMillis() - started, cancelled,
            )
        }

        try {
            val iterator = source.items.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                val slots = ArrayList<Slot>(batchSize)
                while (slots.size < batchSize && iterator.hasNext()) {
                    val item = iterator.next()
                    val result = try {
                        item.parse()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ParseResult.Fail(e.message?.take(120) ?: "Datei nicht lesbar")
                    }
                    slots += Slot(item.name, result)
                }
                val outcomes = process(slots, seenSlugs)
                for ((slot, outcome) in slots.zip(outcomes)) {
                    when (outcome) {
                        Outcome.Created -> created++
                        Outcome.Updated -> updated++
                        Outcome.Unchanged -> unchanged++
                        is Outcome.Duplicate -> duplicates += ImportIssue(slot.name, "gleicher Text wie „${outcome.of}“")
                        is Outcome.Note -> notes += ImportIssue(slot.name, outcome.text)
                        is Outcome.Failed -> errors += ImportIssue(slot.name, outcome.reason)
                        Outcome.Missing -> missing += slot.name
                    }
                    (slot.result as? ParseResult.Ok)?.warnings?.forEach { notes += ImportIssue(slot.name, it) }
                    done++
                }
                onProgress(ImportProgress(done, source.total, slots.lastOrNull()?.name ?: ""))
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw CancelledWithReport(report(), e)
        }
        return report()
    }

    /** Wird bei Abbruch geworfen; trägt den Zwischenstand, der bereits gespeichert ist. */
    class CancelledWithReport(val report: ImportReport, cause: CancellationException) : CancellationException("Import abgebrochen") {
        init { initCause(cause) }
    }

    private suspend fun process(slots: List<Slot>, seenSlugs: MutableMap<String, String>): List<Outcome> {
        // Doppelte Kennung innerhalb desselben Imports vorab erkennen (gleicher Dateiname in zwei Ordnern).
        val prefail = HashMap<Int, Outcome>()
        slots.forEachIndexed { i, slot ->
            val ok = slot.result as? ParseResult.Ok ?: return@forEachIndexed
            val first = seenSlugs.putIfAbsent(ok.story.slug, slot.name)
            if (first != null) prefail[i] = Outcome.Failed("Dateiname/Kennung „${ok.story.slug}“ kommt in diesem Import schon vor (${first}). Eine der Dateien umbenennen")
        }
        fun one(i: Int, slot: Slot): suspend () -> Outcome = {
            prefail[i] ?: when (val r = slot.result) {
                is ParseResult.Fail -> Outcome.Failed(r.reason)
                is ParseResult.Ok -> apply(r.story)
                is ParseResult.Patch -> applyPatch(r.patch)
            }
        }
        return try {
            store.inTransaction { slots.mapIndexed { i, s -> one(i, s)() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            slots.mapIndexed { i, s ->
                try {
                    store.inTransaction { one(i, s)() }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Outcome.Failed("Speichern fehlgeschlagen: ${t.message?.take(120) ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun applyPatch(patch: StoryPatch): Outcome {
        val existing = store.find(patch.slug) ?: return Outcome.Missing
        if (existing.trashed) return Outcome.Note("${patch.slug}: ${ImportReport.TRASH_NOTE}, Änderungen nicht übernommen")
        val tags = patch.addTags.map { de.hr.mediathek.data.tag.TagNames.normalize(it) }
        val same = (patch.rating == null || existing.rating == patch.rating) &&
            (patch.category == null || existing.category == patch.category) &&
            (patch.continueFlag == null || existing.continueFlag == patch.continueFlag) &&
            existing.tags.containsAll(tags)
        if (same) return Outcome.Unchanged
        store.patch(existing, patch)
        return Outcome.Updated
    }

    private suspend fun apply(input: ParsedStory): Outcome {
        var story = input
        var existing = store.find(story.slug)
        // Gleiche Kennung, aber eine andere Geschichte (anderer Link, anderer Text): nicht überschreiben,
        // sondern unter einer freien Kennung neu anlegen (z. B. zwei Geschichten mit gleichem Titel).
        if (existing != null && !existing.trashed && existing.hash != story.hash && differentSource(existing.sourceUrl, story.sourceUrl)) {
            var n = 2
            var candidate = story.copy(slug = "${input.slug}-$n")
            while (store.find(candidate.slug) != null && n < 50) candidate = story.copy(slug = "${input.slug}-${++n}")
            story = candidate
            existing = null
        }
        if (existing != null) {
            if (existing.trashed) return Outcome.Note("${ImportReport.TRASH_NOTE} und wurde nicht wiederhergestellt")
            val tagSet = story.tags.map { de.hr.mediathek.data.tag.TagNames.normalize(it) }.toSet()
            val same = existing.hash == story.hash && (story.keepTitle || existing.title == story.title) &&
                (story.rating == null || existing.rating == story.rating) &&
                (story.continueFlag == null || existing.continueFlag == story.continueFlag) &&
                if (story.preserveUserData) true else (
                    existing.category == story.category && existing.author == story.author &&
                        existing.status == story.status && existing.publishedAt == story.publishedAt && existing.tags == tagSet
                    )
            if (same) return Outcome.Unchanged
            if (existing.hash != story.hash) store.duplicateOf(story)?.let { return Outcome.Duplicate(it) }
            store.update(existing, story)
            return Outcome.Updated
        }
        store.duplicateOf(story)?.let { return Outcome.Duplicate(it) }
        store.insert(story)
        return Outcome.Created
    }

    companion object {
        /** Beide Links bekannt und verschieden (ohne Schema, www., Schrägstrich am Ende und Sprungmarke verglichen). */
        fun differentSource(a: String?, b: String?): Boolean {
            if (a.isNullOrBlank() || b.isNullOrBlank()) return false
            fun norm(u: String) = u.trim().substringBefore('#').lowercase()
                .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
            return norm(a) != norm(b)
        }
    }
}
