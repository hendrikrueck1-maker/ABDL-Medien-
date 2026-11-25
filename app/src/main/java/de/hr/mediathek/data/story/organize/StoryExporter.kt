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
        val wanted = wantedStories(folderUuid)
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
                zip.write(
                    markdown(
                        s.title, s.category, tags, s.author, s.createdAt, series, s.seriesOrder, s.sourceText, s.rating, text,
                        s.publishedAt, s.sourceUrl, exportSlug(s.slug, s.uuid), s.status ?: "published", s.favorite, s.continueFlag,
                    ).toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
                count++
                if (i % 20 == 0) onProgress(i, briefs.size)
            }
        }
        onProgress(briefs.size, briefs.size)
        count
    }

    /** [folderUuid] = null: alle (null zurück); sonst die UUIDs der Geschichten im Ordner samt Unterordnern. */
    private suspend fun wantedStories(folderUuid: String?): Set<String>? {
        if (folderUuid == null) return null
        val folders = dao.observeFolders().first()
        val items = dao.observeFolderItems().first()
        val ids = HashSet<String>()
        fun walk(f: String) {
            items.filter { it.folderUuid == f }.forEach { ids += it.storyUuid }
            folders.filter { it.parentUuid == f }.forEach { walk(it.uuid) }
        }
        walk(folderUuid)
        return ids
    }

    /**
     * Export als eine JSON-Datei (Liste von Objekten mit title, author, category, tags, content, createdAt, publishedAt,
     * source, sourceUrl, series, seriesOrder, rating). Der JSON-Import der App liest sie wieder ein; die Datei lässt sich
     * auch in anderen Dokumentations- oder Inventar-Apps verwenden. Gibt die Zahl der Geschichten zurück.
     */
    suspend fun exportJson(target: Uri, folderUuid: String?, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val wanted = wantedStories(folderUuid)
        val briefs = dao.briefs().filter { wanted == null || it.uuid in wanted }
        val seriesNames = HashMap<String, String>()
        val out = context.contentResolver.openOutputStream(target) ?: throw java.io.IOException("Zieldatei nicht beschreibbar")
        var count = 0
        android.util.JsonWriter(java.io.OutputStreamWriter(out.buffered(), Charsets.UTF_8)).use { w ->
            w.setIndent("  ")
            w.beginArray()
            briefs.forEachIndexed { i, b ->
                val s = dao.get(b.uuid) ?: return@forEachIndexed
                val series = s.seriesUuid?.let { id -> seriesNames.getOrPut(id) { dao.seriesName(id) ?: "Serie" } }
                w.beginObject()
                w.name("title").value(s.title)
                s.author?.takeIf { it.isNotBlank() }?.let { w.name("author").value(it) }
                w.name("category").value(categoryKey(s.category))
                w.name("tags").beginArray().also { dao.tagNames(b.uuid).forEach { t -> w.value(t) } }.endArray()
                w.name("content").value(dao.getText(b.uuid).orEmpty())
                w.name("createdAt").value(isoUtc(s.createdAt))
                s.publishedAt?.let { w.name("publishedAt").value(isoUtc(it)) }
                s.sourceText?.takeIf { it.isNotBlank() }?.let { w.name("source").value(it) }
                s.sourceUrl?.takeIf { it.isNotBlank() }?.let { w.name("sourceUrl").value(it) }
                series?.let { w.name("series").value(it) }
                s.seriesOrder?.let { w.name("seriesOrder").value(it.toLong()) }
                if (s.rating > 0) w.name("rating").value(s.rating.toLong())
                // Für die Übertragung Handy → Tablet: Kennung, Status und Markierungen, damit nichts verloren geht
                // und keine fremde Geschichte mit gleichem Titel überschrieben wird.
                w.name("slug").value(exportSlug(s.slug, s.uuid))
                w.name("status").value(s.status ?: "published")
                w.name("favorite").value(s.favorite)
                w.name("continueFlag").value(s.continueFlag)
                w.name("updatedAt").value(isoUtc(s.updatedAt))
                w.endObject()
                count++
                if (i % 20 == 0) onProgress(i, briefs.size)
            }
            w.endArray()
        }
        onProgress(briefs.size, briefs.size)
        count
    }

    /** Kennung für den Export: vorhandener Slug, sonst eine aus der UUID (eigene Geschichten haben keinen Slug). */
    private fun exportSlug(slug: String?, uuid: String): String = slug?.takeIf { it.isNotBlank() } ?: "app-$uuid"

    /** Alle Quell-Links (eine Zeile je Link), z. B. zum Einfügen in die Link-Liste auf dem Tablet. Gibt die Zahl zurück. */
    suspend fun exportLinks(target: Uri, folderUuid: String?): Int = withContext(Dispatchers.IO) {
        val wanted = wantedStories(folderUuid)
        val links = LinkedHashSet<String>()
        for (b in dao.briefs()) {
            if (wanted != null && b.uuid !in wanted) continue
            dao.get(b.uuid)?.sourceUrl?.takeIf { it.startsWith("http") }?.let { links += it }
        }
        val out = context.contentResolver.openOutputStream(target, "wt") ?: throw java.io.IOException("Zieldatei nicht beschreibbar")
        out.bufferedWriter(Charsets.UTF_8).use { w -> links.forEach { w.write(it); w.write("\n") } }
        links.size
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun categoryKey(c: StoryCategory) = when (c) { StoryCategory.MEN -> "men"; StoryCategory.WOMEN -> "women"; StoryCategory.MIXED -> "mixed" }

    private fun categoryDir(c: StoryCategory) = when (c) {
        StoryCategory.MEN -> "Maenner"
        StoryCategory.WOMEN -> "Frauen"
        StoryCategory.MIXED -> "Gemischt"
    }

    private fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "Ohne Titel" }

    private fun markdown(
        title: String, category: StoryCategory, tags: List<String>, author: String?, createdAt: Long,
        series: String?, order: Int?, source: String?, rating: Int, body: String, publishedAt: Long? = null, sourceUrl: String? = null,
        slug: String? = null, status: String? = null, favorite: Boolean = false, continueFlag: Boolean = false,
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
            publishedAt?.let { append("publishedAt: ").append(isoUtc(it)).append('\n') }
            sourceUrl?.takeIf { it.isNotBlank() }?.let { append("sourceUrl: ").append(q(it)).append('\n') }
            series?.let { append("series: ").append(q(it)).append('\n') }
            order?.let { append("seriesOrder: ").append(it).append('\n') }
            source?.takeIf { it.isNotBlank() }?.let { append("source: ").append(q(it)).append('\n') }
            if (rating > 0) append("rating: ").append(rating).append('\n')
            slug?.let { append("slug: ").append(q(it)).append('\n') }
            status?.let { append("status: ").append(it).append('\n') }
            if (favorite) append("favorite: true\n")
            if (continueFlag) append("continueFlag: true\n")
            append("---\n\n")
            append(body.trim()).append('\n')
        }
    }
}
