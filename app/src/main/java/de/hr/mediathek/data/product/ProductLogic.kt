package de.hr.mediathek.data.product

import de.hr.mediathek.data.db.CriterionValueType

/** Ein Kriterium der Vorlage; die Liste ist Daten, nicht Code-Struktur (wird beim ersten Start in die Datenbank geschrieben). */
data class CriterionDef(val key: String, val label: String, val group: String, val type: CriterionValueType)

object CriterionCatalog {
    const val VERSION = 1
    const val G_PERFORMANCE = "Leistung"
    const val G_STABILITY = "Stabilität"
    const val G_MATERIAL = "Material und Verschluss"
    const val G_FIT = "Passform und Tragegefühl"
    const val G_DATA = "Daten"

    /** Schlüssel der Datenfelder, die im Produkt statt als Kriterienwert liegen. */
    const val K_WEIGHT_EMPTY = "weight_empty"
    const val K_WEIGHT_FULL = "weight_full"
    const val K_SIZES = "sizes_available"
    const val K_PRICE = "price"
    const val K_UNIT_PRICE = "unit_price"
    const val K_PACK = "pack_content"
    const val K_WOULD_BUY = "would_buy"
    const val K_OVERALL = "overall"
    val DATA_KEYS = setOf(K_WEIGHT_EMPTY, K_WEIGHT_FULL, K_SIZES, K_PRICE, K_UNIT_PRICE, K_PACK, K_WOULD_BUY, K_OVERALL)

    private val N = CriterionValueType.NUMBER
    private val T = CriterionValueType.TEXT
    private val B = CriterionValueType.BOTH

    val defaults: List<CriterionDef> = listOf(
        CriterionDef("absorbency", "Saugfähigkeit", G_PERFORMANCE, B),
        CriterionDef("absorbency_claimed", "Saugfähigkeit angegeben", G_PERFORMANCE, T),
        CriterionDef("distribution", "Flüssigkeitsverteilung", G_PERFORMANCE, B),
        CriterionDef("speed", "Aufnahmegeschwindigkeit", G_PERFORMANCE, B),
        CriterionDef("leak_protection", "Auslaufschutz", G_PERFORMANCE, B),
        CriterionDef("dryness", "Trockenheitsgefühl", G_PERFORMANCE, B),
        CriterionDef("wetness_indicator", "Nässeindikator", G_PERFORMANCE, T),
        CriterionDef("odor_new", "Geruch (neu)", G_PERFORMANCE, B),
        CriterionDef("odor_used", "Geruch (benutzt)", G_PERFORMANCE, B),
        CriterionDef("core_stability", "Stabilität des Saugkissens", G_STABILITY, B),
        CriterionDef("front_friction", "Frontbereich bei viel Reibung", G_STABILITY, B),
        CriterionDef("outer_material", "Außenmaterial", G_MATERIAL, T),
        CriterionDef("noise_bare", "Geräuschpegel ohne Kleidung", G_MATERIAL, B),
        CriterionDef("noise_clothed", "Geräuschpegel mit Kleidung", G_MATERIAL, B),
        CriterionDef("tape_strength", "Klebekraft der Verschlüsse", G_MATERIAL, B),
        CriterionDef("waist_comfort", "Tragegefühl Bund", G_FIT, B),
        CriterionDef("fit_dry", "Passform (trocken/leicht nass)", G_FIT, B),
        CriterionDef("fit_wet", "Passform (nass)", G_FIT, B),
        CriterionDef("fit_back", "Passform (hinten)", G_FIT, B),
        CriterionDef("fit_front", "Passform (vorne)", G_FIT, B),
        CriterionDef("fit_active", "Passform körperlich aktiv", G_FIT, B),
        CriterionDef("fit_rest", "Passform in Ruhe", G_FIT, B),
        CriterionDef("fit_night", "Passform und Stabilität bei Nacht", G_FIT, B),
        CriterionDef("elasticity", "Elastizität", G_FIT, B),
        CriterionDef("leg_cuffs", "Beinabschlüsse", G_FIT, B),
        CriterionDef("discretion", "Diskretion", G_FIT, B),
        CriterionDef(K_WEIGHT_EMPTY, "Gewicht leer (g)", G_DATA, T),
        CriterionDef(K_WEIGHT_FULL, "Gewicht voll (g)", G_DATA, T),
        CriterionDef(K_SIZES, "Anzahl der Größen", G_DATA, T),
        CriterionDef(K_PRICE, "Preis", G_DATA, T),
        CriterionDef(K_UNIT_PRICE, "Preis pro Stück", G_DATA, T),
        CriterionDef("value_for_money", "Preis-Leistung", G_DATA, B),
        CriterionDef(K_PACK, "Inhalt pro Packung", G_DATA, T),
        CriterionDef(K_WOULD_BUY, "Würde ich wieder kaufen?", G_DATA, T),
        CriterionDef(K_OVERALL, "Gesamtbewertung", G_DATA, B),
    )
}

/** Sterne: Durchschnitt der Zahlenwerte geteilt durch 2, gerundet, mindestens 1. */
object Stars {
    fun compute(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val avg = values.average() / 2.0
        return Math.floor(avg + 0.5).toInt().coerceIn(1, 5)
    }
}

data class PriceResult(
    val min: Double?,
    val max: Double?,
    /** "ca.", "ab" oder mehrdeutige Schreibweise (z. B. "1.500"): in der Vorschau markieren. */
    val uncertain: Boolean,
    /** Aus einem Packungspreis errechnet. */
    val fromPack: Boolean,
) {
    val mean: Double? get() = if (min != null && max != null) (min + max) / 2.0 else min
}

object PriceParser {
    private val NUMBER = Regex("\\d+(?:[.,]\\d+)*")
    private val PACK = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:€|eur|euro)?\\s*/\\s*(\\d+)\\s*(?:stk|stück|st|x|pcs)?\\b")
    private val UNCERTAIN_WORDS = Regex("\\b(ca|circa|etwa|ungefähr|ab)\\b\\.?|~")
    private val RANGE_SEP = Regex("(?<=\\d|€|r|o)\\s*(?:-|–|—|bis)\\s*(?=\\d|€)")

    fun parse(raw: String?, packSize: Int? = null): PriceResult {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return PriceResult(null, null, false, false)
        var uncertain = UNCERTAIN_WORDS.containsMatchIn(text)

        // Packungspreis "15 € / 10 Stk"
        PACK.find(text)?.let { m ->
            val price = number(m.groupValues[1])
            val count = m.groupValues[2].toIntOrNull()
            if (price != null && count != null && count > 0) {
                return PriceResult(round(price.first / count), round(price.first / count), uncertain || price.second, true)
            }
        }

        val parts = RANGE_SEP.split(text).filter { NUMBER.containsMatchIn(it) }
        val values = ArrayList<Double>()
        for (part in parts) {
            val n = NUMBER.find(part)?.value ?: continue
            val parsed = number(n) ?: continue
            if (parsed.second) uncertain = true
            values += parsed.first
        }
        if (values.isEmpty()) return PriceResult(null, null, false, false)
        var lo = values.min()
        var hi = values.max()
        // Packungspreis über den Packungsinhalt des Produkts
        var fromPack = false
        if (packSize != null && packSize > 1 && (text.contains("packung") || text.contains("pack"))) {
            lo = round(lo / packSize); hi = round(hi / packSize); fromPack = true
        }
        return PriceResult(lo, hi, uncertain, fromPack)
    }

    /** Liefert Zahl und Flag "mehrdeutig". */
    internal fun number(token: String): Pair<Double, Boolean>? {
        val hasComma = token.contains(',')
        val hasDot = token.contains('.')
        return try {
            when {
                hasComma && hasDot -> {
                    val decimalIsComma = token.lastIndexOf(',') > token.lastIndexOf('.')
                    val cleaned = if (decimalIsComma) token.replace(".", "").replace(',', '.') else token.replace(",", "")
                    cleaned.toDouble() to false
                }
                hasComma -> {
                    if (token.count { it == ',' } > 1) token.replace(",", "").toDouble() to true
                    else token.replace(',', '.').toDouble() to false
                }
                hasDot -> {
                    val after = token.substringAfterLast('.')
                    if (token.count { it == '.' } > 1) token.replace(".", "").toDouble() to true
                    else token.toDouble() to (after.length == 3)
                }
                else -> token.toDouble() to false
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun round(v: Double): Double = Math.round(v * 100.0) / 100.0
}

data class BrandInfo(val name: String, val aliases: List<String> = emptyList())

object BrandNormalizer {
    val seed = listOf(
        BrandInfo("TENA", listOf("Tena")),
        BrandInfo("forma-care", listOf("formacare", "forma care")),
        BrandInfo("ID", listOf("iD", "i.D.")),
        BrandInfo("NorthShore", listOf("North Shore")),
        BrandInfo("Abena", listOf("Abri-Form", "Abri Flex")),
        BrandInfo("Seni"),
        BrandInfo("Molicare", listOf("MoliCare Premium")),
        BrandInfo("Lille"),
        BrandInfo("Crinklz"),
    )

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Erkennt die Marke an den ersten ein bis drei Wörtern des Titels. */
    fun detect(title: String, brands: List<BrandInfo>): BrandInfo? {
        val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (k in minOf(3, words.size) downTo 1) {
            val candidate = key(words.take(k).joinToString(" "))
            brands.firstOrNull { b -> key(b.name) == candidate || b.aliases.any { key(it) == candidate } }?.let { return it }
        }
        return null
    }

    private val SIZE = Regex("(?i)^(xxs|xs|s|m|l|xl|xxl|xxxl|2xl|3xl|\\d{2,3}(-\\d{2,3})?)$")

    fun sizeOf(title: String): String? = title.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { SIZE.matches(it) }?.uppercase()
}
