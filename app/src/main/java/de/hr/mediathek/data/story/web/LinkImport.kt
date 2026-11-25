package de.hr.mediathek.data.story.web

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryDao
import de.hr.mediathek.data.story.mdimport.ImportItem
import de.hr.mediathek.data.story.mdimport.ImportSource
import de.hr.mediathek.data.story.mdimport.ParseResult
import de.hr.mediathek.data.story.mdimport.RoomImportStore
import de.hr.mediathek.data.story.mdimport.StoryImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class FailedLink(val url: String, val reason: String)

/** Stand der Link-Liste; wird als Datei gespeichert, damit ein Abbruch (App beendet, Neustart) fortgesetzt werden kann. */
data class LinkImportState(
    val total: Int = 0,
    /** Anzahl der schon bearbeiteten Links (= Position, an der es weitergeht). */
    val done: Int = 0,
    val category: StoryCategory = StoryCategory.MIXED,
    /** Kapitel mit weniger Zeichen werden ausgelassen (0 = alle). */
    val minChars: Int = LinkImportStore.DEFAULT_MIN_CHARS,
    val created: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val duplicates: Int = 0,
    /** Ausgelassene kurze Kapitel (unter [minChars]). */
    val tooShort: Int = 0,
    val failed: List<FailedLink> = emptyList(),
    val current: String = "",
    val message: String? = null,
    /** Ein Hintergrundauftrag arbeitet gerade an der Liste. */
    val running: Boolean = false,
) {
    val unfinished: Boolean get() = total > 0 && done < total
}

/** Liste und Fortschritt der Link-Liste (Dateien im App-Speicher, nicht in der Datenbank). */
@Singleton
class LinkImportStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "linkimport").apply { mkdirs() }
    private val linksFile = File(dir, "links.txt")
    private val stateFile = File(dir, "state.json")
    private val draftFile = File(dir, "draft.txt")

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<LinkImportState> = _state

    /** Noch nicht gestartete Liste (eingefügt oder gesammelt), überlebt einen Neustart der App. */
    fun draft(): List<String> = runCatching { draftFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    fun saveDraft(links: List<String>) {
        runCatching { draftFile.writeText(links.joinToString("\n")) }
    }

    fun links(): List<String> = runCatching { linksFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** Neue Liste vorbereiten: Fortschritt beginnt bei 0. */
    fun prepare(links: List<String>, category: StoryCategory, minChars: Int) {
        linksFile.writeText(links.joinToString("\n"))
        set(LinkImportState(total = links.size, category = category, minChars = minChars))
    }

    fun update(block: (LinkImportState) -> LinkImportState) {
        _state.update(block)
        save(_state.value)
    }

    fun set(value: LinkImportState) {
        _state.value = value
        save(value)
    }

    /** Ergebnis und Liste verwerfen (der Entwurf bleibt). */
    fun clear() {
        linksFile.delete()
        set(LinkImportState(category = _state.value.category, minChars = _state.value.minChars))
    }

    private fun save(s: LinkImportState) {
        runCatching {
            val o = JSONObject()
                .put("total", s.total).put("done", s.done).put("category", s.category.name).put("minChars", s.minChars)
                .put("created", s.created).put("updated", s.updated).put("skipped", s.skipped).put("duplicates", s.duplicates)
                .put("tooShort", s.tooShort).put("message", s.message ?: JSONObject.NULL)
                .put("failed", JSONArray().apply { s.failed.takeLast(MAX_FAILED).forEach { put(JSONObject().put("url", it.url).put("reason", it.reason)) } })
            val tmp = File(dir, "state.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(stateFile)
        }
    }

    private fun readState(): LinkImportState = runCatching {
        val o = JSONObject(stateFile.readText())
        val failed = o.optJSONArray("failed")?.let { a -> (0 until a.length()).map { FailedLink(a.getJSONObject(it).getString("url"), a.getJSONObject(it).getString("reason")) } }
        LinkImportState(
            total = o.optInt("total"), done = o.optInt("done"),
            category = runCatching { StoryCategory.valueOf(o.optString("category")) }.getOrDefault(StoryCategory.MIXED),
            minChars = o.optInt("minChars", DEFAULT_MIN_CHARS),
            created = o.optInt("created"), updated = o.optInt("updated"), skipped = o.optInt("skipped"),
            duplicates = o.optInt("duplicates"), tooShort = o.optInt("tooShort"),
            failed = failed.orEmpty(), message = if (o.isNull("message")) null else o.optString("message"),
        )
    }.getOrDefault(LinkImportState())

    companion object {
        const val DEFAULT_MIN_CHARS = 800
        private const val MAX_FAILED = 500
    }
}

/** Kapitel-Auswahl für die Link-Liste: vorgewählte Kapitel mit Text, kurze (unter [minChars]) ausgelassen. */
object LinkImportRules {
    fun choose(parts: List<WebPart>, minChars: Int): Pair<List<WebPart>, Int> {
        val selected = parts.filter { it.selected && it.text.isNotBlank() }
        if (minChars <= 0) return selected to 0
        val long = selected.filter { it.text.length >= minChars }
        return long to (selected.size - long.size)
    }

    /** Wartezeiten bei „zu viele Anfragen“ (429): 30 s, 2 min, 5 min; danach pausiert der Auftrag. */
    val RATE_LIMIT_WAITS_MS = listOf(30_000L, 120_000L, 300_000L)
}

/** Hintergrundauftrag: holt die Links der Liste nacheinander, ab der gespeicherten Position. */
class LinkImportExecutor @Inject constructor(
    private val store: LinkImportStore,
    private val fetcher: WebStoryFetcher,
    private val importStore: RoomImportStore,
    private val dao: StoryDao,
    private val scheduler: dagger.Lazy<de.hr.mediathek.core.jobs.JobScheduler>,
) {
    suspend fun execute(ctx: JobContext): JobOutcome = withContext(Dispatchers.IO) {
        val links = store.links()
        val start = store.state.value
        if (links.isEmpty() || start.done >= links.size) return@withContext JobOutcome.Completed("Link-Liste: nichts zu tun")
        store.update { it.copy(running = true, message = null, total = links.size) }
        try {
            for (index in start.done until links.size) {
                ctx.checkpoint()
                val raw = links[index]
                store.update { it.copy(current = raw) }
                ctx.report("Geschichten werden geholt: ${index + 1} von ${links.size}", (index + 1f) / links.size)
                val outcome = processOne(raw, ctx)
                if (outcome != null) return@withContext outcome
                store.update { it.copy(done = index + 1) }
                delay(PAUSE_MS)
            }
            val s = store.state.value
            store.update { it.copy(current = "", running = false) }
            JobOutcome.Completed("Link-Liste: ${s.created} neu, ${s.updated} aktualisiert, ${s.failed.size} Fehler")
        } finally {
            store.update { it.copy(running = false, current = "") }
            runCatching { scheduler.get().enqueueBackupAfterChange() }
        }
    }

    /** null = weiter mit dem nächsten Link; sonst Auftrag beenden (Anmeldung nötig, Seite bremst). */
    private suspend fun processOne(raw: String, ctx: JobContext): JobOutcome? {
        val url = try {
            fetcher.normalize(raw)
        } catch (e: WebFetchException) {
            fail(raw, e.message ?: "ungültiger Link")
            return null
        }
        if (dao.countBySourceUrl(url) > 0 || dao.countBySourceUrl(ThreadBuilder.forumFirstPage(url)) > 0) {
            store.update { it.copy(skipped = it.skipped + 1) }
            return null
        }
        val thread = try {
            loadWithBackoff(url, ctx) ?: run {
                val msg = "Die Seite meldet „zu viele Anfragen“. Der Auftrag pausiert; später mit „Fortsetzen“ weitermachen."
                store.update { it.copy(message = msg) }
                return JobOutcome.Failed(msg)
            }
        } catch (e: WebFetchException) {
            if (e.loginNeeded) {
                val msg = "Diese Seite verlangt eine Anmeldung. Melde dich einmal unter „Von Webseite holen“ an und tippe dann auf „Fortsetzen“."
                store.update { it.copy(message = msg) }
                return JobOutcome.Failed(msg)
            }
            fail(raw, e.message ?: "Fehler beim Laden")
            return null
        }
        val state = store.state.value
        val (chosen, short) = LinkImportRules.choose(thread.parts, state.minChars)
        if (short > 0) store.update { it.copy(tooShort = it.tooShort + short) }
        if (chosen.isEmpty()) {
            if (short == 0) fail(raw, "Kein Text erkannt")
            return null
        }
        val stories = WebStories.parsed(thread, thread.title, state.category, chosen.map { it to it.dateMillis }, System.currentTimeMillis())
        val items = stories.map { s -> ImportItem(s.title) { ParseResult.Ok(s) } }
        val report = StoryImporter(importStore).run(ImportSource(thread.host, items.size, items.asSequence()))
        store.update { it.copy(created = it.created + report.created, updated = it.updated + report.updated, duplicates = it.duplicates + report.duplicates.size) }
        report.errors.forEach { fail(raw, it.reason) }
        return null
    }

    /** null = auch nach allen Wartezeiten noch „zu viele Anfragen“. */
    private suspend fun loadWithBackoff(url: String, ctx: JobContext): WebThread? {
        for (wait in listOf(0L) + LinkImportRules.RATE_LIMIT_WAITS_MS) {
            if (wait > 0) {
                store.update { it.copy(message = "Die Seite bremst – warte ${wait / 1000} Sekunden …") }
                var left = wait
                while (left > 0) { ctx.checkpoint(); delay(minOf(left, 5_000L)); left -= 5_000L }
                store.update { it.copy(message = null) }
            }
            try {
                return fetcher.load(url)
            } catch (e: WebFetchException) {
                if (e.message?.contains("429") != true) throw e
            }
        }
        return null
    }

    private fun fail(url: String, reason: String) = store.update { it.copy(failed = it.failed + FailedLink(url, reason)) }

    private companion object {
        const val PAUSE_MS = 1_000L
    }
}
