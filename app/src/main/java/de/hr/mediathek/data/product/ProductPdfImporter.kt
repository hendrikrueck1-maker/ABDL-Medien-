package de.hr.mediathek.data.product

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.PhotoKind
import de.hr.mediathek.data.story.DocxReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

class ExtractedImage(val page: Int, val index: Int, val file: File, var kind: PhotoKind?, var rowIndex: Int)

class RawProductFile(
    val name: String,
    val text: String?,
    val images: List<ExtractedImage>,
    val error: String?,
    val warnings: List<String>,
)

/** Liest Bewertungs-PDFs (und .docx als reinen Text). Bilder landen zunächst im Cache, erst nach der Vorschau im Arbeitsordner. */
@Singleton
class ProductPdfImporter @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Wie die Datei gelesen wird: normale Dateien direkt; Google-Docs aus Drive sind „virtuelle“ Dateien ohne eigenen Inhalt
     * und werden über den Drive-Anbieter als PDF (mit Bildern) oder sonst als Word-Datei abgerufen.
     */
    private class Source(val docx: Boolean, val open: () -> java.io.InputStream?)

    private fun sourceFor(uri: Uri, name: String): Source {
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val virtual = mime == GOOGLE_DOC || isVirtual(uri)
        if (virtual) {
            val types = runCatching { resolver.getStreamTypes(uri, "*/*") }.getOrNull().orEmpty()
            val pick = when {
                PDF in types -> PDF
                DOCX in types -> DOCX
                else -> error("Google-Dokument lässt sich nicht als PDF oder Word abrufen")
            }
            return Source(pick == DOCX) { resolver.openTypedAssetFileDescriptor(uri, pick, null)?.createInputStream() }
        }
        return Source(mime == DOCX || name.lowercase().endsWith(".docx")) { resolver.openInputStream(uri) }
    }

    private fun isVirtual(uri: Uri): Boolean = runCatching {
        if (!android.provider.DocumentsContract.isDocumentUri(context, uri)) return false
        context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and android.provider.DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
        } ?: false
    }.getOrDefault(false)

    suspend fun read(uri: Uri, name: String): RawProductFile = withContext(Dispatchers.IO) {
        try {
            val source = sourceFor(uri, name)
            if (source.docx) {
                val bytes = source.open()?.use { input ->
                    // Höchstens 20 MB lesen (wie bei Geschichten), sonst droht Speichermangel.
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOCX_BYTES) return@withContext RawProductFile(name, null, emptyList(), "Datei ist größer als 20 MB", emptyList())
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: error("nicht lesbar")
                RawProductFile(name, DocxReader.read(bytes), emptyList(), null, emptyList())
            } else {
                readPdf(source.open, name)
            }
        } catch (e: InvalidPasswordException) {
            RawProductFile(name, null, emptyList(), "PDF ist passwortgeschützt", emptyList())
        } catch (e: OutOfMemoryError) {
            RawProductFile(name, null, emptyList(), "Speicher reicht nicht für diese Datei", emptyList())
        } catch (e: Exception) {
            val why = if (e is IllegalStateException && e.message != null) ": ${e.message}" else ""
            RawProductFile(name, null, emptyList(), "Datei nicht lesbar$why", emptyList())
        }
    }

    private fun readPdf(open: () -> java.io.InputStream?, name: String): RawProductFile {
        PDFBoxResourceLoader.init(context)
        val stream = open() ?: error("nicht lesbar")
        val doc = stream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
        val dir = File(context.cacheDir, "product-import").apply { mkdirs() }
        val warnings = ArrayList<String>()
        doc.use { d ->
            val sb = StringBuilder()
            val images = ArrayList<ExtractedImage>()
            for (p in 1..d.numberOfPages) {
                val stripper = PDFTextStripper().apply { sortByPosition = true; startPage = p; endPage = p }
                if (p > 1) sb.append('\u000C')
                sb.append(stripper.getText(d))
                val resources = d.getPage(p - 1).resources ?: continue
                var index = 0
                for (xName in resources.xObjectNames) {
                    if (!resources.isImageXObject(xName)) continue
                    try {
                        val image = resources.getXObject(xName) as PDImageXObject
                        if (image.width.toLong() * image.height > MAX_PIXELS) {
                            warnings += "Seite $p: Bild zu groß, übersprungen"
                            continue
                        }
                        val bmp = image.image ?: continue
                        val file = File(dir, "${System.nanoTime()}-p$p-$index.jpg")
                        file.writeBytes(ProductPhotoStore.jpeg(bmp))
                        bmp.recycle()
                        images += ExtractedImage(p, index, file, null, 0)
                        index++
                    } catch (e: Exception) {
                        warnings += "Seite $p: Bild nicht unterstützt (${e::class.java.simpleName})"
                    } catch (e: OutOfMemoryError) {
                        warnings += "Seite $p: Speicher reicht für ein Bild nicht"
                    }
                }
            }
            assign(images)
            return RawProductFile(name, sb.toString(), images, null, warnings)
        }
    }

    companion object {
        private const val MAX_PIXELS = 40_000_000L
        private const val MAX_DOCX_BYTES = 20L * 1024 * 1024
        const val GOOGLE_DOC = "application/vnd.google-apps.document"
        const val PDF = "application/pdf"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /** Regel: Seite 3 und 4 = Trocken/Nass-Paare, Seite 5 = erstes Bild Verpackung, danach Produktfotos. */
        fun assign(images: List<ExtractedImage>) {
            val wear = images.filter { it.page == 3 || it.page == 4 }
            wear.forEachIndexed { i, img ->
                img.kind = if (i % 2 == 0) PhotoKind.WORN_DRY else PhotoKind.WORN_WET
                img.rowIndex = i / 2
            }
            images.filter { it.page == 5 }.forEachIndexed { i, img ->
                img.kind = if (i == 0) PhotoKind.PACKAGE else PhotoKind.DETAIL
            }
        }
    }
}
