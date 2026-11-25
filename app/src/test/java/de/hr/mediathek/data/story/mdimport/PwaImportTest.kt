package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Prüft das Format der Geschichten-PWA (Katalog + Shards, Sicherung, eigene Geschichten). */
class PwaImportTest {
    private class MapContainer(val files: Map<String, String>) : FileContainer {
        override val paths = files.keys.toList()
        override fun read(path: String, limit: Long) = files.getValue(path).toByteArray()
        override fun close() {}
    }

    private val index = """
        [
          {"id":"s0","title":"Titel A","language":"Englisch","platform":"QuelleX","readTime":10,"shard":0,"preview":"x","date":"2011-05-11"},
          {"id":"s1","title":"Serie B – Part 1","language":"Englisch","platform":"QuelleY","shard":0,"date":"2012-01-02","seriesId":"quelley-serie","seriesTitle":"Serie B","seriesPos":1,"seriesTotal":2},
          {"id":"s2","title":"Serie B – Part 2","language":"Englisch","platform":"QuelleY","shard":0,"date":"2012-01-09","seriesId":"quelley-serie","seriesTitle":"Serie B","seriesPos":2,"seriesTotal":2},
          {"id":"s100","title":"Titel C","language":"Deutsch","platform":"QuelleZ","shard":1,"date":"2020-03-04"}
        ]
    """.trimIndent()
    private val shard0 = """{"s0":"Preface: ein Text.","s1":"Chapter one.","s2":"Chapter two."}"""
    private val shard1 = """{"s100":"Ein deutscher Text."}"""

    private val backup = """
        {
          "ratings": {"s0": 4, "s1": 5, "s999": 3, "custom_1": 2},
          "read": {"s0": true},
          "tags": {"s0": {"gender": "w", "ai": true}, "s100": {"gender": "m", "ai": false}, "custom_1": {"gender": "mix"}},
          "wip": {"s100": true, "s1": false},
          "customStories": [
            {"id":"custom_1","title":"Meine Geschichte","text":"Eigener Text.","language":"Deutsch","platform":"eigene","date":"2026-08-12","custom":true}
          ],
          "progress": {"s0": {"scroll": 0.5, "ts": 1}},
          "translations": {}
        }
    """.trimIndent()

    private fun repo() = mapOf(
        "geschichten-main/data/index.json" to index,
        "geschichten-main/data/shard_0.json" to shard0,
        "geschichten-main/data/shard_1.json" to shard1,
        "geschichten-main/README.md" to "# Geschichten",
        "geschichten-main/manifest.json" to "{}",
        "geschichten-main/user-stories/custom_2.json" to """{"id":"custom_2","title":"Aus Sync","text":"Sync-Text.","language":"Deutsch","platform":"eigene","date":"2026-09-01"}""",
    )

    private fun run(store: ImportStore, source: ImportSource) = runBlocking { StoryImporter(store, 2).run(source) }

    @Test
    fun catalogZipImportsEverythingWithSeries() {
        val store = FakeStore()
        val source = ContainerSource.build("ZIP", MapContainer(repo()), now = 1L)
        assertEquals(5, source.total) // 4 Katalog + 1 user-stories, README und manifest ignoriert
        val r = run(store, source)
        assertEquals(5, r.created)
        assertTrue(r.errors.isEmpty())
        val s1 = store.rows.getValue("s1").story
        assertEquals("Serie B – Part 1", s1.title)
        assertEquals("QuelleY", s1.sourceText)
        assertEquals(SeriesRef("Serie B", 1), s1.series)
        assertEquals(listOf("Englisch"), s1.tags)
        assertEquals(Instant.parse("2012-01-02T00:00:00Z").toEpochMilli(), s1.createdAt)
        assertEquals("Chapter one.", s1.body)
        assertEquals(emptyList<String>(), store.rows.getValue("s100").story.tags)
        assertEquals("Sync-Text.", store.rows.getValue("custom_2").story.body)
    }

    @Test
    fun backupAppliesRatingsGenderAndWipToImportedStories() {
        val store = FakeStore()
        run(store, ContainerSource.build("ZIP", MapContainer(repo()), now = 1L))
        val r = run(store, ContainerSource.fromJsonText(backup, now = 1L))
        val s0 = store.rows.getValue("s0").story
        assertEquals(4, s0.rating)
        assertEquals(StoryCategory.WOMEN, s0.category)
        assertTrue("KI" in s0.tags)
        assertEquals(5, store.rows.getValue("s1").story.rating)
        assertEquals(StoryCategory.MEN, store.rows.getValue("s100").story.category)
        assertEquals(true, store.rows.getValue("s100").story.continueFlag)
        // eigene Geschichte aus der Sicherung wird vollständig angelegt, mit Bewertung und ⚥
        val custom = store.rows.getValue("custom_1").story
        assertEquals("Eigener Text.", custom.body)
        assertEquals(2, custom.rating)
        assertEquals(StoryCategory.MIXED, custom.category)
        // s999 gibt es nicht in der App: Hinweis statt Fehler
        assertTrue(r.errors.isEmpty())
        assertTrue(r.notes.any { it.file.contains("Einträge der Sicherung") && it.reason.contains("s999") })
        // gelesen-Markierungen und Lesefortschritt werden nicht übernommen und im Report genannt
        assertTrue(r.notes.any { it.reason.contains("Lesefortschritt") && it.reason.contains("gelesen") })
    }

    @Test
    fun backupIsIdempotentAndCatalogReimportKeepsUserData() {
        val store = FakeStore()
        run(store, ContainerSource.build("ZIP", MapContainer(repo()), now = 1L))
        run(store, ContainerSource.fromJsonText(backup, now = 1L))
        val before = store.rows.mapValues { it.value.story }
        val again = run(store, ContainerSource.fromJsonText(backup, now = 1L))
        assertEquals(0, again.created)
        assertEquals(0, again.updated)
        val catalogAgain = run(store, ContainerSource.build("ZIP", MapContainer(repo()), now = 1L))
        assertEquals(0, catalogAgain.created)
        assertEquals(0, catalogAgain.updated)
        assertEquals(before, store.rows.mapValues { it.value.story })
    }

    @Test
    fun backupInsideTheSameFolderIsAppliedAfterTheCatalog() {
        val store = FakeStore()
        val files = repo() + ("geschichten-main/geschichten-backup.json" to backup)
        val r = run(store, ContainerSource.build("ZIP", MapContainer(files), now = 1L))
        assertEquals(4, store.rows.getValue("s0").story.rating)
        assertEquals(StoryCategory.WOMEN, store.rows.getValue("s0").story.category)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun missingShardTextIsReported() {
        val files = mapOf("data/index.json" to index, "data/shard_0.json" to shard0)
        val r = run(FakeStore(), ContainerSource.build("Ordner", MapContainer(files), now = 1L))
        assertEquals(3, r.created)
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].reason.contains("Shard 1"))
    }

    @Test
    fun singleJsonKinds() {
        assertEquals(1, ContainerSource.fromJsonText("""{"title":"T","text":"Text"}""").total)
        val unknown = run(FakeStore(), ContainerSource.fromJsonText("""{"foo":1}"""))
        assertTrue(unknown.errors.single().reason.startsWith("Unbekanntes JSON-Format"))
        val broken = run(FakeStore(), ContainerSource.fromJsonText("{kaputt"))
        assertTrue(broken.errors.single().reason.startsWith("Kein gültiges JSON"))
        val list = run(FakeStore(), ContainerSource.fromJsonText("""[{"title":"A","content":"x"}]"""))
        assertEquals(1, list.created)
    }

    @Test
    fun markdownFromPwaDownloadWorks() {
        // Format der PWA-Funktion „Als Datei herunterladen“: title in Anführungszeichen, date statt createdAt
        val md = "---\ntitle: \"Meine Geschichte\"\ndate: 2026-08-12\n---\n\nEs war einmal.\n"
        val s = (MarkdownStoryParser.parse("meine-geschichte.md", md, 1L) as ParseResult.Ok).story
        assertEquals("Meine Geschichte", s.title)
        assertEquals(Instant.parse("2026-08-12T00:00:00Z").toEpochMilli(), s.createdAt)
    }

    @Test
    fun genderMapping() {
        assertEquals(StoryCategory.MEN, PwaFormat.genderToCategory("m"))
        assertEquals(StoryCategory.WOMEN, PwaFormat.genderToCategory("w"))
        assertEquals(StoryCategory.MIXED, PwaFormat.genderToCategory("mix"))
        assertNull(PwaFormat.genderToCategory(""))
        assertTrue(PwaFormat.isBackup(JSONObject("""{"ratings":{}}""")))
    }
}
