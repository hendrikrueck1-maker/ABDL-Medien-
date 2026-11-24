package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryDao
import de.hr.mediathek.data.story.mdimport.ImportItem as ImportEntry
import de.hr.mediathek.data.story.mdimport.ImportSource
import de.hr.mediathek.data.story.mdimport.ParseResult
import de.hr.mediathek.data.story.mdimport.RoomImportStore
import de.hr.mediathek.data.story.mdimport.StoryImporter
import de.hr.mediathek.data.story.web.WebFetchException
import de.hr.mediathek.data.story.web.WebStories
import de.hr.mediathek.data.story.web.WebStoryFetcher
import de.hr.mediathek.data.story.web.WebThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FailedLink(val url: String, val reason: String)

data class LinkListUi(
    val text: String = "",
    val category: StoryCategory = StoryCategory.MIXED,
    val running: Boolean = false,
    val finished: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    /** Neu angelegte Geschichten. */
    val created: Int = 0,
    /** Links, die schon in der App sind (übersprungen). */
    val skipped: Int = 0,
    /** Geschichten mit gleichem Text wie eine vorhandene (übersprungen). */
    val duplicates: Int = 0,
    val failed: List<FailedLink> = emptyList(),
    val message: String? = null,
    /** Übersichtsseite eines Forum-Bereichs, aus der die Links gesammelt werden. */
    val collectUrl: String = "",
    val collecting: Boolean = false,
    val collectPages: Int = 0,
    val collectLinks: Int = 0,
    val collectMessage: String? = null,
)

/** Viele Links nacheinander importieren. Schon vorhandene Links werden übersprungen, so lässt sich nach einer Unterbrechung weitermachen. */
@HiltViewModel
class LinkListViewModel @Inject constructor(
    private val fetcher: WebStoryFetcher,
    private val store: RoomImportStore,
    private val dao: StoryDao,
    private val scheduler: de.hr.mediathek.core.jobs.JobScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(LinkListUi())
    val state: StateFlow<LinkListUi> = _state
    private var job: Job? = null

    fun setText(value: String) = _state.update { it.copy(text = value, message = null) }
    fun setCategory(c: StoryCategory) = _state.update { it.copy(category = c) }

    fun setCollectUrl(value: String) = _state.update { it.copy(collectUrl = value, collectMessage = null) }

    private var collectJob: Job? = null

    /** Liest alle Seiten eines Forum-Bereichs und trägt die Themen-Links in die Liste ein. */
    fun collect() {
        if (collectJob?.isActive == true || job?.isActive == true) return
        val url = _state.value.collectUrl.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(collectMessage = "Bitte den Link der Übersichtsseite einfügen.") }
            return
        }
        collectJob = viewModelScope.launch {
            _state.update { it.copy(collecting = true, collectPages = 0, collectLinks = 0, collectMessage = null) }
            try {
                val links = fetcher.collectThreadLinks(url) { pages, count -> _state.update { it.copy(collectPages = pages, collectLinks = count) } }
                _state.update {
                    val existing = WebStories.extractLinks(it.text)
                    val merged = (existing + links).distinct()
                    it.copy(
                        text = merged.joinToString("\n"),
                        collectMessage = "${links.size} Links aus ${it.collectPages} Seite(n) gesammelt. Sie stehen jetzt in der Liste.",
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(collectMessage = "Abgebrochen.") }
                throw e
            } catch (e: WebFetchException) {
                _state.update { it.copy(collectMessage = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(collectMessage = "Das hat nicht geklappt: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(collecting = false) }
            }
        }
    }

    fun cancelCollect() {
        collectJob?.cancel()
    }

    fun linkCount(): Int = WebStories.extractLinks(_state.value.text).size

    fun start() {
        if (job?.isActive == true) return
        val links = WebStories.extractLinks(_state.value.text)
        if (links.isEmpty()) {
            _state.update { it.copy(message = "Keine Links gefunden. Pro Zeile einen Link einfügen.") }
            return
        }
        val category = _state.value.category
        job = viewModelScope.launch {
            _state.update {
                it.copy(running = true, finished = false, done = 0, total = links.size, current = "", created = 0, skipped = 0, duplicates = 0, failed = emptyList(), message = null)
            }
            try {
                withContext(Dispatchers.IO) { process(links, category) }
            } catch (e: CancellationException) {
                _state.update { it.copy(message = "Abgebrochen. Bereits gespeicherte Geschichten bleiben erhalten.") }
                throw e
            } finally {
                _state.update { it.copy(running = false, finished = true) }
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { scheduler.enqueueBackupAfterChange() } }
            }
        }
    }

    private suspend fun process(links: List<String>, category: StoryCategory) {
        for ((index, raw) in links.withIndex()) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(done = index, current = raw) }
            val url = try {
                fetcher.normalize(raw)
            } catch (e: WebFetchException) {
                fail(raw, e.message ?: "ungültiger Link")
                continue
            }
            if (dao.countBySourceUrl(url) > 0) {
                _state.update { it.copy(skipped = it.skipped + 1) }
                continue
            }
            try {
                val thread = loadWithRetry(url)
                val chosen = thread.parts.filter { it.selected && it.text.isNotBlank() }
                if (chosen.isEmpty()) {
                    fail(raw, "Kein Text erkannt")
                } else {
                    val stories = WebStories.parsed(thread, thread.title, category, chosen.map { it to it.dateMillis }, System.currentTimeMillis())
                    val items = stories.map { s -> ImportEntry(s.title) { ParseResult.Ok(s) } }
                    val report = StoryImporter(store).run(ImportSource(thread.host, items.size, items.asSequence()))
                    _state.update { it.copy(created = it.created + report.created, duplicates = it.duplicates + report.duplicates.size) }
                    report.errors.forEach { fail(raw, it.reason) }
                }
            } catch (e: WebFetchException) {
                if (e.loginNeeded) {
                    _state.update {
                        it.copy(
                            message = "Diese Seite verlangt eine Anmeldung. Melde dich einmal unter „Von Webseite holen“ an, dann die Liste noch einmal starten. " +
                                "Schon importierte Links werden übersprungen.",
                        )
                    }
                    return
                }
                fail(raw, e.message ?: "Fehler beim Laden")
            }
            delay(PAUSE_MS)
        }
        _state.update { it.copy(done = links.size, current = "") }
    }

    /** Bei "zu viele Anfragen" (429) einmal 30 Sekunden warten und noch einmal versuchen. */
    private suspend fun loadWithRetry(url: String): WebThread = try {
        fetcher.load(url)
    } catch (e: WebFetchException) {
        if (e.message?.contains("429") == true) {
            delay(RATE_LIMIT_WAIT_MS)
            fetcher.load(url)
        } else {
            throw e
        }
    }

    private fun fail(url: String, reason: String) = _state.update { it.copy(failed = it.failed + FailedLink(url, reason)) }

    fun cancel() {
        job?.cancel()
    }

    /** Nur die fehlgeschlagenen Links, zum erneuten Einfügen. */
    fun failedLinksText(): String = _state.value.failed.joinToString("\n") { it.url }

    fun reset() {
        if (job?.isActive == true) return
        _state.update { LinkListUi(category = it.category, collectUrl = it.collectUrl) }
    }

    private companion object {
        const val PAUSE_MS = 1_000L
        const val RATE_LIMIT_WAIT_MS = 30_000L
    }
}
