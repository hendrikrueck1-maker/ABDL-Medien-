package de.hr.mediathek.data.story.organize

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Exportiert Geschichten als ZIP mit Markdown-Dateien (im selben Format, das der Import wieder einliest). */
@Singleton
class StoryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: StoryDao,
) {
    /** [folderUuid] = null: alle Geschichten; sonst der Ordner samt Unterordnern. Gibt die Zahl der Dateien zurück. */
    suspend fun export(target: Uri, folderUuid: String?, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val folders = dao.observeFolders().first()
        val items = dao.observeFolderItems().first()
        val wanted: Set<String>? = folderUuid?.let { root ->
            val ids = HashSet<String>()
            fun walk(f: String) {
                items.filter { it.folderUuid == f }.forEach { ids += it.storyUuid }
                folders.filter { it.parentUuid == f }.forEach { walk(it.uuid) }
            }
            walk(root)
            ids
        }
        val briefs = dao.briefs().filter { wanted == null || it.uuid in wanted }
        val seriesNames = HashMap<String, String>()
        val used = HashSet<String>()
        val out = context.contentResolver.openOutputStream(target) ?: throw java.io.IOException("Zieldatei nicht beschreibbar")
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            briefs.forEachIndexed { i, b ->
                val s = dao.get(b.uuid) ?: return@forEachIndexed
                val text = dao.getText(b.uuid).orEmpty()
                val series = s.seriesUuid?.let { id -> seriesNames.getOrPut(id) { dao.seriesName(id) ?: "Serie" } }
                val tags = dao.tagNames(b.uuid)
                val dir = if (folderUuid != null) "" else categoryDir(s.category) + "/" + (series?.let { safe(it) + "/" } ?: "")
                val prefix = s.seriesOrder?.let { "%02d - ".format(it) } ?: ""
                var name = dir + prefix + safe(s.title)
                var n = 1
                while (!used.add(name.lowercase())) name = dir + prefix + safe(s.title) + " (${++n})"
                zip.putNextEntry(ZipEntry("$name.md"))
                zip.write(markdown(s.title, s.category, tags, s.author, s.createdAt, series, s.seriesOrder, s.sourceText, s.rating, text).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                count++
                if (i % 20 == 0) onProgress(i, briefs.size)
            }
        }
        onProgress(briefs.size, briefs.size)
        count
    }

    private fun categoryDir(c: StoryCategory) = when (c) {
        StoryCategory.MEN -> "Maenner"
        StoryCategory.WOMEN -> "Frauen"
        StoryCategory.MIXED -> "Gemischt"
    }

    private fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "Ohne Titel" }

    private fun markdown(
        title: String, category: StoryCategory, tags: List<String>, author: String?, createdAt: Long,
        series: String?, order: Int?, source: String?, rating: Int, body: String,
    ): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(createdAt))
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            append("---\n")
            append("title: ").append(q(title)).append('\n')
            append("category: ").append(when (category) { StoryCategory.MEN -> "men"; StoryCategory.WOMEN -> "women"; StoryCategory.MIXED -> "mixed" }).append('\n')
            if (tags.isNotEmpty()) append("tags: [").append(tags.joinToString(", ") { q(it) }).append("]\n")
            author?.takeIf { it.isNotBlank() }?.let { append("author: ").append(q(it)).append('\n') }
            append("createdAt: ").append(iso).append('\n')
            series?.let { append("series: ").append(q(it)).append('\n') }
            order?.let { append("seriesOrder: ").append(it).append('\n') }
            source?.takeIf { it.isNotBlank() }?.let { append("source: ").append(q(it)).append('\n') }
            if (rating > 0) append("rating: ").append(rating).append('\n')
            append("---\n\n")
            append(body.trim()).append('\n')
        }
    }
}
