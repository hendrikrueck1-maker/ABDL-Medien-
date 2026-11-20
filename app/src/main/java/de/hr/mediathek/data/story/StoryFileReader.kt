package de.hr.mediathek.data.story

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class StoryFileKind { TXT, DOCX, PDF }

/** Rohdaten einer gelesenen Datei: Text-Bytes (zur wählbaren Kodierung) oder schon extrahierter Text. */
class RawStoryFile(
    val name: String,
    val kind: StoryFileKind,
    val bytes: ByteArray?,
    val rawText: String?,
    val error: String?,
)

@Singleton
class StoryFileReader @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun readFiles(uris: List<Uri>): List<RawStoryFile> = withContext(Dispatchers.IO) {
        uris.map { read(it, displayName(it)) }
    }

    /** Liest alle .txt/.docx/.pdf in einem Ordner (mit Unterordnern). */
    suspend fun readFolder(tree: Uri): List<RawStoryFile> = withContext(Dispatchers.IO) {
        val result = ArrayList<RawStoryFile>()
        val resolver = context.contentResolver
        fun walk(docId: String, depth: Int) {
            for (child in SafOps.children(resolver, tree, docId).sortedBy { it.name.lowercase() }) {
                if (child.isDir) {
                    if (depth < MAX_DEPTH) walk(child.docId, depth + 1)
                } else if (kindOf(child.name) != null) {
                    result += read(DocumentsContract.buildDocumentUriUsingTree(tree, child.docId), child.name)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        result
    }

    private fun read(uri: Uri, name: String): RawStoryFile {
        val kind = kindOf(name) ?: return RawStoryFile(name, StoryFileKind.TXT, null, null, "Dateityp nicht unterstützt")
        return try {
            when (kind) {
                StoryFileKind.TXT -> RawStoryFile(name, kind, readBytes(uri), null, null)
                StoryFileKind.DOCX -> {
                    val text = DocxReader.read(readBytes(uri))
                    RawStoryFile(name, kind, null, text, null)
                }
                StoryFileKind.PDF -> readPdf(uri, name)
            }
        } catch (e: DocxRejected) {
            RawStoryFile(name, kind, null, null, e.message)
        } catch (e: TooLarge) {
            RawStoryFile(name, kind, null, null, "Die Datei ist größer als 20 MB")
        } catch (e: OutOfMemoryError) {
            RawStoryFile(name, kind, null, null, "Speicher reicht nicht für diese Datei")
        } catch (e: Exception) {
            RawStoryFile(name, kind, null, null, "Datei nicht lesbar")
        }
    }

    private fun readPdf(uri: Uri, name: String): RawStoryFile {
        PDFBoxResourceLoader.init(context)
        val stream = context.contentResolver.openInputStream(uri) ?: return RawStoryFile(name, StoryFileKind.PDF, null, null, "Datei nicht lesbar")
        val doc = try {
            stream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
        } catch (e: InvalidPasswordException) {
            return RawStoryFile(name, StoryFileKind.PDF, null, null, "PDF ist passwortgeschützt")
        }
        doc.use { d ->
            val pages = d.numberOfPages
            val sb = StringBuilder()
            for (p in 1..pages) {
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = p
                    endPage = p
                }
                if (p > 1) sb.append('\u000C')
                sb.append(stripper.getText(d))
            }
            val text = sb.toString()
            if (pages == 0 || text.count { !it.isWhitespace() } / pages < MIN_CHARS_PER_PAGE) {
                return RawStoryFile(name, StoryFileKind.PDF, null, null, "Scan-PDF ohne Text, wird nicht unterstützt")
            }
            return RawStoryFile(name, StoryFileKind.PDF, null, text, null)
        }
    }

    private class TooLarge : Exception()

    private fun readBytes(uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw TooLarge()
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
        error("nicht lesbar")
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "Datei"
        }
        return uri.lastPathSegment ?: "Datei"
    }

    companion object {
        private const val MAX_BYTES = 20L * 1024 * 1024
        private const val MAX_DEPTH = 3
        private const val MIN_CHARS_PER_PAGE = 20

        fun kindOf(name: String): StoryFileKind? = when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> StoryFileKind.TXT
            "docx" -> StoryFileKind.DOCX
            "pdf" -> StoryFileKind.PDF
            else -> null
        }
    }
}
