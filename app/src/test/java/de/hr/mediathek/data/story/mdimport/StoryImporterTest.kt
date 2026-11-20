package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.tag.TagNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speicher im Arbeitsspeicher; ahmt Unique-Slug und Duplikaterkennung nach. */
private class FakeStore(private val failOn: Set<String> = emptySet()) : ImportStore {
    class Row(var story: ParsedStory, val uuid: String, var trashed: Boolean = false)

    val rows = LinkedHashMap<String, Row>()
    var transactions = 0
    private var snapshot: Map<String, Pair<ParsedStory, Boolean>>? = null

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        transactions++
        val before = rows.mapValues { it.value.story to it.value.trashed }
        try {
            return block()
        } catch (e: Throwable) {
            rows.clear()
            before.forEach { (k, v) -> rows[k] = Row(v.first, "u-$k", v.second) }
            throw e
        }
    }

    override suspend fun find(slug: String): ExistingStory? = rows[slug]?.let {
        ExistingStory(
            it.uuid, it.trashed, it.story.hash, it.story.title, it.story.category, it.story.author, it.story.status,
            it.story.publishedAt, it.story.tags.map { t -> TagNames.normalize(t) }.toSet(),
        )
    }

    override suspend fun duplicateOf(story: ParsedStory): String? =
        rows.values.firstOrNull { !it.trashed && it.story.slug != story.slug && it.story.hash == story.hash }?.story?.title

    override suspend fun insert(story: ParsedStory) {
        if (story.slug in failOn) error("Platte voll")
        check(story.slug !in rows) { "Slug doppelt" }
        rows[story.slug] = Row(story, "u-${story.slug}")
    }

    override suspend fun update(existing: ExistingStory, story: ParsedStory) {
        rows.getValue(story.slug).story = story
    }
}

class StoryImporterTest {
    private fun item(name: String, raw: String) = ImportItem(name) { MarkdownStoryParser.parse(name, raw, now = 1L) }

    private fun source(vararg items: ImportItem) = ImportSource("Test", items.size, items.asSequence())

    private fun run(store: ImportStore, vararg items: ImportItem, batch: Int = 100): ImportReport =
        runBlocking { StoryImporter(store, batch).run(source(*items)) }

    private val three = arrayOf(
        item(Samples.FULL_NAME, Samples.FULL),
        item(Samples.MINIMAL_NAME, Samples.MINIMAL),
        item(Samples.DUPLICATE_NAME, Samples.DUPLICATE),
    )

    @Test
    fun fullMinimalAndDuplicate() {
        val store = FakeStore()
        val r = run(store, *three)
        assertEquals(2, r.created)
        assertEquals(0, r.updated)
        assertEquals(1, r.duplicates.size)
        assertEquals(Samples.DUPLICATE_NAME, r.duplicates[0].file)
        assertTrue(r.duplicates[0].reason.contains("Die Reise ans Meer"))
        assertTrue(r.errors.isEmpty())
        assertEquals(setOf("2026-08-12-die-reise-ans-meer", "2026-09-03-kurz"), store.rows.keys)
    }

    @Test
    fun importingTwiceChangesNothing() {
        val store = FakeStore()
        run(store, *three)
        val before = store.rows.mapValues { it.value.story }
        val r = run(store, *three)
        assertEquals(0, r.created)
        assertEquals(0, r.updated)
        assertEquals(2, r.unchanged)
        assertEquals(1, r.duplicates.size)
        assertEquals(before, store.rows.mapValues { it.value.story })
        assertEquals(2, store.rows.size)
    }

    @Test
    fun changedFileUpdatesInPlace() {
        val store = FakeStore()
        run(store, item("a.md", "---\ntitle: Alt\n---\nText eins"))
        val r = run(store, item("a.md", "---\ntitle: Neu\n---\nText zwei"))
        assertEquals(1, r.updated)
        assertEquals(1, store.rows.size)
        assertEquals("Neu", store.rows.getValue("a").story.title)
        assertEquals("Text zwei", store.rows.getValue("a").story.body)
    }

    @Test
    fun metadataOnlyChangeIsAnUpdate() {
        val store = FakeStore()
        run(store, item("a.md", "---\ntitle: T\ntags: [x]\n---\nText"))
        val r = run(store, item("a.md", "---\ntitle: T\ntags: [x, y]\n---\nText"))
        assertEquals(1, r.updated)
        assertEquals(listOf("x", "y"), store.rows.getValue("a").story.tags)
    }

    @Test
    fun errorsDoNotAbortAndAreReported() {
        val store = FakeStore()
        val r = run(
            store,
            item("ok1.md", "---\ntitle: Eins\n---\nA"),
            item("kaputt.md", "---\ntitle: [x\n---\nB"),
            item("ohne-titel.md", "---\ncategory: m\n---\nC"),
            item("kein-frontmatter.md", "Nur Text"),
            ImportItem("wirft.md") { error("Datei nicht lesbar") },
            item("ok2.md", "---\ntitle: Zwei\n---\nD"),
        )
        assertEquals(2, r.created)
        assertEquals(4, r.errors.size)
        assertEquals(listOf("kaputt.md", "ohne-titel.md", "kein-frontmatter.md", "wirft.md"), r.errors.map { it.file })
        assertTrue(r.errors[1].reason.contains("title"))
        assertEquals(setOf("ok1", "ok2"), store.rows.keys)
    }

    @Test
    fun sameSlugInTwoFoldersIsReported() {
        val store = FakeStore()
        val r = run(
            store,
            item("2026/08/a.md", "---\ntitle: A\n---\nErster"),
            item("2026/09/a.md", "---\ntitle: A2\n---\nZweiter"),
        )
        assertEquals(1, r.created)
        assertEquals(1, r.errors.size)
        assertEquals("2026/09/a.md", r.errors[0].file)
        assertEquals("Erster", store.rows.getValue("a").story.body)
    }

    @Test
    fun failingBatchIsRetriedFileByFile() {
        val store = FakeStore(failOn = setOf("b"))
        val r = run(
            store,
            item("a.md", "---\ntitle: A\n---\nA-Text"),
            item("b.md", "---\ntitle: B\n---\nB-Text"),
            item("c.md", "---\ntitle: C\n---\nC-Text"),
            batch = 10,
        )
        assertEquals(2, r.created)
        assertEquals(1, r.errors.size)
        assertEquals("b.md", r.errors[0].file)
        assertTrue(r.errors[0].reason.startsWith("Speichern fehlgeschlagen"))
        assertEquals(setOf("a", "c"), store.rows.keys)
    }

    @Test
    fun batchesAndProgress() {
        val store = FakeStore()
        val items = (1..25).map { item("s$it.md", "---\ntitle: S$it\n---\nText $it") }.toTypedArray()
        val seen = ArrayList<ImportProgress>()
        val r = runBlocking { StoryImporter(store, batchSize = 10).run(source(*items)) { seen += it } }
        assertEquals(25, r.created)
        assertEquals(listOf(10, 20, 25), seen.map { it.done })
        assertTrue(seen.all { it.total == 25 })
        assertEquals(3, store.transactions)
    }

    @Test
    fun trashedStoryIsNotTouched() {
        val store = FakeStore()
        run(store, item("a.md", "---\ntitle: A\n---\nText"))
        store.rows.getValue("a").trashed = true
        val r = run(store, item("a.md", "---\ntitle: Anders\n---\nAnderer Text"))
        assertEquals(0, r.updated)
        assertEquals("A", store.rows.getValue("a").story.title)
        assertTrue(r.notes.single().reason.startsWith(ImportReport.TRASH_NOTE))
    }

    @Test
    fun reportTextListsProblems() {
        val r = run(FakeStore(), *three, item("x.md", "kaputt"))
        val text = r.toText()
        assertTrue(text.contains("Neu angelegt: 2"))
        assertTrue(text.contains("- x.md: Kein YAML-Frontmatter"))
        assertTrue(text.contains("Duplikate (1)"))
    }

    @Test
    fun categoryStaysMixedForUnknown() {
        val store = FakeStore()
        run(store, item(Samples.FULL_NAME, Samples.FULL))
        assertEquals(StoryCategory.MIXED, store.rows.values.single().story.category)
    }
}
