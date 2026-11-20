package de.hr.mediathek.data.product

/** Eine Zeile für den Export mit allen Werten bereits als Text. */
class ExportProduct(
    val title: String,
    val brand: String?,
    val size: String?,
    val stars: Int?,
    val favorite: Boolean,
    val price: String?,
    val unitPrice: Double?,
    val wouldBuyAgain: Boolean?,
    val overallNote: String?,
    /** Schlüssel -> (Zahl, Text, Anmerkung) */
    val values: Map<String, Triple<Int?, String?, String?>>,
)

object ProductCsv {
    const val BOM = "﻿"

    fun field(v: String?): String {
        val s = v ?: return ""
        return if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun build(products: List<ExportProduct>, catalog: List<CriterionDef>): String {
        val criteria = catalog.filter { it.key !in CriterionCatalog.DATA_KEYS }
        val sb = StringBuilder(BOM)
        val header = mutableListOf("Titel", "Marke", "Größe", "Sterne", "Favorit", "Preis", "Stückpreis", "Wieder kaufen", "Gesamtbewertung")
        for (c in criteria) {
            header += c.label; header += c.label + " (Text)"; header += c.label + " (Anmerkung)"
        }
        sb.append(header.joinToString(";") { field(it) }).append("\r\n")
        for (p in products) {
            val row = mutableListOf(
                p.title, p.brand, p.size, p.stars?.toString(), if (p.favorite) "ja" else "nein",
                p.price, p.unitPrice?.let { "%.2f".format(java.util.Locale.GERMANY, it) },
                when (p.wouldBuyAgain) { true -> "ja"; false -> "nein"; null -> null }, p.overallNote,
            )
            for (c in criteria) {
                val v = p.values[c.key]
                row += v?.first?.toString(); row += v?.second; row += v?.third
            }
            sb.append(row.joinToString(";") { field(it) }).append("\r\n")
        }
        return sb.toString()
    }
}
