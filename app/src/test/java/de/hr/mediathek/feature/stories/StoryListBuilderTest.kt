package de.hr.mediathek.feature.stories

import de.hr.mediathek.data.db.SeriesRow
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryFolderEntity
import de.hr.mediathek.data.db.StoryFolderItemEntity
import de.hr.mediathek.data.db.StoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryListBuilderTest {
    private fun row(id: String, title: String, series: String? = null, order: Int? = null, cat: StoryCategory = StoryCategory.MIXED, read: Float = 0f, last: Long? = null) =
        StoryRow(id, title, cat, 0, false, false, null, 1, 1, null, series, order, null, last, read, 100)

    private val data = StoryData(
        active = listOf(row("a", "Alpha"), row("b", "Teil B", "s1", 2), row("c", "Teil A", "s1", 1), row("d", "Zeta", cat = StoryCategory.WOMEN)),
        series = listOf(SeriesRow("s1", "Meine Serie")),
        folders = listOf(StoryFolderEntity(uuid = "f1", name = "Favoriten")),
        folderItems = listOf(StoryFolderItemEntity(folderUuid = "f1", storyUuid = "a")),
    )

    @Test fun listGroupsSeriesAndSorts() {
        val items = StoryListBuilder.build(data, StoryFilter(sort = StorySort.TITLE), emptySet())
        assertEquals(listOf("story:a", "series:s1", "story:d"), items.map { it.key })
    }

    @Test fun expandedSeriesShowsPartsInOrder() {
        val items = StoryListBuilder.build(data, StoryFilter(sort = StorySort.TITLE), setOf("series:s1"))
        assertEquals(listOf("story:a", "series:s1", "story:c", "story:b", "story:d"), items.map { it.key })
    }

    @Test fun treeHasCategoryHeaders() {
        val items = StoryListBuilder.build(data, StoryFilter(view = StoryView.TREE), setOf("cat:MIXED"))
        assertTrue(items.first() is ListItem.Header)
        assertEquals(1, items.count { it is ListItem.Header && it.category == StoryCategory.WOMEN })
    }

    @Test fun foldersViewShowsFolderAndLoose() {
        val root = StoryListBuilder.build(data, StoryFilter(view = StoryView.FOLDERS), emptySet())
        assertEquals(listOf("folder:f1", "folder:$NO_FOLDER"), root.map { it.key })
        val inside = StoryListBuilder.build(data, StoryFilter(view = StoryView.FOLDERS, folder = "f1"), emptySet())
        assertEquals(listOf("story:a"), inside.map { it.key })
    }

    @Test fun readStateFilterAndResume() {
        val rows = listOf(row("x", "X", read = 0.4f, last = 5), row("y", "Y", read = 1f, last = 9), row("z", "Z"))
        val f = StoryFilter(readState = ReadState.STARTED)
        assertEquals(listOf("x"), rows.filter { StoryListBuilder.matches(it, f) }.map { it.uuid })
        assertEquals(listOf("x"), StoryListBuilder.continueReading(rows).map { it.uuid })
    }
}

class StoryPagingTest {
    private fun row(i: Int, series: String? = null) =
        StoryRow("u$i", "Titel %03d".format(i), StoryCategory.MIXED, 0, false, false, null, i.toLong(), 1, null, series, null, null, null, 0f, 10)

    @Test fun splitsIntoPagesOfTen() {
        val items = (1..25).map { ListItem.Single(row(it), 0) }
        val pages = StoryPaging.paginate(items)
        assertEquals(listOf(10, 10, 5), pages.map { it.size })
        assertEquals(3, StoryPaging.build(items, 99).pageCount)
        assertEquals(2, StoryPaging.build(items, 99).page)
    }

    @Test fun expandedSeriesStaysOnOnePage() {
        val parts = (1..4).map { row(100 + it, "s") }
        val group = ListItem.SeriesGroup("s", "Serie", parts, true, 0)
        val items = (1..9).map { ListItem.Single(row(it), 0) } + group + parts.map { ListItem.Single(it, 1) } + ListItem.Single(row(50), 0)
        val pages = StoryPaging.paginate(items)
        assertEquals(2, pages.size)
        assertEquals(9 + 1 + 4, pages[0].size)
        assertEquals(1, pages[1].size)
    }

    @Test fun emptyListHasOnePage() {
        val p = StoryPaging.build(emptyList(), 3)
        assertEquals(1, p.pageCount)
        assertEquals(0, p.page)
    }

    @Test fun letterMapPointsToPages() {
        val items = (1..15).map { ListItem.Single(row(it).copy(title = if (it <= 10) "Anna $it" else "Berta $it"), 0) }
        val p = StoryPaging.build(items, 0)
        assertEquals(0, p.letterPages['A'])
        assertEquals(1, p.letterPages['B'])
    }
}
