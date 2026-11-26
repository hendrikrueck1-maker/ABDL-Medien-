package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.core.AppScope
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.web.LinkImportState
import de.hr.mediathek.data.story.web.LinkImportStore
import de.hr.mediathek.data.story.web.WebFetchException
import de.hr.mediathek.data.story.web.WebStories
import de.hr.mediathek.data.story.web.WebStoryFetcher
import de.hr.mediathek.data.story.web.blogger.BlogProblem
import de.hr.mediathek.data.story.web.blogger.BlogResult
import de.hr.mediathek.data.story.web.blogger.BloggerCollector
import de.hr.mediathek.data.story.web.blogger.CollectProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Eingabe-Seite der Link-Liste (vor dem Start). Die Liste selbst steht als Liste im Zustand, nicht als großer Text. */
data class LinkListUi(
    /** Gesammelte bzw. eingefügte Links (noch nicht gestartet). */
    val links: List<String> = emptyList(),
    /** Eingabefeld zum Einfügen weiterer Links. */
    val input: String = "",
    val category: StoryCategory = StoryCategory.MIXED,
    val skipShort: Boolean = true,
    val message: String? = null,
    /** Übersichtsseite eines Forum-Bereichs, aus der die Links gesammelt werden. */
    val collectUrl: String = "",
    val collecting: Boolean = false,
    val collectPages: Int = 0,
    val collectLinks: Int = 0,
    val collectMessage: String? = null,
    /** Blogger-Sammler: eine Blog-Adresse pro Zeile. */
    val blogInput: String = "",
    val blogCollecting: Boolean = false,
    val blogProgress: String? = null,
    /** Zusammenfassung je Blog nach dem Lauf. */
    val blogSummary: List<String> = emptyList(),
)

/**
 * Viele Links nacheinander importieren – als Hintergrundauftrag mit Benachrichtigung. Läuft weiter, wenn die Seite oder
 * die App verlassen wird; nach einer Unterbrechung geht es an der gespeicherten Stelle weiter.
 */
@HiltViewModel
class LinkListViewModel @Inject constructor(
    private val fetcher: WebStoryFetcher,
    private val store: LinkImportStore,
    private val scheduler: de.hr.mediathek.core.jobs.JobScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(LinkListUi(links = store.draft(), category = store.state.value.category, skipShort = store.state.value.minChars > 0))
    val state: StateFlow<LinkListUi> = _state

    /** Fortschritt des Hintergrundauftrags (auch nach Neustart der App). */
    val progress: StateFlow<LinkImportState> = store.state

    /** Aktiver Auftrag (eingereiht, läuft oder pausiert); null = keiner. */
    val job: StateFlow<JobEntity?> = scheduler.observeLinkImportJob().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var collectJob: Job? = null

    fun setInput(value: String) = _state.update { it.copy(input = value, message = null) }
    fun setCategory(c: StoryCategory) = _state.update { it.copy(category = c) }
    fun setSkipShort(v: Boolean) = _state.update { it.copy(skipShort = v) }
    fun setCollectUrl(value: String) = _state.update { it.copy(collectUrl = value, collectMessage = null) }

    /** Links aus dem Eingabefeld zur Liste hinzufügen (Doppelte fallen weg). */
    fun addInput() {
        val found = WebStories.extractLinks(_state.value.input)
        if (found.isEmpty()) {
            _state.update { it.copy(message = "Keine Links gefunden. Pro Zeile einen Link einfügen.") }
            return
        }
        setLinks((_state.value.links + found).distinct())
        _state.update { it.copy(input = "", message = "${found.size} Links übernommen.") }
    }

    fun clearLinks() = setLinks(emptyList())

    private fun setLinks(links: List<String>) {
        _state.update { it.copy(links = links) }
        AppScope.launch { store.saveDraft(links) }
    }

    /** Liest alle Seiten eines Forum-Bereichs und trägt die Themen-Links in die Liste ein. */
    fun collect() {
        if (collectJob?.isActive == true) return
        val url = _state.value.collectUrl.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(collectMessage = "Bitte den Link der Übersichtsseite einfügen.") }
            return
        }
        collectJob = viewModelScope.launch {
            _state.update { it.copy(collecting = true, collectPages = 0, collectLinks = 0, collectMessage = null) }
            try {
                val links = fetcher.collectThreadLinks(url) { pages, count -> _state.update { it.copy(collectPages = pages, collectLinks = count) } }
                setLinks((_state.value.links + links).distinct())
                _state.update { it.copy(collectMessage = "${links.size} Links aus ${it.collectPages} Seiten gesammelt. Sie stehen jetzt in der Liste.") }
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

    private var blogJob: Job? = null

    fun setBlogInput(value: String) = _state.update { it.copy(blogInput = value, blogSummary = emptyList()) }

    /**
     * Sammelt die Beitragslinks mehrerer Blogger-Blogs. Läuft über AppScope, damit „Zurück“ den Lauf nicht abbricht;
     * am Ende werden die Links an die Liste angehängt (auch wenn die Seite inzwischen geschlossen wurde).
     */
    fun collectBlogs() {
        if (blogJob?.isActive == true) return
        val inputs = _state.value.blogInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (inputs.isEmpty()) {
            _state.update { it.copy(blogSummary = listOf("Bitte mindestens eine Blog-Adresse eintragen.")) }
            return
        }
        _state.update { it.copy(blogCollecting = true, blogProgress = "Starte …", blogSummary = emptyList()) }
        blogJob = AppScope.launch {
            try {
                val collector = BloggerCollector(fetch = { fetcher.get(it) }, sleep = { kotlinx.coroutines.delay(it) })
                val result = collector.collect(inputs) { p -> _state.update { it.copy(blogProgress = progressText(p)) } }
                // Neu lesen: Die Liste kann sich während des Laufs geändert haben.
                val merged = (store.draft() + result.links).distinct()
                store.saveDraft(merged)
                val added = merged.size
                _state.update {
                    it.copy(
                        links = merged,
                        blogSummary = result.blogs.map(::summaryLine) + "Gesamt: ${result.links.size} Links gesammelt, $added in der Liste.",
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(blogSummary = listOf("Abgebrochen.")) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(blogSummary = listOf("Das hat nicht geklappt: ${e.message ?: e.javaClass.simpleName}")) }
            } finally {
                _state.update { it.copy(blogCollecting = false, blogProgress = null) }
            }
        }
    }

    fun cancelBlogs() {
        blogJob?.cancel()
    }

    private fun progressText(p: CollectProgress): String {
        val host = p.blogLabel.removePrefix("https://").removePrefix("http://")
        val wait = p.waitingMs?.let { ms -> if (ms >= 60_000) "Blog bremst – warte ${ms / 60_000} min" else "Blog bremst – warte ${ms / 1000} s" }
        val count = if (p.total != null) "${p.found} von ${p.total}" else "${p.found} gefunden"
        return "Blog ${p.blogIndex} von ${p.blogCount} · $host · $count" + (wait?.let { "\n$it" } ?: "")
    }

    private fun summaryLine(b: BlogResult): String {
        val name = (b.origin ?: b.input).removePrefix("https://").removePrefix("http://") + (b.label?.let { " · $it" } ?: "")
        val n = b.posts.size
        val text = when (b.problem) {
            null -> "$n Links gesammelt" + if (b.viaHtml) " (Feed abgeschaltet – über die Seiten gesammelt)" else ""
            BlogProblem.INVALID_ADDRESS -> "Keine Webadresse"
            BlogProblem.NOT_BLOGGER -> "Kein Blogger-Blog – bitte den Forum-Sammler nehmen"
            BlogProblem.PRIVATE -> "Blog nur für eingeladene Leser – geht ohne Anmeldung nicht"
            BlogProblem.NOT_FOUND -> "Blog nicht gefunden (gelöscht?)"
            BlogProblem.TOO_MANY_REQUESTS -> "Blog bremst – $n Links gesammelt, später erneut versuchen"
            BlogProblem.NETWORK -> "Keine Verbindung – $n Links gesammelt"
        }
        val prefix = if (b.problem == null) "✓" else "✗"
        return "$prefix ${if (b.origin == null) b.input else name}: $text"
    }

    /** Neue Liste starten (Hintergrundauftrag). */
    fun start() {
        val ui = _state.value
        if (ui.links.isEmpty()) {
            _state.update { it.copy(message = "Die Liste ist leer.") }
            return
        }
        AppScope.launch {
            if (job.value != null) return@launch
            store.prepare(ui.links, ui.category, if (ui.skipShort) LinkImportStore.DEFAULT_MIN_CHARS else 0)
            store.saveDraft(emptyList())
            _state.update { it.copy(links = emptyList(), message = null) }
            scheduler.enqueueLinkImport()
        }
    }

    /** Nach Abbruch, Anmeldung oder „zu viele Anfragen“ an der gespeicherten Stelle weitermachen. */
    fun resume() {
        AppScope.launch {
            store.update { it.copy(message = null) }
            scheduler.enqueueLinkImport()
        }
    }

    fun cancel() {
        AppScope.launch { scheduler.cancelLinkImport() }
    }

    /** Ergebnis verwerfen und eine neue Liste beginnen. */
    fun reset() {
        if (job.value != null) return
        store.clear()
    }

    /** Nur die fehlgeschlagenen Links, zum erneuten Einfügen. */
    fun failedLinksText(): String = progress.value.failed.joinToString("\n") { it.url }

    /** Fehlgeschlagene Links als neue Liste übernehmen (für „Noch einmal versuchen“). */
    fun retryFailed() {
        val failed = progress.value.failed.map { it.url }.distinct()
        if (failed.isEmpty() || job.value != null) return
        store.clear()
        setLinks(failed)
    }
}
