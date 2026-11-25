package de.hr.mediathek.data.story.mdimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.story.TextDecoder
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Dateien eines Ordners oder ZIPs: Pfade auflisten und einzelne Dateien lesen. */
interface FileContainer : AutoCloseable {
    val paths: List<String>
    fun read(path: String, limit: Long): ByteArray
}

class TooLarge : Exception()

fun readLimited(input: InputStream, limit: Long): ByteArray {
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

/**
 * Erkennt in einem Container (Ordner oder ZIP) alles Importierbare und baut daraus eine Quelle:
 * - *.md (Markdown mit Frontmatter; README.md wird ignoriert),
 * - Katalog der Geschichten-PWA: …/index.json mit …/shard_N.json,
 * - eigene Geschichten der PWA: user-stories/(Dateien .json),
 * - geschichten-backup.json (Sicherung der PWA).
 * Reihenfolge: Katalog, eigene Geschichten, Markdown, zuletzt die Sicherung (damit ihre Bewertungen auf schon importierte
 * Geschichten treffen).
 */
object ContainerSource {
    const val MAX_FILE_BYTES = 5L * 1024 * 1024
    const val MAX_BACKUP_BYTES = 150L * 1024 * 1024

    private fun guarded(block: () -> ParseResult): ParseResult = try {
        block()
    } catch (e: TooLarge) {
        ParseResult.Fail("Datei ist größer als 5 MB")
    } catch (e: OutOfMemoryError) {
        ParseResult.Fail("Speicher reicht nicht für diese Datei")
    }

    private fun text(bytes: ByteArray): String = TextDecoder.decode(bytes, TextDecoder.detect(bytes))

    fun build(label: String, c: FileContainer, now: Long = System.currentTimeMillis(), onClose: () -> Unit = {}): ImportSource {
        val paths = c.paths
        val pathSet = paths.toHashSet()
        val items = ArrayList<Sequence<ImportItem>>()
        var total = 0
        val notes = ArrayList<ImportIssue>()
        fun add(list: List<ImportItem>) { items += list.asSequence(); total += list.size }

        // 1. Katalog
        for (index in paths.filter { it.substringAfterLast('/') == "index.json" }) {
            val dir = index.substringBeforeLast('/', "")
            fun p(n: String) = if (dir.isEmpty()) n else "$dir/$n"
            if (p("shard_0.json") !in pathSet) continue
            try {
                var cachedNo = -1
                var cached: Map<String, String>? = null
                val shard = { no: Int ->
                    if (no != cachedNo) {
                        cached = if (p("shard_$no.json") in pathSet) {
                            PwaFormat.parseShard(text(c.read(p("shard_$no.json"), MAX_FILE_BYTES)))
                        } else null
                        cachedNo = no
                    }
                    cached
                }
                add(PwaFormat.catalogItems(text(c.read(index, MAX_FILE_BYTES)), shard, now))
            } catch (e: Exception) {
                add(listOf(ImportItem(index) { ParseResult.Fail("Katalog nicht lesbar: ${e.message?.take(100)}") }))
            }
        }
        // 2. eigene Geschichten (GitHub-Sync der PWA)
        add(
            paths.filter { it.endsWith(".json", true) && it.split('/').contains("user-stories") }.sorted().map { path ->
                ImportItem(path) {
                    guarded {
                        val o = try { JSONObject(text(c.read(path, MAX_FILE_BYTES))) } catch (e: org.json.JSONException) {
                            return@guarded ParseResult.Fail("Kein gültiges JSON")
                        }
                        PwaFormat.customStory(path, o, now)
                    }
                }
            },
        )
        // 3. Markdown
        add(
            paths.filter { it.endsWith(".md", true) && !it.substringAfterLast('/').equals("README.md", true) }.sorted().map { path ->
                ImportItem(path) { guarded { MarkdownStoryParser.parse(path, text(c.read(path, MAX_FILE_BYTES)), now) } }
            },
        )
        // 4. Sicherung
        for (path in paths.filter { it.substringAfterLast('/') == PwaFormat.BACKUP_NAME }) {
            try {
                val o = JSONObject(text(c.read(path, MAX_BACKUP_BYTES)))
                val (list, n) = PwaFormat.backupItems(o, now)
                add(list); notes += n
            } catch (e: Exception) {
                add(listOf(ImportItem(path) { ParseResult.Fail("Sicherung nicht lesbar: ${e.message?.take(100)}") }))
            }
        }
        return ImportSource(label, total, items.asSequence().flatten(), notes, onClose)
    }

    /** Eine einzelne JSON-Datei: Liste, PWA-Sicherung oder eine eigene Geschichte. */
    fun fromJsonText(json: String, now: Long = System.currentTimeMillis()): ImportSource {
        val clean = json.removePrefix("\uFEFF").trimStart()
        fun failing(reason: String) = ImportSource("JSON", 1, sequenceOf(ImportItem("JSON-Datei") { ParseResult.Fail(reason) }))
        return try {
            if (clean.startsWith("[")) {
                val array = JSONArray(clean)
                ImportSource(
                    "JSON", array.length(),
                    (0 until array.length()).asSequence().map { i ->
                        val element = array.opt(i)
                        val title = (element as? JSONObject)?.optString("title").orEmpty()
                        ImportItem("Eintrag ${i + 1}" + if (title.isNotBlank()) " ($title)" else "") {
                            JsonStoryParser.parseElement(i, element, now)
                        }
                    },
                )
            } else {
                val o = JSONObject(clean)
                when {
                    PwaFormat.isBackup(o) -> {
                        val (list, notes) = PwaFormat.backupItems(o, now)
                        ImportSource("Sicherung", list.size, list.asSequence(), notes)
                    }
                    o.has("title") && o.has("text") -> ImportSource("JSON", 1, sequenceOf(ImportItem("JSON-Datei") { PwaFormat.customStory("story", o, now) }))
                    else -> failing("Unbekanntes JSON-Format (erwartet: Liste, geschichten-backup.json oder eine Geschichte mit title und text). Gefundene Felder: ${o.keys().asSequence().take(10).joinToString(", ").ifEmpty { "keine" }}")
                }
            }
        } catch (e: org.json.JSONException) {
            failing("Kein gültiges JSON (${e.message?.take(80)})")
        }
    }
}

/** Baut aus Ordner, ZIP oder JSON eine [ImportSource]. Gelesen wird Datei für Datei beim Import. */
@Singleton
class StoryImportSources @Inject constructor(@ApplicationContext private val context: Context) {

    private class Entry(val docId: String, val path: String)

    private class SafContainer(
        val resolver: android.content.ContentResolver, val tree: Uri, val entries: Map<String, Entry>,
    ) : FileContainer {
        override val paths: List<String> = entries.keys.toList()
        override fun read(path: String, limit: Long): ByteArray {
            val e = entries[path] ?: throw java.io.FileNotFoundException(path)
            val stream = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId))
                ?: throw java.io.IOException("Datei nicht lesbar")
            return stream.use { readLimited(it, limit) }
        }
        override fun close() {}
    }

    private class ZipContainer(val zip: ZipFile, val temp: File, names: List<String>) : FileContainer {
        override val paths: List<String> = names
        override fun read(path: String, limit: Long): ByteArray {
            val e = zip.getEntry(path) ?: throw java.io.FileNotFoundException(path)
            return zip.getInputStream(e).use { readLimited(it, limit) }
        }
        override fun close() { runCatching { zip.close() }; temp.delete() }
    }

    private fun relevant(name: String) = name.endsWith(".md", true) || name.endsWith(".json", true)

    /** Durchsucht den gewählten Ordner (rekursiv) nach importierbaren Dateien. */
    suspend fun fromFolder(tree: Uri): ImportSource = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val found = LinkedHashMap<String, Entry>()
        fun walk(docId: String, path: String, depth: Int) {
            for (child in SafOps.children(resolver, tree, docId)) {
                if (child.name.startsWith(".") || child.name == "node_modules") continue
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (child.isDir) {
                    if (depth < MAX_DEPTH) walk(child.docId, childPath, depth + 1)
                } else if (relevant(child.name)) {
                    found[childPath] = Entry(child.docId, childPath)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        ContainerSource.build("Ordner", SafContainer(resolver, tree, found))
    }

    suspend fun fromZip(uri: Uri): ImportSource = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("story-import", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("ZIP nicht lesbar")
            val zip = ZipFile(temp)
            val names = zip.entries().asSequence()
                .filter { !it.isDirectory && relevant(it.name) }
                .map { it.name }
                .filter { n -> n.split('/').none { it.startsWith(".") || it == "__MACOSX" || it == "node_modules" } }
                .toList()
            if (names.size > MAX_ZIP_ENTRIES) {
                zip.close(); temp.delete()
                error("ZIP enthält mehr als $MAX_ZIP_ENTRIES Dateien")
            }
            val container = ZipContainer(zip, temp, names)
            ContainerSource.build("ZIP", container, onClose = { container.close() })
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    suspend fun fromJson(uri: Uri): ImportSource = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, ContainerSource.MAX_BACKUP_BYTES) }
            ?: error("JSON nicht lesbar")
        ContainerSource.fromJsonText(TextDecoder.decode(bytes, TextDecoder.detect(bytes)))
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_ZIP_ENTRIES = 50_000
    }
}
