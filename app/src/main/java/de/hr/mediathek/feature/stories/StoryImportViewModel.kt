package de.hr.mediathek.feature.stories

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.story.CleanOptions
import de.hr.mediathek.data.story.RawStoryFile
import de.hr.mediathek.data.story.StoryFileKind
import de.hr.mediathek.data.story.StoryFileReader
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.data.story.TextCleaner
import de.hr.mediathek.data.story.TextDecoder
import de.hr.mediathek.data.story.TextEncoding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

class ImportItem(
    val id: Int,
    val raw: RawStoryFile,
    val encoding: TextEncoding?,
    val smooth: Boolean,
    val removeRepeating: Boolean,
    val selected: Boolean,
    val text: String,
    val removed: List<String>,
    val duplicateOf: String?,
) {
    val name: String get() = raw.name
    val kind: StoryFileKind get() = raw.kind
    val error: String? get() = raw.error
    val title: String get() = raw.name.substringBeforeLast('.')
}

data class ImportUiState(
    val loading: Boolean = false,
    val items: List<ImportItem> = emptyList(),
    val category: StoryCategory = StoryCategory.MIXED,
    val importedCount: Int? = null,
)

@HiltViewModel
class StoryImportViewModel @Inject constructor(
    private val reader: StoryFileReader,
    private val repository: StoryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state

    fun loadFiles(uris: List<Uri>) = load { reader.readFiles(uris) }
    fun loadFolder(tree: Uri) = load { reader.readFolder(tree) }

    private fun load(block: suspend () -> List<RawStoryFile>) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, importedCount = null) }
            val files = block()
            val items = files.mapIndexed { i, raw -> build(i, raw, null, null, null, true) }
            _state.update { it.copy(loading = false, items = items) }
        }
    }

    /** Berechnet Text und Duplikat-Hinweis eines Eintrags neu; nicht gesetzte Optionen bekommen ihre Standardwerte. */
    private suspend fun build(
        id: Int, raw: RawStoryFile, encoding: TextEncoding?, smooth: Boolean?, removeRepeating: Boolean?, selected: Boolean,
    ): ImportItem {
        if (raw.error != null) return ImportItem(id, raw, null, false, false, false, "", emptyList(), null)
        val isPdf = raw.kind == StoryFileKind.PDF
        val enc = if (raw.kind == StoryFileKind.TXT) encoding ?: TextDecoder.detect(raw.bytes!!) else null
        val smoothing = smooth ?: isPdf
        val repeating = removeRepeating ?: isPdf
        val source = raw.rawText ?: TextDecoder.decode(raw.bytes!!, enc!!)
        val cleaned = TextCleaner.clean(source, CleanOptions(smoothing, repeating))
        val duplicate = if (cleaned.text.isBlank()) null else repository.duplicateTitle(cleaned.text)
        return ImportItem(id, raw, enc, smoothing, repeating, selected && cleaned.text.isNotBlank(), cleaned.text, cleaned.removedLines, duplicate)
    }

    private fun rebuild(id: Int, encoding: TextEncoding? = null, smooth: Boolean? = null, removeRepeating: Boolean? = null) {
        viewModelScope.launch {
            val old = _state.value.items.firstOrNull { it.id == id } ?: return@launch
            val updated = build(
                id, old.raw, encoding ?: old.encoding, smooth ?: old.smooth, removeRepeating ?: old.removeRepeating, old.selected,
            )
            _state.update { s -> s.copy(items = s.items.map { if (it.id == id) updated else it }) }
        }
    }

    fun setEncoding(id: Int, encoding: TextEncoding) = rebuild(id, encoding = encoding)
    fun setSmooth(id: Int, value: Boolean) = rebuild(id, smooth = value)
    fun setRemoveRepeating(id: Int, value: Boolean) = rebuild(id, removeRepeating = value)

    fun setSelected(id: Int, value: Boolean) {
        _state.update { s ->
            s.copy(
                items = s.items.map {
                    if (it.id == id) ImportItem(it.id, it.raw, it.encoding, it.smooth, it.removeRepeating, value, it.text, it.removed, it.duplicateOf) else it
                },
            )
        }
    }

    fun setCategory(category: StoryCategory) = _state.update { it.copy(category = category) }

    fun importSelected() {
        viewModelScope.launch {
            val s = _state.value
            var count = 0
            for (item in s.items.filter { it.selected && it.error == null }) {
                repository.create(item.title, item.text, s.category, sourceText = item.name)
                count++
            }
            _state.update { it.copy(items = emptyList(), importedCount = count) }
        }
    }

    fun reset() = _state.update { ImportUiState() }
}
