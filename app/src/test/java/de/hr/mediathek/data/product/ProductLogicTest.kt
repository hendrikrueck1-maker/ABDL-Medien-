package de.hr.mediathek.data.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLogicTest {
    @Test fun starsAverageHalfRoundedMinOne() {
        assertEquals(4, Stars.compute(listOf(8, 7, 9)))
        assertEquals(1, Stars.compute(listOf(1, 1)))
        assertEquals(5, Stars.compute(listOf(10, 10)))
        assertNull(Stars.compute(emptyList()))
    }

    @Test fun priceFormats() {
        for (s in listOf("1,50€", "1.50€", "1,50 EUR", "€1.50", "1,50 € ")) {
            val p = PriceParser.parse(s)
            assertEquals(s, 1.5, p.min!!, 0.001)
            assertFalse(s, p.uncertain)
        }
    }

    @Test fun priceRanges() {
        val a = PriceParser.parse("1-1,50€")
        assertEquals(1.0, a.min!!, 0.001); assertEquals(1.5, a.max!!, 0.001); assertEquals(1.25, a.mean!!, 0.001)
        val b = PriceParser.parse("1.00 - 1.50 €")
        assertEquals(1.0, b.min!!, 0.001); assertEquals(1.5, b.max!!, 0.001)
    }

    @Test fun priceUncertain() {
        assertTrue(PriceParser.parse("ca. 1,50 €").uncertain)
        assertTrue(PriceParser.parse("ab 1,20€").uncertain)
        assertTrue(PriceParser.parse("1.500").uncertain)
    }

    @Test fun packPriceBecomesUnitPrice() {
        val p = PriceParser.parse("15 € / 10 Stk")
        assertTrue(p.fromPack)
        assertEquals(1.5, p.min!!, 0.001)
    }

    @Test fun unreadablePriceStaysNull() {
        assertNull(PriceParser.parse("keine Ahnung").min)
        assertNull(PriceParser.parse(null).min)
    }

    @Test fun brandDetection() {
        val b = BrandNormalizer.seed
        assertEquals("TENA", BrandNormalizer.detect("Tena Pants Maxi S", b)?.name)
        assertEquals("forma-care", BrandNormalizer.detect("Forma Care Slip M", b)?.name)
        assertEquals("ID", BrandNormalizer.detect("iD Expert Slip L", b)?.name)
        assertNull(BrandNormalizer.detect("Idee Windel M", b))
        assertEquals("S", BrandNormalizer.sizeOf("Tena Pants Maxi S"))
    }

    @Test fun parserReadsTableWithMultilineNotes() {
        val text = """
            Testmarke Slip Maxi M
            Kriterium Bewertung (1-10) Anmerkung
            Saugfähigkeit 8 550ml
            Saugfähigkeit angegeben 2400 ml
            Auslaufschutz 9 hielt die ganze Nacht
            und auch am Morgen
            Nässeindikator ja
            Geruch (neu) 6 neutral
            Passform (trocken/leicht nass)
            7
            sitzt gut
            Würde ich wieder kaufen? ja gerne
        """.trimIndent()
        val r = ProductTextParser.parse(text)
        assertEquals("Testmarke Slip Maxi M", r.title)
        val byKey = r.criteria.associateBy { it.key }
        assertEquals(8, byKey["absorbency"]!!.number)
        assertEquals("550ml", byKey["absorbency"]!!.note)
        assertEquals("2400 ml", byKey["absorbency_claimed"]!!.text)
        assertEquals(9, byKey["leak_protection"]!!.number)
        assertEquals("hielt die ganze Nacht und auch am Morgen", byKey["leak_protection"]!!.note)
        assertEquals("ja", byKey["wetness_indicator"]!!.text)
        assertEquals(6, byKey["odor_new"]!!.number)
        assertEquals(7, byKey["fit_dry"]!!.number)
        assertEquals("sitzt gut", byKey["fit_dry"]!!.note)
        assertEquals("ja gerne", byKey["would_buy"]!!.text)
    }

    @Test fun parserKeepsUnknownCriteria() {
        val text = "Titel\nKriterium Bewertung Anmerkung\nAuslaufschutz 9\nZusatzprüfung Rand 5 unauffällig\nweiter"
        val r = ProductTextParser.parse(text)
        assertEquals(1, r.unassigned.size)
        assertEquals("Zusatzprüfung Rand", r.unassigned[0].label)
        assertEquals(5, r.unassigned[0].number)
        assertEquals("unauffällig weiter", r.unassigned[0].note)
    }

    @Test fun matchPrefixToleratesSpacing() {
        assertTrue(ProductTextParser.matchPrefix("Passform (trocken / leicht nass) 7", "Passform (trocken/leicht nass)") > 0)
        assertEquals(-1, ProductTextParser.matchPrefix("Saugfähigkeiten 3", "Saugfähigkeit"))
    }

    @Test fun csvEscapesAndHasBom() {
        val p = ExportProduct("A;B", "X", null, 4, true, "1,50", 1.5, true, "sagt \"gut\"", mapOf("absorbency" to Triple(8, null, "550ml")))
        val csv = ProductCsv.build(listOf(p), CriterionCatalog.defaults)
        assertTrue(csv.startsWith("﻿"))
        assertTrue(csv.contains("\"A;B\";X;;4;ja;1,50;1,50;ja;\"sagt \"\"gut\"\"\""))
        assertNotNull(csv.lines()[1])
    }
}
