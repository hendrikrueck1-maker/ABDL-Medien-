package de.hr.mediathek.audit

import de.hr.mediathek.data.product.PriceParser
import de.hr.mediathek.data.product.Stars
import de.hr.mediathek.data.stats.Redactor
import de.hr.mediathek.data.story.DocxReader
import de.hr.mediathek.data.tracker.TrackerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Audit-Tests fuer reine Logik. Rote Tests dokumentieren Befunde (siehe Auditbericht),
 * gruene sichern das Soll-Verhalten ab.
 */
class AuditPureLogicTest {

    // --- Preisparser: Liste aus dem Testprompt ---------------------------------------------

    private fun p(s: String, pack: Int? = null) = PriceParser.parse(s, pack)

    @Test fun price_spec_list_basic() {
        assertEquals(1.5, p("1,50€").min!!, 1e-9)
        assertEquals(1.5, p("1.50€").min!!, 1e-9)
        assertEquals(1.5, p("1,50 EUR").min!!, 1e-9)
        assertEquals(1.5, p("€1.50").min!!, 1e-9)
        p("1-1,50€").let { assertEquals(1.0, it.min!!, 1e-9); assertEquals(1.5, it.max!!, 1e-9) }
        p("1.00 - 1.50 €").let { assertEquals(1.0, it.min!!, 1e-9); assertEquals(1.5, it.max!!, 1e-9) }
        assertTrue(p("1.500").uncertain)
        assertTrue(p("ca. 2 €").uncertain)
        assertTrue(p("ab 1,20").uncertain)
        p("15 € / 10 Stk").let { assertEquals(1.5, it.min!!, 1e-9); assertTrue(it.fromPack) }
        assertEquals(0.0, p("0").min!!, 1e-9)
        assertNull(p("-").min)
        assertNull(p("keine Angabe").min)
    }

    @Test fun price_nbsp_and_spaces() {
        assertEquals(1.5, p("1,50 €").min!!, 1e-9)
        assertEquals(1.5, p("  1,50 €  ").min!!, 1e-9)
    }

    /** Befund: Tausendertrenner mit Leerzeichen wird als zwei Zahlen gelesen (1 statt 1500). */
    @Test fun price_space_thousands_separator() {
        assertEquals(1500.0, p("1 500,00 €").min!!, 1e-9)
    }

    /** Befund: "1,500" (englischer Tausender) wird still als 1,5 gelesen und nicht als unsicher markiert. */
    @Test fun price_comma_three_digits_is_uncertain() {
        assertTrue(p("1,500").uncertain)
    }

    /** Befund: negatives Vorzeichen geht verloren, -1,50 wird zu 1,50. */
    @Test fun price_negative_is_not_silently_positive() {
        val r = p("-1,50 €")
        assertTrue("negativer Preis darf nicht als +1,50 gelten", r.min == null || r.uncertain)
    }

    @Test fun price_never_throws_on_garbage() {
        val inputs = listOf(
            "", " ", "€", "--", "1..2", "1,,2", ",5", ".5", "9".repeat(400), "1e10", "١٫٥", "½ €", "1/0", "15 € / 0 Stk",
            "'; DROP TABLE product; --", "%s %d {0}", "1–2 €", "1 bis 2 €", "1,5-", "-1,5-", "€-1",
        )
        for (s in inputs) PriceParser.parse(s, 0)
    }

    @Test fun stars_rules() {
        assertNull(Stars.compute(emptyList()))
        assertEquals(1, Stars.compute(listOf(1)))
        assertEquals(5, Stars.compute(listOf(10)))
        assertEquals(3, Stars.compute(listOf(5, 6)))
    }

    // --- Tracker: Tagesbeginn und Zeitumstellung ------------------------------------------

    private val berlin = "Europe/Berlin"
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of(berlin)).toInstant().toEpochMilli()

    @Test fun dayStart_boundaries_normal_day() {
        val s = 6 * 60
        assertEquals(LocalDate.of(2026, 6, 9), TrackerStats.dayOf(at(2026, 6, 10, 5, 59), berlin, s))
        assertEquals(LocalDate.of(2026, 6, 10), TrackerStats.dayOf(at(2026, 6, 10, 6, 0), berlin, s))
        assertEquals(LocalDate.of(2026, 6, 10), TrackerStats.dayOf(at(2026, 6, 10, 23, 59), berlin, s))
        assertEquals(LocalDate.of(2026, 6, 10), TrackerStats.dayOf(at(2026, 6, 11, 0, 0), berlin, s))
    }

    /** Befund: Frühjahr (29.03.2026, 02:00 -> 03:00). 06:30 Ortszeit gehört zum Sonntag. */
    @Test fun dayStart_dst_spring_forward() {
        assertEquals(LocalDate.of(2026, 3, 29), TrackerStats.dayOf(at(2026, 3, 29, 6, 30), berlin, 360))
    }

    /** Befund: Herbst (25.10.2026, 03:00 -> 02:00). 05:30 Ortszeit liegt vor dem Tagesbeginn und gehört zum Samstag. */
    @Test fun dayStart_dst_fall_back() {
        assertEquals(LocalDate.of(2026, 10, 24), TrackerStats.dayOf(at(2026, 10, 25, 5, 30), berlin, 360))
    }

    @Test fun leapDay_2028() {
        assertEquals(LocalDate.of(2028, 2, 29), TrackerStats.dayOf(at(2028, 2, 29, 12), berlin, 360))
    }

    @Test fun invalidZoneDoesNotCrash() {
        TrackerStats.dayOf(0L, "Mars/Olympus", 360)
        TrackerStats.dayOf(Long.MIN_VALUE / 1000, berlin, 360)
    }

    // --- Schwärzen im Fehlerprotokoll -----------------------------------------------------

    /** Befund: Dateinamen mit Leerzeichen bleiben teilweise lesbar. */
    @Test fun redactor_name_with_spaces() {
        val out = Redactor.redact("FileNotFoundException: Missing file for primary:Filme/Mein Urlaub 2024.mp4")
        assertFalse(out, out.contains("Urlaub"))
        assertFalse(out, out.contains("Mein"))
    }

    /** Befund: unterstützte Medienendungen, die nicht geschwärzt werden. */
    @Test fun redactor_all_media_extensions() {
        for (ext in listOf("wmv", "flv", "ts", "mts", "m2ts", "mpg", "mpeg", "ogv", "heif", "avif", "md", "json")) {
            val out = Redactor.redact("Fehler bei geheim.$ext")
            assertFalse("$ext: $out", out.contains("geheim"))
        }
    }

    @Test fun redactor_basic() {
        val out = Redactor.redact("content://com.android.externalstorage.documents/tree/1234-ABCD%3AFilme x.mp4")
        assertFalse(out, out.contains("Filme"))
    }

    // --- DOCX --------------------------------------------------------------------------

    /** Befund: Zeichenreferenzen außerhalb der BMP (Emoji) werden falsch dekodiert. */
    @Test fun docx_emoji_entity() {
        val text = DocxReader.extractText("<w:p><w:t>a&#128512;b</w:t></w:p>")
        assertEquals("a😀b", text)
    }

    /** Befund: riesige Zeichenreferenz wirft NumberFormatException (Import bricht unklar ab). */
    @Test fun docx_huge_entity_does_not_throw() {
        DocxReader.extractText("<w:p><w:t>&#99999999999;</w:t></w:p>")
    }

    @Test fun docx_rejects_non_docx() {
        try {
            DocxReader.read(ByteArray(10))
            throw AssertionError("muss abgelehnt werden")
        } catch (e: Exception) {
            assertTrue(e.javaClass.simpleName, e is de.hr.mediathek.data.story.DocxRejected || e is java.util.zip.ZipException)
        }
    }
}
