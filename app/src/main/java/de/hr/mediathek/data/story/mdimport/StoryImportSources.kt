package de.hr.mediathek.data.story.mdimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.story.TextDecoder
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Baut aus Ordner, ZIP oder JSON eine [ImportSource]. Gelesen wird Datei für Datei beim Import. */
@Singleton
class StoryImportSources @Inject constructor(@ApplicationContext private val context: Context) {

    private class TooLarge : Exception()

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw TooLarge()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parseBytes(name: String, bytes: ByteArray): ParseResult {
        val text = TextDecoder.decode(bytes, TextDecoder.detect(bytes))
        return MarkdownStoryParser.parse(name, text)
    }

    private fun guarded(block: () -> ParseResult): ParseResult = try {
        block()
    } catch (e: TooLarge) {
        ParseResult.Fail("Datei ist größer als 5 MB")
    } catch (e: OutOfMemoryError) {
        ParseResult.Fail("Speicher reicht nicht für diese Datei")
    }

    private class Entry(val docId: String, val name: String, val path: String)

    /** Alle *.md unter dem Ordner (rekursiv). Gibt es darin content/stories, wird dieser Ordner genommen. */
    suspend fun fromFolder(tree: Uri): ImportSource = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val root = DocumentsContract.getTreeDocumentId(tree)
        var start = root
        var startPath = ""
        SafOps.children(resolver, tree, root).firstOrNull { it.isDir && it.name == "content" }?.let { content ->
            SafOps.children(resolver, tree, content.docId).firstOrNull { it.isDir && it.name == "stories" }?.let { stories ->
                start = stories.docId
                startPath = "content/stories"
            }
        }
        val found = ArrayList<Entry>()
        fun walk(docId: String, path: String, depth: Int) {
            for (child in SafOps.children(resolver, tree, docId)) {
                if (child.name.startsWith(".")) continue
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (child.isDir) {
                    if (depth < MAX_DEPTH) walk(child.docId, childPath, depth + 1)
                } else if (child.name.endsWith(".md", ignoreCase = true)) {
                    found += Entry(child.docId, child.name, childPath)
                }
            }
        }
        walk(start, startPath, 0)
        found.sortBy { it.path }
        ImportSource(
            label = "Ordner",
            total = found.size,
            items = found.asSequence().map { e ->
                ImportItem(e.path) {
                    guarded {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
                        val stream = resolver.openInputStream(uri) ?: return@guarded ParseResult.Fail("Datei nicht lesbar")
                        parseBytes(e.name, stream.use { readLimited(it, MAX_FILE_BYTES) })
                    }
                }
            },
        )
    }

    suspend fun fromZip(uri: Uri): ImportSource = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("story-import", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("ZIP nicht lesbar")
            val zip = ZipFile(temp)
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                .filter { e -> e.name.split('/').none { it.startsWith(".") || it == "__MACOSX" } }
                .sortedBy { it.name }
                .toList()
            if (entries.size > MAX_ZIP_ENTRIES) {
                zip.close()
                temp.delete()
                error("ZIP enthält mehr als $MAX_ZIP_ENTRIES Markdown-Dateien")
            }
            ImportSource(
                label = "ZIP",
                total = entries.size,
                items = entries.asSequence().map { e ->
                    ImportItem(e.name) {
                        guarded { parseBytes(e.name, zip.getInputStream(e).use { readLimited(it, MAX_FILE_BYTES) }) }
                    }
                },
                onClose = { runCatching { zip.close() }; temp.delete() },
            )
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    suspend fun fromJson(uri: Uri): ImportSource = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_JSON_BYTES) }
            ?: error("JSON nicht lesbar")
        val array = JsonStoryParser.parseArray(TextDecoder.decode(bytes, TextDecoder.detect(bytes)))
        if (array == null) {
            ImportSource("JSON", 1, sequenceOf(ImportItem("JSON") { ParseResult.Fail("Keine gültige JSON-Liste ([ … ])") }))
        } else {
            ImportSource(
                label = "JSON",
                total = array.length(),
                items = (0 until array.length()).asSequence().map { i ->
                    val element = array.opt(i)
                    val title = (element as? org.json.JSONObject)?.optString("title").orEmpty()
                    ImportItem("Eintrag ${i + 1}" + if (title.isNotBlank()) " ($title)" else "") {
                        JsonStoryParser.parseElement(i, element)
                    }
                },
            )
        }
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
        const val MAX_JSON_BYTES = 100L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 50_000
    }
}
