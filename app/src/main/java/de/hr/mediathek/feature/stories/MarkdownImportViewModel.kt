package de.hr.mediathek.feature.stories

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.story.mdimport.ImportProgress
import de.hr.mediathek.data.story.mdimport.ImportReport
import de.hr.mediathek.data.story.mdimport.ImportSource
import de.hr.mediathek.data.story.mdimport.RoomImportStore
import de.hr.mediathek.data.story.mdimport.StoryImportSources
import de.hr.mediathek.data.story.mdimport.StoryImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class MdPhase { IDLE, PREPARING, RUNNING, DONE }

data class MdImportUi(
    val phase: MdPhase = MdPhase.IDLE,
    val label: String = "",
    val progress: ImportProgress? = null,
    val report: ImportReport? = null,
    val message: String? = null,
    val reportSaved: Boolean = false,
)

@HiltViewModel
class MarkdownImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: StoryImportSources,
    private val store: RoomImportStore,
) : ViewModel() {
    private val _state = MutableStateFlow(MdImportUi())
    val state: StateFlow<MdImportUi> = _state
    private var job: Job? = null

    fun importFolder(tree: Uri) = start("Ordner") { sources.fromFolder(tree) }
    fun importZip(uri: Uri) = start("ZIP-Datei") { sources.fromZip(uri) }
    fun importJson(uri: Uri) = start("JSON-Datei") { sources.fromJson(uri) }

    private fun start(label: String, open: suspend () -> ImportSource) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = MdImportUi(MdPhase.PREPARING, label)
            val source = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = MdImportUi(message = "Konnte nicht gelesen werden: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            try {
                if (source.total == 0) {
                    _state.value = MdImportUi(message = "Keine Markdown-Dateien (*.md) gefunden.")
                    return@launch
                }
                _state.value = MdImportUi(MdPhase.RUNNING, label, ImportProgress(0, source.total, ""))
                val report = withContext(Dispatchers.IO) {
                    StoryImporter(store).run(source) { p -> _state.update { it.copy(progress = p) } }
                }
                _state.value = MdImportUi(MdPhase.DONE, label, report = report)
            } catch (e: StoryImporter.CancelledWithReport) {
                _state.value = MdImportUi(MdPhase.DONE, label, report = e.report, message = "Abgebrochen. Bereits gespeicherte Geschichten bleiben erhalten.")
            } catch (e: CancellationException) {
                _state.value = MdImportUi(message = "Import abgebrochen.")
                throw e
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { source.close() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = MdImportUi()
    }

    /** Schreibt den Fehlerreport in die gewählte Datei. */
    fun saveReport(uri: Uri) {
        val text = _state.value.report?.toText() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
            }.getOrDefault(false)
            _state.update { it.copy(reportSaved = ok, message = if (ok) "Report gespeichert." else "Report konnte nicht gespeichert werden.") }
        }
    }
}
