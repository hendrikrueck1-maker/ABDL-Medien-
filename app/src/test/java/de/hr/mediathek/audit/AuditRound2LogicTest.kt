package de.hr.mediathek.audit

import de.hr.mediathek.data.images.HashedItem
import de.hr.mediathek.data.images.SimilarImageGroups
import de.hr.mediathek.data.pkg.PackageLogic
import de.hr.mediathek.data.product.ProductTextParser
import de.hr.mediathek.data.story.CleanOptions
import de.hr.mediathek.data.story.TextCleaner
import de.hr.mediathek.data.story.TextDecoder
import de.hr.mediathek.data.story.TextEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Audit Runde 2: PDF-Textparser, Ähnlichkeitsgruppen, Textimport, Paketpfade. Synthetische Daten. */
class AuditRound2LogicTest {

    private fun parse(vararg lines: String) = ProductTextParser.parse((listOf("Testmarke Slip M", "Kriterium Bewertung (1-10) Anmerkung") + lines).joinToString("\n"))

    // --- Produkt-PDF-Text --------------------------------------------------------------

    /** Befund: Anmerkungszeile, die mit Großbuchstaben beginnt und eine Zahl 1–10 enthält, wird zu einem neuen Kriterium. */
    @Test fun multiline_note_with_number_stays_note() {
        val r = parse("Auslaufschutz 8 hielt gut", "Nach 3 Stunden noch trocken", "Geruch (neu) 6")
        val leak = r.criteria.first { it.key == "leak_protection" }
        assertEquals("hielt gut Nach 3 Stunden noch trocken", leak.note)
        assertTrue(r.unassigned.isEmpty())
    }

    /** Befund: Dezimalwert wird still zu Text, die Zahl geht verloren (kein Hinweis in der Vorschau). */
    @Test fun decimal_value_is_not_silently_text() {
        val c = parse("Auslaufschutz 7,5").criteria.first { it.key == "leak_protection" }
        assertTrue("Wert 7,5: number=${c.number} text=${c.text}", c.number != null)
    }

    @Test fun out_of_range_values_are_not_accepted_as_numbers() {
        for (v in listOf("0", "11", "-1", "abc")) {
            val c = parse("Auslaufschutz $v").criteria.first { it.key == "leak_protection" }
            assertTrue("$v -> ${c.number}", c.number == null)
        }
    }

    @Test fun typo_in_label_goes_to_unassigned() {
        val r = parse("Auslaufschuz 7 gut", "Geruch (neu) 6")
        assertEquals(listOf("Auslaufschuz"), r.unassigned.map { it.label })
    }

    @Test fun empty_and_garbage_never_throw() {
        ProductTextParser.parse("")
        ProductTextParser.parse("\u000C\u000C\u000C")
        ProductTextParser.parse("x".repeat(200_000))
        ProductTextParser.parse((1..5000).joinToString("\n") { "Zeile $it 5" })
    }

    // --- Ähnliche Bilder ---------------------------------------------------------------

    /** Hinweis: Gruppen bilden Ketten. A~B (6), B~C (6), A–C (12) landen bei Schwelle 6 in einer Gruppe. */
    @Test fun similar_groups_chain_transitively() {
        val a = 0L
        val b = (1L shl 6) - 1            // 6 Bits Abstand zu a
        val c = ((1L shl 12) - 1)          // 6 Bits Abstand zu b, 12 zu a
        val groups = SimilarImageGroups.build(listOf(HashedItem("a", a), HashedItem("b", b), HashedItem("c", c)), 6)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test fun similar_groups_threshold_zero_and_max() {
        val items = listOf(HashedItem("a", 5L), HashedItem("b", 5L), HashedItem("c", 7L))
        assertEquals(listOf(listOf("a", "b")), SimilarImageGroups.build(items, 0))
        assertEquals(1, SimilarImageGroups.build(items, 99).size)
    }

    @Test fun similar_groups_100k_random_is_fast() {
        val rnd = Random(1)
        val items = (0 until 100_000).map { HashedItem("i$it", rnd.nextLong()) }
        val t = System.nanoTime()
        SimilarImageGroups.build(items, 10)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("100.000 Hashes, Schwelle 10: $ms ms", ms < 20_000)
    }

    /** Viele fast gleiche Hashes (z. B. dunkle Bilder): eine riesige Kette statt sinnvoller Gruppen. */
    @Test fun similar_groups_low_entropy_images() {
        val rnd = Random(2)
        // 2.000 Hashes, die sich jeweils nur in den unteren 8 Bits unterscheiden (dunkle, gleichförmige Bilder)
        val items = (0 until 2000).map { HashedItem("d$it", rnd.nextLong() and 0xFF) }
        val groups = SimilarImageGroups.build(items, 6)
        val biggest = groups.maxOf { it.size }
        assertTrue("größte Gruppe: $biggest von 2000", biggest < 500)
    }

    // --- Textimport --------------------------------------------------------------------

    @Test fun encodings_detected() {
        val s = "Größe, Übung, Straße – €"
        assertEquals(TextEncoding.UTF8, TextDecoder.detect(s.toByteArray(Charsets.UTF_8)))
        assertEquals(TextEncoding.WINDOWS_1252, TextDecoder.detect(s.toByteArray(charset("windows-1252"))))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
        assertEquals(TextEncoding.UTF16, TextDecoder.detect(utf16))
        assertEquals(s, TextDecoder.decode(utf16, TextEncoding.UTF16))
        assertEquals(s, TextDecoder.decode(s.toByteArray(charset("windows-1252")), TextEncoding.WINDOWS_1252))
    }

    /** UTF-8 mit BOM: das BOM darf nicht im Text bleiben. */
    @Test fun utf8_bom_removed() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hallo".toByteArray()
        assertEquals("Hallo", TextDecoder.decode(bytes, TextDecoder.detect(bytes)))
    }

    /** Befund: einzelne Zahlenzeilen mitten im Text (Kapitelnummern, Jahreszahlen) werden als Seitenzahl entfernt. */
    @Test fun numeric_line_inside_text_is_kept() {
        val page = "Kapitel\n\n1999\n\nDamals war alles anders.\n" + "Text ".repeat(50)
        val raw = listOf(page, "Weiter geht es.\n" + "Text ".repeat(50), "Ende.\n" + "Text ".repeat(50)).joinToString("\u000C")
        val r = TextCleaner.clean(raw, CleanOptions())
        assertTrue(r.text, r.text.contains("1999"))
    }

    @Test fun huge_and_weird_texts_do_not_throw() {
        TextCleaner.clean("", CleanOptions())
        TextCleaner.clean("\n".repeat(100_000), CleanOptions())
        TextCleaner.clean("a".repeat(2_000_000), CleanOptions())
        TextCleaner.clean("‮abc\u0000​😀", CleanOptions())
        val big = (1..200_000).joinToString("\n\n") { "Absatz $it" }
        assertNotNull(TextCleaner.paragraphs(big))
    }

    // --- Paketpfade --------------------------------------------------------------------

    @Test fun package_paths() {
        assertTrue(PackageLogic.isSafePath("videos/a.mp4"))
        for (bad in listOf("../a", "videos/../../x", "/abs", "a\\b", "", "videos//a")) assertFalse(bad, PackageLogic.isSafePath(bad))
    }
}
