package de.hr.mediathek.feature.stories

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryFolderEntity
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.data.story.organize.StoryExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StoryStats(
    val stories: Int = 0,
    val series: Int = 0,
    val unread: Int = 0,
    val started: Int = 0,
    val done: Int = 0,
    val words: Long = 0,
    val wordsRead: Long = 0,
    val men: Int = 0,
    val women: Int = 0,
    val mixed: Int = 0,
) {
    /** Lesezeit bei etwa 200 Wörtern pro Minute. */
    val minutesRead: Long get() = wordsRead / 200
    val minutesLeft: Long get() = (words - wordsRead) / 200
}

data class ExportUi(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val message: String? = null)

@HiltViewModel
class StoryToolsViewModel @Inject constructor(
    repository: StoryRepository,
    private val exporter: StoryExporter,
) : ViewModel() {
    val stats: StateFlow<StoryStats> = repository.observeActive().map { rows ->
        StoryStats(
            stories = rows.size,
            series = rows.mapNotNull { it.seriesUuid }.distinct().size,
            unread = rows.count { it.lastReadAt == null && it.readProgress <= 0f },
            started = rows.count { it.lastReadAt != null && it.readProgress < READ_DONE_AT },
            done = rows.count { it.readProgress >= READ_DONE_AT },
            words = rows.sumOf { it.wordCount.toLong() },
            wordsRead = rows.sumOf { (it.wordCount * it.readProgress.coerceIn(0f, 1f)).toLong() },
            men = rows.count { it.category == de.hr.mediathek.data.db.StoryCategory.MEN },
            women = rows.count { it.category == de.hr.mediathek.data.db.StoryCategory.WOMEN },
            mixed = rows.count { it.category == de.hr.mediathek.data.db.StoryCategory.MIXED },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryStats())

    val folders: StateFlow<List<StoryFolderEntity>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _export = MutableStateFlow(ExportUi())
    val export: StateFlow<ExportUi> = _export

    fun export(target: Uri, folderUuid: String?) {
        _export.value = ExportUi(running = true)
        viewModelScope.launch {
            try {
                val n = exporter.export(target, folderUuid) { d, t -> _export.update { it.copy(done = d, total = t) } }
                _export.value = ExportUi(message = "$n Geschichten exportiert.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _export.value = ExportUi(message = "Export fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
