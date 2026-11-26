package de.hr.mediathek.feature.stories

import de.hr.mediathek.data.db.SeriesRow
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryFolderEntity
import de.hr.mediathek.data.db.StoryFolderItemEntity
import de.hr.mediathek.data.db.StoryRow

enum class StorySort(val label: String) {
    NEWEST("Neueste"), TITLE("Titel"), RATING("Bewertung"), LAST_READ("Zuletzt gelesen"), LENGTH("Länge")
}
enum class ReadState(val label: String) { ALL("Alle"), UNREAD("Ungelesen"), STARTED("Angefangen"), DONE("Gelesen") }
enum class StoryView(val label: String) { LIST("Liste"), TREE("Baum"), FOLDERS("Ordner") }

const val NO_FOLDER = "__none__"
const val READ_DONE_AT = 0.97f

data class StoryFilter(
    val query: String = "",
    val category: StoryCategory? = null,
    val favoritesOnly: Boolean = false,
    val continueOnly: Boolean = false,
    /** Nur Geschichten mit der Markierung „Teile fehlen noch“. */
    val partsMissingOnly: Boolean = false,
    /** Nur kurze Geschichten (weniger Wörter als dieser Wert), z. B. um Bruchstücke aufzuräumen. */
    val maxWords: Int? = null,
    val sort: StorySort = StorySort.NEWEST,
    val trash: Boolean = false,
    val view: StoryView = StoryView.LIST,
    val readState: ReadState = ReadState.ALL,
    val folder: String? = null,
)

data class StoryData(
    val active: List<StoryRow> = emptyList(),
    val series: List<SeriesRow> = emptyList(),
    val folders: List<StoryFolderEntity> = emptyList(),
    val folderItems: List<StoryFolderItemEntity> = emptyList(),
)

sealed interface ListItem {
    val key: String

    data class Header(val category: StoryCategory, val count: Int, val expanded: Boolean) : ListItem {
        override val key get() = "cat:${category.name}"
    }

    /** [partNo]/[seriesName] sind gesetzt, wenn die Geschichte als Teil einer aufgeklappten Serie erscheint. */
    data class Single(val row: StoryRow, val indent: Int, val partNo: Int? = null, val seriesName: String? = null) : ListItem {
        override val key get() = "story:${row.uuid}"
    }

    data class SeriesGroup(val uuid: String, val name: String, val parts: List<StoryRow>, val expanded: Boolean, val indent: Int) : ListItem {
        override val key get() = "series:$uuid"
        val avgProgress: Float get() = if (parts.isEmpty()) 0f else parts.map { it.readProgress.coerceIn(0f, 1f) }.average().toFloat()
        val doneCount: Int get() = parts.count { it.readProgress >= READ_DONE_AT }

        /** Teil, bei dem es weitergeht: zuletzt gelesener angefangener, sonst der erste ungelesene. */
        val resumePart: StoryRow
            get() = parts.filter { it.lastReadAt != null && it.readProgress < READ_DONE_AT }.maxByOrNull { it.lastReadAt ?: 0L }
                ?: parts.firstOrNull { it.readProgress < READ_DONE_AT && it.lastReadAt == null }
                ?: parts.first()
    }

    data class Folder(val folder: StoryFolderEntity, val storyCount: Int, val subfolders: Int) : ListItem {
        override val key get() = "folder:${folder.uuid}"
    }
}

/** Stellt aus den Daten und dem Filter die Zeilen der Liste zusammen (rein, deshalb testbar). */
object StoryListBuilder {
    fun matches(s: StoryRow, f: StoryFilter): Boolean {
        val q = f.query.trim().lowercase()
        return (f.category == null || s.category == f.category) &&
            (!f.favoritesOnly || s.favorite) &&
            (!f.continueOnly || s.continueFlag) &&
            (!f.partsMissingOnly || s.partsMissing) &&
            (f.maxWords == null || s.wordCount < f.maxWords) &&
            when (f.readState) {
                ReadState.ALL -> true
                ReadState.UNREAD -> s.lastReadAt == null && s.readProgress <= 0f
                ReadState.STARTED -> s.lastReadAt != null && s.readProgress < READ_DONE_AT
                ReadState.DONE -> s.readProgress >= READ_DONE_AT
            } &&
            (q.isEmpty() || s.title.lowercase().contains(q) ||
                s.sourceText?.lowercase()?.contains(q) == true || s.author?.lowercase()?.contains(q) == true)
    }

    private fun partComparator() =
        compareBy<StoryRow>({ it.seriesOrder ?: 1_000_000 }, { it.createdAt }, { it.title.lowercase() })

    private fun sortKeyTitle(i: ListItem): String = when (i) {
        is ListItem.Single -> i.row.title
        is ListItem.SeriesGroup -> i.name
        is ListItem.Folder -> i.folder.name
        is ListItem.Header -> ""
    }.lowercase()

    private fun entries(rows: List<StoryRow>, f: StoryFilter, series: Map<String, String>, expanded: Set<String>, indent: Int): List<ListItem> {
        val bySeries = rows.filter { it.seriesUuid != null }.groupBy { it.seriesUuid!! }
        val out = ArrayList<ListItem>()
        for (r in rows) if (r.seriesUuid == null || (bySeries[r.seriesUuid]?.size ?: 0) < 2) out += ListItem.Single(r, indent)
        for ((id, parts) in bySeries) {
            if (parts.size < 2) continue
            out += ListItem.SeriesGroup(id, series[id] ?: "Serie", parts.sortedWith(partComparator()), "series:$id" in expanded, indent)
        }
        fun rowsOf(i: ListItem): List<StoryRow> = when (i) {
            is ListItem.Single -> listOf(i.row)
            is ListItem.SeriesGroup -> i.parts
            else -> emptyList()
        }
        val sorted = when (f.sort) {
            StorySort.NEWEST -> out.sortedByDescending { i -> rowsOf(i).maxOf { it.createdAt } }
            StorySort.TITLE -> out.sortedBy(::sortKeyTitle)
            StorySort.RATING -> out.sortedByDescending { i -> rowsOf(i).maxOf { it.rating } }
            StorySort.LAST_READ -> out.sortedByDescending { i -> rowsOf(i).maxOf { it.lastReadAt ?: 0L } }
            StorySort.LENGTH -> out.sortedByDescending { i -> rowsOf(i).sumOf { it.wordCount } }
        }
        // Aufgeklappte Serien zeigen ihre Kapitel direkt darunter.
        val flat = ArrayList<ListItem>()
        for (i in sorted) {
            flat += i
            if (i is ListItem.SeriesGroup && i.expanded) i.parts.forEachIndexed { n, p -> flat += ListItem.Single(p, indent + 1, n + 1, i.name) }
        }
        return flat
    }

    fun build(data: StoryData, f: StoryFilter, expanded: Set<String>): List<ListItem> {
        val seriesNames = data.series.associate { it.uuid to it.name }
        val rows = data.active.filter { matches(it, f) }
        return when (f.view) {
            StoryView.LIST -> entries(rows, f, seriesNames, expanded, 0)
            StoryView.TREE -> StoryCategory.entries.flatMap { c ->
                val inCat = rows.filter { it.category == c }
                if (inCat.isEmpty()) emptyList()
                else {
                    val open = "cat:${c.name}" in expanded
                    listOf<ListItem>(ListItem.Header(c, inCat.size, open)) + if (open) entries(inCat, f, seriesNames, expanded, 1) else emptyList()
                }
            }
            StoryView.FOLDERS -> foldersView(data, rows, f, seriesNames, expanded)
        }
    }

    private fun foldersView(data: StoryData, rows: List<StoryRow>, f: StoryFilter, series: Map<String, String>, expanded: Set<String>): List<ListItem> {
        val itemsByFolder = data.folderItems.groupBy { it.folderUuid }
        val inAnyFolder = data.folderItems.map { it.storyUuid }.toSet()
        val current = f.folder
        val out = ArrayList<ListItem>()
        if (current == NO_FOLDER) return entries(rows.filter { it.uuid !in inAnyFolder }, f, series, expanded, 0)
        val subs = data.folders.filter { it.parentUuid == current }
        for (s in subs) out += ListItem.Folder(s, countIn(s.uuid, data, itemsByFolder), data.folders.count { it.parentUuid == s.uuid })
        if (current == null) {
            val loose = rows.count { it.uuid !in inAnyFolder }
            if (loose > 0) out += ListItem.Folder(StoryFolderEntity(uuid = NO_FOLDER, name = "Ohne Ordner", createdAt = 0), loose, 0)
        } else {
            val ids = itemsByFolder[current].orEmpty().map { it.storyUuid }.toSet()
            out += entries(rows.filter { it.uuid in ids }, f, series, expanded, 0)
        }
        return out
    }

    private fun countIn(folder: String, data: StoryData, byFolder: Map<String, List<StoryFolderItemEntity>>): Int {
        val ids = HashSet<String>()
        fun walk(id: String) {
            byFolder[id]?.forEach { ids += it.storyUuid }
            data.folders.filter { it.parentUuid == id }.forEach { walk(it.uuid) }
        }
        walk(folder)
        return ids.size
    }

    /** Zuletzt gelesene, noch nicht fertige Geschichten für die Weiterlesen-Leiste. */
    fun continueReading(active: List<StoryRow>, limit: Int = 12): List<StoryRow> =
        active.filter { it.lastReadAt != null && it.readProgress < READ_DONE_AT }
            .sortedByDescending { it.lastReadAt }.take(limit)
}

/** Ergebnis der Seitenaufteilung für die Anzeige. */
data class PagedUi(
    val items: List<ListItem> = emptyList(),
    val page: Int = 0,
    val pageCount: Int = 1,
    val total: Int = 0,
    /** Anfangsbuchstabe -> Seite, auf der die erste Geschichte mit diesem Buchstaben steht (für die A-Z-Leiste). */
    val letterPages: Map<Char, Int> = emptyMap(),
    val loaded: Boolean = false,
)

/** Teilt die Liste in Seiten mit je [PAGE_SIZE] Einträgen; eine aufgeklappte Serie bleibt samt Kapiteln auf einer Seite. */
object StoryPaging {
    const val PAGE_SIZE = 10

    fun paginate(items: List<ListItem>, pageSize: Int = PAGE_SIZE): List<List<ListItem>> {
        val pages = ArrayList<List<ListItem>>()
        var current = ArrayList<ListItem>()
        var units = 0
        val pendingHeaders = ArrayList<ListItem>()
        var seriesIndent = -1
        for (item in items) {
            when {
                item is ListItem.Header -> {
                    seriesIndent = -1
                    // Aufgeklappte Kategorien ziehen ihre Überschrift mit dem ersten Eintrag um; zugeklappte stehen einfach da.
                    if (item.expanded) pendingHeaders += item else current += item
                }
                item is ListItem.Single && seriesIndent >= 0 && item.indent == seriesIndent + 1 -> current += item
                else -> {
                    seriesIndent = if (item is ListItem.SeriesGroup && item.expanded) item.indent else -1
                    if (units >= pageSize) {
                        pages += current
                        current = ArrayList()
                        units = 0
                    }
                    current += pendingHeaders
                    pendingHeaders.clear()
                    current += item
                    units++
                }
            }
        }
        current += pendingHeaders
        if (current.isNotEmpty() || pages.isEmpty()) pages += current
        return pages
    }

    fun build(items: List<ListItem>, requestedPage: Int, pageSize: Int = PAGE_SIZE): PagedUi {
        val pages = paginate(items, pageSize)
        val page = requestedPage.coerceIn(0, pages.lastIndex)
        val letters = LinkedHashMap<Char, Int>()
        pages.forEachIndexed { p, list ->
            for (i in list) {
                val title = when (i) {
                    is ListItem.Single -> if (i.indent == 0) i.row.title else null
                    is ListItem.SeriesGroup -> i.name
                    else -> null
                }
                val c = title?.trim()?.firstOrNull()?.uppercaseChar()?.let { if (it in 'A'..'Z') it else '#' }
                if (c != null && c !in letters) letters[c] = p
            }
        }
        val total = items.count { it is ListItem.SeriesGroup || it is ListItem.Folder || (it is ListItem.Single && it.indent <= 1 && !isPart(items, it)) }
        return PagedUi(pages[page], page, pages.size, total, letters, loaded = true)
    }

    private fun isPart(items: List<ListItem>, single: ListItem.Single): Boolean =
        single.row.seriesUuid != null && items.any { it is ListItem.SeriesGroup && it.uuid == single.row.seriesUuid && it.expanded }
}
