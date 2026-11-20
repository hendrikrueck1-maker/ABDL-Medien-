package de.hr.mediathek.data.product

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProductRepository,
) {
    suspend fun writeCsv(uuids: List<String>, target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val products = repository.exportProducts(uuids)
            val csv = ProductCsv.build(products, repository.catalog().map { CriterionDef(it.key, it.label, it.groupName, it.valueType) })
            context.contentResolver.openOutputStream(target, "w")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("Ziel nicht beschreibbar")
            products.size
        }
    }

    suspend fun writePdf(uuids: List<String>, target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            PDFBoxResourceLoader.init(context)
            val products = repository.exportProducts(uuids)
            val catalog = repository.catalog().filter { it.key !in CriterionCatalog.DATA_KEYS }
            val doc = PDDocument()
            doc.use { d ->
                for (p in products) writeProduct(d, p, catalog.map { CriterionDef(it.key, it.label, it.groupName, it.valueType) })
                if (products.isEmpty()) d.addPage(PDPage(PDRectangle.A4))
                context.contentResolver.openOutputStream(target, "w")?.use { d.save(it) } ?: error("Ziel nicht beschreibbar")
            }
            products.size
        }
    }

    private class PageWriter(val doc: PDDocument) {
        private val font = PDType1Font.HELVETICA
        private val bold = PDType1Font.HELVETICA_BOLD
        private var page: PDPage? = null
        private var stream: PDPageContentStream? = null
        private var y = 0f

        private fun newPage() {
            stream?.close()
            val p = PDPage(PDRectangle.A4)
            doc.addPage(p)
            page = p
            stream = PDPageContentStream(doc, p)
            y = PDRectangle.A4.height - MARGIN
        }

        fun startProduct() { newPage() }

        fun line(text: String, size: Float = 10f, isBold: Boolean = false) {
            val f = if (isBold) bold else font
            val maxWidth = PDRectangle.A4.width - 2 * MARGIN
            for (part in wrap(sanitize(text), f, size, maxWidth)) {
                if (y < MARGIN + size) newPage()
                val s = stream!!
                s.beginText()
                s.setFont(f, size)
                s.newLineAtOffset(MARGIN, y)
                s.showText(part)
                s.endText()
                y -= size + 3f
            }
        }

        fun gap() { y -= 6f }
        fun close() { stream?.close() }

        private fun wrap(text: String, f: PDType1Font, size: Float, maxWidth: Float): List<String> {
            val out = ArrayList<String>()
            var cur = StringBuilder()
            for (word in text.split(' ')) {
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                if (f.getStringWidth(candidate) / 1000f * size > maxWidth && cur.isNotEmpty()) {
                    out += cur.toString(); cur = StringBuilder(word)
                } else {
                    cur = StringBuilder(candidate)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }

        companion object {
            const val MARGIN = 40f

            /** Standardschrift mit WinAnsi: alles, was sie nicht kennt, wird zu "?". */
            fun sanitize(s: String): String = buildString {
                for (ch in s) {
                    append(
                        when {
                            ch == '€' -> "EUR"
                            ch == '\n' || ch == '\r' || ch == '\t' -> ' '
                            ch.code in 32..126 || ch.code in 161..255 -> ch
                            else -> '?'
                        },
                    )
                }
            }
        }
    }

    private fun writeProduct(doc: PDDocument, p: ExportProduct, criteria: List<CriterionDef>) {
        val w = PageWriter(doc)
        w.startProduct()
        w.line(p.title.ifBlank { "Ohne Titel" }, 16f, true)
        w.line(
            listOfNotNull(
                p.brand?.let { "Marke: $it" }, p.size?.let { "Größe: $it" },
                p.stars?.let { "Bewertung: $it von 5 Sternen" }, if (p.favorite) "Favorit" else null,
            ).joinToString("   "),
        )
        p.price?.let { w.line("Preis: $it") }
        p.unitPrice?.let { w.line("Preis pro Stück: " + "%.2f".format(java.util.Locale.GERMANY, it) + " EUR") }
        when (p.wouldBuyAgain) { true -> w.line("Würde ich wieder kaufen: ja"); false -> w.line("Würde ich wieder kaufen: nein"); null -> {} }
        w.gap()
        var group = ""
        for (c in criteria) {
            val v = p.values[c.key] ?: continue
            if (v.first == null && v.second.isNullOrBlank() && v.third.isNullOrBlank()) continue
            if (c.group != group) { group = c.group; w.gap(); w.line(group, 12f, true) }
            val value = listOfNotNull(v.first?.let { "$it/10" }, v.second?.takeIf { it.isNotBlank() }, v.third?.takeIf { it.isNotBlank() }).joinToString(" - ")
            w.line("${c.label}: $value")
        }
        p.overallNote?.let { w.gap(); w.line("Gesamtbewertung", 12f, true); w.line(it) }
        w.close()
    }
}
