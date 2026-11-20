package de.hr.mediathek.data.story.mdimport

import de.hr.mediathek.data.db.StoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarkdownStoryParserTest {
    private fun ok(name: String, raw: String): ParsedStory =
        (MarkdownStoryParser.parse(name, raw, now = 1_000L) as ParseResult.Ok).story

    private fun fail(name: String, raw: String): String =
        (MarkdownStoryParser.parse(name, raw, now = 1_000L) as ParseResult.Fail).reason

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun fullFile() {
        val s = ok(Samples.FULL_NAME, Samples.FULL)
        assertEquals("2026-08-12-die-reise-ans-meer", s.slug)
        assertEquals("Die Reise ans Meer", s.title)
        assertEquals("Anna", s.author)
        assertEquals("published", s.status)
        // "abenteuer" ist keine bekannte Kategorie: MIXED, der Wert bleibt als Tag
        assertEquals(StoryCategory.MIXED, s.category)
        assertEquals(listOf("meer", "freundschaft", "abenteuer"), s.tags)
        assertEquals(ms("2026-08-12T10:00:00Z"), s.createdAt)
        assertEquals(ms("2026-08-13T11:30:00Z"), s.updatedAt)
        assertEquals(ms("2026-08-14T08:00:00Z"), s.publishedAt)
        assertEquals("Es war einmal ein kleines Boot...\n\nUnd es fuhr hinaus.\n", s.body)
    }

    @Test
    fun minimalFileUsesDefaults() {
        val s = ok(Samples.MINIMAL_NAME, Samples.MINIMAL)
        assertEquals("2026-09-03-kurz", s.slug)
        assertEquals(emptyList<String>(), s.tags)
        assertNull(s.author)
        assertNull(s.publishedAt)
        assertEquals("draft", s.status)
        assertEquals(StoryCategory.MIXED, s.category)
        // createdAt aus dem Datum im Dateinamen, updatedAt folgt createdAt
        assertEquals(ms("2026-09-03T00:00:00Z"), s.createdAt)
        assertEquals(s.createdAt, s.updatedAt)
        assertEquals("Nur ein Satz.\n", s.body)
    }

    @Test
    fun withoutDateInNameCreatedAtIsNow() {
        assertEquals(1_000L, ok("ohne-datum.md", Samples.MINIMAL).createdAt)
    }

    @Test
    fun knownCategoriesAreMapped() {
        fun cat(c: String) = ok("a.md", "---\ntitle: T\ncategory: $c\n---\nText").category
        assertEquals(StoryCategory.MEN, cat("Männer"))
        assertEquals(StoryCategory.WOMEN, cat("frauen"))
        assertEquals(StoryCategory.MIXED, cat("gemischt"))
    }

    @Test
    fun tagsAsCommaStringAndDuplicates() {
        val s = ok("a.md", "---\ntitle: T\ntags: \"Meer, #meer, Strand\"\n---\nText")
        assertEquals(listOf("Meer", "Strand"), s.tags)
    }

    @Test
    fun bodyIsKeptAsIs() {
        val s = ok("a.md", "---\ntitle: T\n---\n\n  Einrückung bleibt.\n\n\nZwei Leerzeilen.\n---\nKein Frontmatter-Ende mehr.\n")
        assertEquals("\n  Einrückung bleibt.\n\n\nZwei Leerzeilen.\n---\nKein Frontmatter-Ende mehr.\n", s.body)
    }

    @Test
    fun crlfAndBomAreHandled() {
        val s = ok("a.md", "﻿---\r\ntitle: Mit BOM\r\n---\r\nZeile eins\r\nZeile zwei\r\n")
        assertEquals("Mit BOM", s.title)
        assertEquals("Zeile eins\nZeile zwei\n", s.body)
    }

    @Test
    fun missingTitleIsAnError() {
        assertTrue(fail("a.md", "---\ncategory: x\n---\nText").contains("title"))
        assertTrue(fail("a.md", "---\n---\nText").contains("title"))
    }

    @Test
    fun missingOrBrokenFrontmatter() {
        assertTrue(fail("a.md", "Nur Text").contains("Frontmatter"))
        assertTrue(fail("a.md", "---\ntitle: T\nText ohne Ende").contains("nicht mit ---"))
        assertTrue(fail("a.md", "---\ntitle: [unklar\n---\nText").startsWith("YAML ungültig"))
        assertTrue(fail("a.md", "---\n- eins\n- zwei\n---\nText").contains("kein YAML-Objekt"))
    }

    @Test
    fun emptyBodyIsAnError() {
        assertEquals("Text ist leer", fail("a.md", "---\ntitle: T\n---\n  \n"))
    }

    @Test
    fun invalidDateGivesWarningNotError() {
        val r = MarkdownStoryParser.parse("a.md", "---\ntitle: T\ncreatedAt: gestern\n---\nText", now = 5L) as ParseResult.Ok
        assertEquals(5L, r.story.createdAt)
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun hashIgnoresLineEndingsAndOuterWhitespace() {
        val a = ok(Samples.FULL_NAME, Samples.FULL)
        val b = ok(Samples.DUPLICATE_NAME, Samples.DUPLICATE)
        assertEquals(a.hash, b.hash)
        assertEquals(64, a.hash.length)
        assertTrue(a.hash != ok(Samples.MINIMAL_NAME, Samples.MINIMAL).hash)
    }
}
