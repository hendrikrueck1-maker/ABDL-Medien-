package de.hr.mediathek.audit3

import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.pkg.ManifestFile
import de.hr.mediathek.data.pkg.PackageLogic
import de.hr.mediathek.data.pkg.PackageManifest
import de.hr.mediathek.data.product.PriceParser
import de.hr.mediathek.data.product.ProductTextParser
import de.hr.mediathek.data.scan.ScanMatching
import de.hr.mediathek.data.search.SearchQuery
import de.hr.mediathek.data.stats.Redactor
import de.hr.mediathek.data.story.mdimport.JsonStoryParser
import de.hr.mediathek.data.story.mdimport.ParseResult
import de.hr.mediathek.data.story.mdimport.ParsedStory
import de.hr.mediathek.data.story.mdimport.StoryImporter
import de.hr.mediathek.data.story.web.LinkImportRules
import de.hr.mediathek.data.story.web.ListingParser
import de.hr.mediathek.data.story.web.PageParser
import de.hr.mediathek.data.story.web.ThreadBuilder
import de.hr.mediathek.data.story.web.WebDates
import de.hr.mediathek.data.story.web.WebPage
import de.hr.mediathek.data.story.web.WebPart
import de.hr.mediathek.data.story.web.WebPost
import de.hr.mediathek.data.story.web.WebStories
import de.hr.mediathek.data.tracker.TrackerStats
import de.hr.mediathek.core.ui.theme.GlassLevel
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Gesamtprüfung 25.11.2026 (Runde 3): die Befunde als Tests, nach den Korrekturen grün. */
class Audit3Test {

    // ---- JSON-Export (StoryExporter.exportJson) -> JSON-Import (JsonStoryParser) ----

    /** Genau die Felder, die StoryExporter.exportJson schreibt. */
    private fun exported(title: String, content: String, slug: String, rating: Int = 4) = JSONObject().apply {
        put("title", title)
        put("category", "women")
        put("tags", JSONArray(listOf("Lieblings-Tag")))
        put("content", content)
        put("createdAt", "2026-11-01T10:00:00Z")
        put("publishedAt", "2004-05-12T17:04:00Z")
        put("source", "beispiel.example")
        put("sourceUrl", "https://beispiel.example/showthread.php?t=1")
        put("rating", rating)
        put("slug", slug)
        put("status", "published")
        put("favorite", true)
        put("continueFlag", true)
        put("updatedAt", "2026-11-02T10:00:00Z")
    }

    private fun parse(o: JSONObject, index: Int = 0): ParsedStory =
        (JsonStoryParser.parseElement(index, o) as ParseResult.Ok).story

    @Test fun json_roundtrip_keeps_rating() = assertEquals(4, parse(exported("Titel A", "Text A", "web-a-1")).rating)

    @Test fun json_roundtrip_keeps_published_status() = assertEquals("published", parse(exported("Titel A", "Text A", "web-a-1")).status)

    @Test fun json_roundtrip_keeps_marks() {
        val s = parse(exported("Titel A", "Text A", "web-a-1"))
        assertEquals(true, s.continueFlag)
        assertEquals(true, s.favorite)
    }

    @Test fun json_roundtrip_two_stories_same_title_get_distinct_ids() {
        val a = parse(exported("Teil 1", "Erster Text", "web-a-1"), 0)
        val b = parse(exported("Teil 1", "Ganz anderer Text", "web-b-1"), 1)
        assertNotEquals(a.slug, b.slug)
    }

    @Test fun different_source_detects_foreign_story() {
        assertTrue(StoryImporter.differentSource("https://a.example/x?t=1", "https://b.example/y?t=2"))
        assertFalse(StoryImporter.differentSource("https://www.a.example/x/", "http://a.example/x#post5"))
        assertFalse(StoryImporter.differentSource(null, "https://a.example/x"))
    }

    // ---- Web-Import: Kennung von Seiten ohne Themen-Nummer ----

    private fun articleThread(url: String) = ThreadBuilder.build(
        url,
        listOf(WebPage("Titel", listOf(WebPost("article", 1, null, null, null, "x".repeat(300))), null, false, "article")),
    )

    @Test fun wordpress_p_links_get_distinct_slugs() {
        val a = articleThread("https://blog.example/?p=101")
        val b = articleThread("https://blog.example/?p=202")
        assertNotEquals(ThreadBuilder.slug(a, a.parts[0]), ThreadBuilder.slug(b, b.parts[0]))
    }

    @Test fun efiction_sid_links_get_distinct_slugs() {
        val a = articleThread("https://archiv.example/viewstory.php?sid=55")
        val b = articleThread("https://archiv.example/viewstory.php?sid=56")
        assertNotEquals(ThreadBuilder.slug(a, a.parts[0]), ThreadBuilder.slug(b, b.parts[0]))
    }

    @Test fun same_page_with_tracking_params_keeps_key() {
        assertEquals(
            ThreadBuilder.threadKey("https://blog.example/geschichte?utm_source=x&page=2"),
            ThreadBuilder.threadKey("https://blog.example/geschichte"),
        )
    }

    @Test fun forum_thread_link_and_page2_same_thread_key() {
        assertEquals(ThreadBuilder.threadKey("https://forum.example/showthread.php?t=4711"), ThreadBuilder.threadKey("https://forum.example/showthread.php?t=4711&page=2"))
    }

    @Test fun forum_links_start_at_page_one() {
        assertEquals("https://forum.example/showthread.php?t=4711", ThreadBuilder.forumFirstPage("https://forum.example/showthread.php?t=4711&page=3"))
        assertEquals("https://blog.example/?p=5&page=2", ThreadBuilder.forumFirstPage("https://blog.example/?p=5&page=2"))
    }

    // ---- Web-Import: erneuter Import ----

    @Test fun web_reimport_preserves_user_data() {
        val t = articleThread("https://forum.example/showthread.php?t=9")
        val stories = WebStories.parsed(t, "Titel", StoryCategory.MIXED, listOf(t.parts[0] to null), 0L)
        assertTrue(stories[0].preserveUserData)
        assertTrue(stories[0].keepTitle)
    }

    // ---- Link-Liste ----

    private fun part(id: String, len: Int, selected: Boolean = true) =
        WebPart(id = id, author = "A", dateMillis = null, dateRelative = false, postTitle = null, text = "x".repeat(len), selected = selected)

    @Test fun link_list_skips_short_posts() {
        val (chosen, short) = LinkImportRules.choose(listOf(part("1", 5000), part("2", 40), part("3", 900), part("4", 2000, selected = false)), 800)
        assertEquals(listOf("1", "3"), chosen.map { it.id })
        assertEquals(1, short)
        assertEquals(3, LinkImportRules.choose(listOf(part("1", 5000), part("2", 40), part("3", 900)), 0).first.size)
    }

    // ---- Datum ----

    @Test fun date_only_is_not_shown_with_midnight() {
        val d = WebDates.findIn("12.05.2004")!!
        assertEquals("12.05.2004", WebDates.format(d.millis))
    }

    @Test fun english_forum_date_is_found() {
        val d = WebDates.findIn("05-12-2004, 07:04 PM")
        assertNotNull(d)
        assertEquals("12.05.2004 19:04", WebDates.format(d!!.millis))
    }

    // ---- Forum-Übersicht ----

    @Test fun listing_keeps_thread_links_with_p_in_other_params() {
        val html = """<html><body><table id="threadslist">
            <tr><td><a href="showthread.php?t=11&amp;sp=1">Thema elf</a></td></tr>
            <tr><td><a href="showthread.php?t=12">Thema zwölf</a></td></tr>
            <tr><td><a href="showthread.php?p=99">Beitrag</a></td></tr></table></body></html>"""
        val links = ListingParser.parse(Jsoup.parse(html, "https://forum.example/forumdisplay.php?f=3"), 1).links
        assertEquals(2, links.size)
    }

    @Test fun article_title_falls_back_to_page_title() {
        val html = "<html><head><title>Mein Titel - Blogname</title></head><body><article>" + "<p>${"Wort ".repeat(80)}</p>" + "</article></body></html>"
        assertEquals("Mein Titel", PageParser.parse(Jsoup.parse(html, "https://blog.example/x")).threadTitle)
    }

    // ---- Paket entfernen ----

    @Test fun package_deletion_only_touches_manifest_files() {
        val m = PackageManifest(
            8, "x", 0, "Paket",
            listOf(
                ManifestFile("u1", "VIDEO", "videos/ab_film.mp4", 1, null, "f", false),
                ManifestFile("u2", "IMAGE", "bilder/cd_bild.jpg", 1, null, "f", false),
                ManifestFile("u3", "IMAGE", "../fremd.jpg", 1, null, "f", false),
                ManifestFile("u4", "IMAGE", "andere/x.jpg", 1, null, "f", false),
            ),
        )
        val plan = PackageLogic.deletionPlan(m)
        assertTrue("videos/ab_film.mp4" in plan)
        assertTrue("bilder/cd_bild.jpg.part" in plan)
        assertTrue(PackageLogic.MANIFEST_NAME in plan && PackageLogic.METADATA_NAME in plan)
        assertFalse(plan.any { it.contains("..") || it.startsWith("andere") })
    }

    // ---- Scan ----

    @Test fun future_timestamp_is_not_recent() {
        val now = 1_000_000_000L
        assertFalse(ScanMatching.isRecent(now, now + 3_600_000, 10_000))
        assertTrue(ScanMatching.isRecent(now, now - 5_000, 10_000))
        assertFalse(ScanMatching.isRecent(now, now - 20_000, 10_000))
    }

    @Test fun compression_paths_are_protected_from_scan() {
        val p = ScanMatching.protectedPaths(listOf("Filme/Urlaub.mkv" to "Urlaub.mp4", "Wurzel.avi" to "Wurzel.mp4"))
        assertEquals(setOf("Filme/Urlaub.mkv", "Filme/Urlaub.mp4", "Wurzel.avi", "Wurzel.mp4"), p)
    }

    // ---- Kleine Logikfehler aus Runde 1 ----

    @Test fun tracker_day_start_on_dst_days() {
        val z = ZoneId.of("Europe/Berlin")
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(z).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 29), TrackerStats.dayOf(at(2026, 3, 29, 6, 30), "Europe/Berlin", 360))
        assertEquals(LocalDate.of(2026, 10, 24), TrackerStats.dayOf(at(2026, 10, 25, 5, 30), "Europe/Berlin", 360))
    }

    @Test fun prices_with_space_thousands_and_ambiguous_comma() {
        assertEquals(1500.0, PriceParser.parse("1 500,00 €").min!!, 0.001)
        assertTrue(PriceParser.parse("1,500").uncertain)
        assertTrue(PriceParser.parse("-1,50 €").uncertain)
        assertFalse(PriceParser.parse("1,50 €").uncertain)
    }

    @Test fun search_folds_eszett_and_limits_terms() {
        assertEquals("strasse*", SearchQuery.parse("Straße")!!.match)
        val many = (1..500).joinToString(" ") { "wort$it" }
        assertEquals(SearchQuery.MAX_TERMS, SearchQuery.parse(many)!!.match.split(' ').size)
    }

    @Test fun redactor_hides_names_with_spaces() {
        val out = Redactor.redact("Fehler: Urlaub am See.mp4 nicht lesbar")
        assertFalse(out.contains("See"))
        assertFalse(Redactor.redact("ID 1A2B-3C4D:Videos/Mein Film.mkv, Grund x").contains("Mein Film"))
    }

    // ---- Produkt-PDF ----

    @Test fun typo_in_first_criterion_goes_to_unassigned() {
        val p = ProductTextParser.parse("Produkt X\nSaugfähigkeitt 8 sehr gut\nAuslaufschutz 7\n")
        assertTrue(p.unassigned.any { it.label.startsWith("Saugfähigkeitt") && it.number == 8 })
    }

    @Test fun multiline_note_with_number_stays_note() {
        val p = ProductTextParser.parse("Produkt X\nAuslaufschutz 7 gut\nNach 3 Stunden noch trocken\n")
        assertTrue(p.unassigned.none { it.label.startsWith("Nach") })
        assertTrue(p.criteria.first { it.key == "leak_protection" }.note!!.contains("Nach 3 Stunden"))
    }

    // ---- Lesbarkeit: angepasstes Glas ----

    @Test fun glass_gets_denser_on_bright_images() {
        assertEquals(GlassLevel.MIN_ALPHA, GlassLevel.cardAlpha(GlassLevel.level(0f)), 0.001f)
        assertEquals(GlassLevel.MAX_ALPHA, GlassLevel.cardAlpha(GlassLevel.level(1f)), 0.001f)
        assertEquals(0f, GlassLevel.veilAlpha(GlassLevel.level(0.02f)), 0.001f)
    }

    /** Gerechnet wie in der Gesamtprüfung: jede Schriftfarbe auf jeder Bildhelligkeit ≥ 4,5 : 1. */
    @Test fun all_text_colors_readable_on_any_brightness() {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        fun rgb(hex: Int) = doubleArrayOf(((hex shr 16) and 0xFF) / 255.0, ((hex shr 8) and 0xFF) / 255.0, (hex and 0xFF) / 255.0)
        fun lum(c: DoubleArray) = 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
        fun over(fg: DoubleArray, a: Double, bg: DoubleArray) = DoubleArray(3) { a * fg[it] + (1 - a) * bg[it] }
        fun contrast(a: DoubleArray, b: DoubleArray): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
        val card = rgb(0x2A2442); val veil = rgb(0x07060D); val white = rgb(0xFFFFFF)
        val texts = listOf(0xF6F0FF, 0xC9BEDF, 0xFF9CC8, 0xFFA3A3, 0x7EEBC4, 0x8ED6FF, 0xFFE08A).map { rgb(it) }
        for (i in 0..100) {
            val y = i / 100.0
            val c = if (y <= 0.0031308) y * 12.92 else 1.055 * Math.pow(y, 1 / 2.4) - 0.055
            val img = doubleArrayOf(c, c, c)
            val level = GlassLevel.level(y.toFloat()).toDouble()
            var bg = over(veil, GlassLevel.veilAlpha(level.toFloat()).toDouble(), img)
            bg = over(card, GlassLevel.cardAlpha(level.toFloat()).toDouble(), bg)
            bg = over(white, GlassLevel.GLOSS.toDouble(), bg)
            for (t in texts) assertTrue("Kontrast bei Helligkeit $y zu gering", contrast(t, bg) >= 4.5)
        }
    }
}
