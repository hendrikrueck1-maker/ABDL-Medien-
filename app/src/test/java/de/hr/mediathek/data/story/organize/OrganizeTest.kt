package de.hr.mediathek.data.story.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeTest {
    private val base = (1..400).joinToString(" ") { "wort$it" }

    @Test fun identicalHashGroupsTogether() {
        val g = DuplicateDetector.group(listOf(DupInput("a", "h1"), DupInput("b", "h1"), DupInput("c", "h2")), emptyMap())
        assertEquals(1, g.size)
        assertEquals(DupKind.IDENTICAL, g[0].kind)
        assertEquals(setOf("a", "b"), g[0].members.toSet())
    }

    @Test fun slightlyEditedTextIsSimilar() {
        val edited = base.replace("wort100", "geaendert").replace("wort250", "anders") + " Nachwort zum Schluss"
        val other = (1..400).joinToString(" ") { "ganz$it" }
        val sigs = mapOf(
            "a" to DuplicateDetector.signature(base)!!,
            "b" to DuplicateDetector.signature(edited)!!,
            "c" to DuplicateDetector.signature(other)!!,
        )
        val g = DuplicateDetector.group(listOf(DupInput("a", "1"), DupInput("b", "2"), DupInput("c", "3")), sigs)
        assertEquals(1, g.size)
        assertEquals(DupKind.SIMILAR, g[0].kind)
        assertEquals(setOf("a", "b"), g[0].members.toSet())
    }

    @Test fun shortTextHasNoSignature() = assertNull(DuplicateDetector.signature("zu kurz"))

    @Test fun diffMarksChanges() {
        val d = TextDiff.diff(listOf("A", "B", "C"), listOf("A", "X", "C", "D"))
        assertEquals(listOf(DiffKind.SAME, DiffKind.ONLY_LEFT, DiffKind.ONLY_RIGHT, DiffKind.SAME, DiffKind.ONLY_RIGHT), d.map { it.kind })
    }

    @Test fun diffIdentical() = assertTrue(TextDiff.diff(listOf("a b", "c"), listOf("A  B", "C")).all { it.kind == DiffKind.SAME })

    private fun c(id: String, t: String, author: String? = null, series: String? = null) = SeriesCandidate(id, t, author, series, 0)

    @Test fun parsesTitles() {
        assertEquals(2, SeriesSuggester.parse("Larissa und ihr Kleid Teil 2")?.number)
        assertEquals("Larissa und ihr Kleid", SeriesSuggester.parse("Larissa und ihr Kleid - Kapitel 3: Der Besuch")?.base)
        assertEquals(4, SeriesSuggester.parse("Katarina (4)")?.number)
        assertEquals(5, SeriesSuggester.parse("Meine Geschichte 5")?.number)
        assertEquals(3, SeriesSuggester.parse("Name Part III")?.number)
        assertNull(SeriesSuggester.parse("Ein Tag am See"))
        assertNull(SeriesSuggester.parse("Sommer 2024"))
    }

    @Test fun groupsSeriesAndAddsUnnumberedFirstPart() {
        val list = listOf(c("1", "Katarina"), c("2", "Katarina 2"), c("3", "Katarina 3"), c("4", "Anderes"))
        val p = SeriesSuggester.suggest(list, emptyMap())
        assertEquals(1, p.size)
        assertEquals("Katarina", p[0].name)
        assertEquals(listOf("1", "2", "3"), p[0].parts.map { it.uuid })
    }

    @Test fun differentAuthorsSplit() {
        val list = listOf(c("1", "X Teil 1", "Anna"), c("2", "X Teil 2", "Ben"))
        assertTrue(SeriesSuggester.suggest(list, emptyMap()).isEmpty())
    }

    @Test fun addsToExistingSeries() {
        val list = listOf(c("1", "Yvonne Teil 1", series = "s1"), c("2", "Yvonne Teil 2"))
        val p = SeriesSuggester.suggest(list, mapOf("s1" to "Meine Yvonne"))
        assertNotNull(p.firstOrNull())
        assertEquals("s1", p[0].existingSeriesUuid)
        assertEquals(listOf("2"), p[0].parts.map { it.uuid })
    }
}
