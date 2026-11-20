package de.hr.mediathek.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.StoryRow
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.data.story.organize.DiffKind
import de.hr.mediathek.data.story.organize.DiffLine
import de.hr.mediathek.data.story.organize.DupGroup
import de.hr.mediathek.data.story.organize.DupKind
import de.hr.mediathek.data.story.organize.StoryOrganizer
import de.hr.mediathek.data.story.organize.TextDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DupGroupUi(val kind: DupKind, val similarity: Float, val members: List<StoryRow>, val keep: String)

/** Zeile der Gegenüberstellung: ein Absatz oder ein zusammengefasster Block gleicher Absätze. */
data class DiffRow(val kind: DiffKind, val text: String, val collapsed: Int = 0)

data class DupUi(
    val scanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val scanned: Boolean = false,
    val groups: List<DupGroupUi> = emptyList(),
    val compareGroup: DupGroupUi? = null,
    val compareA: Int = 0,
    val compareB: Int = 1,
    val diff: List<DiffRow>? = null,
    val message: String? = null,
)

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repository: StoryRepository,
    private val organizer: StoryOrganizer,
) : ViewModel() {
    private val _state = MutableStateFlow(DupUi())
    val state: StateFlow<DupUi> = _state
    private var job: Job? = null

    init { scan() }

    fun scan() {
        job?.cancel()
        _state.value = DupUi(scanning = true)
        job = viewModelScope.launch {
            try {
                val rows = repository.observeActive().first().associateBy { it.uuid }
                val groups = organizer.scanDuplicates { d, t -> _state.update { it.copy(done = d, total = t) } }
                val ui = groups.mapNotNull { g -> toUi(g, rows) }
                _state.value = DupUi(scanned = true, groups = ui)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DupUi(scanned = true, message = "Suche fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun toUi(g: DupGroup, rows: Map<String, StoryRow>): DupGroupUi? {
        val members = g.members.mapNotNull { rows[it] }
        if (members.size < 2) return null
        // Vorschlag: die längste Fassung, bei Gleichstand die besser bewertete.
        val keep = members.sortedWith(compareByDescending<StoryRow> { it.wordCount }.thenByDescending { it.rating }.thenByDescending { it.favorite }).first().uuid
        return DupGroupUi(g.kind, g.similarity, members.sortedBy { it.createdAt }, keep)
    }

    fun setKeep(group: DupGroupUi, uuid: String) = replace(group) { it.copy(keep = uuid) }

    private fun replace(group: DupGroupUi, block: (DupGroupUi) -> DupGroupUi) =
        _state.update { s -> s.copy(groups = s.groups.map { if (it === group) block(it) else it }) }

    fun merge(group: DupGroupUi) = viewModelScope.launch {
        organizer.merge(group.keep, group.members.map { it.uuid }.filter { it != group.keep })
        _state.update { s -> s.copy(groups = s.groups.filter { it !== group }, message = "Zusammengeführt: „${group.members.first { m -> m.uuid == group.keep }.title}“ bleibt, ${group.members.size - 1} im Papierkorb.") }
    }

    fun ignore(group: DupGroupUi) = viewModelScope.launch {
        organizer.ignoreGroup(group.members.map { it.uuid })
        _state.update { s -> s.copy(groups = s.groups.filter { it !== group }) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun openCompare(group: DupGroupUi) {
        _state.update { it.copy(compareGroup = group, compareA = 0, compareB = 1, diff = null) }
        loadDiff(group, 0, 1)
    }

    fun pick(a: Int, b: Int) {
        val g = _state.value.compareGroup ?: return
        _state.update { it.copy(compareA = a, compareB = b, diff = null) }
        loadDiff(g, a, b)
    }

    fun closeCompare() = _state.update { it.copy(compareGroup = null, diff = null) }

    private fun loadDiff(group: DupGroupUi, a: Int, b: Int) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.Default) {
                val left = TextDiff.paragraphs(repository.text(group.members[a].uuid))
                val right = TextDiff.paragraphs(repository.text(group.members[b].uuid))
                collapse(TextDiff.diff(left, right))
            }
            _state.update { if (it.compareA == a && it.compareB == b) it.copy(diff = rows) else it }
        }
    }

    private fun collapse(lines: List<DiffLine>): List<DiffRow> {
        val out = ArrayList<DiffRow>()
        var run = 0
        for (l in lines) {
            if (l.kind == DiffKind.SAME) { run++; continue }
            if (run > 0) { out += DiffRow(DiffKind.SAME, "", run); run = 0 }
            out += DiffRow(l.kind, l.text)
        }
        if (run > 0) out += DiffRow(DiffKind.SAME, "", run)
        return out
    }
}
