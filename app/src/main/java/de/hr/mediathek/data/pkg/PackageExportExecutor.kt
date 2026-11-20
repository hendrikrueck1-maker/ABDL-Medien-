package de.hr.mediathek.data.pkg

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.core.media.Fingerprinter
import de.hr.mediathek.data.backup.BackupWriter
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.MediaType
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.PackageDao
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.SourceEntity
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kopiert die gewählten Medien in einen Paketordner (z. B. auf einer SD-Karte) und schreibt am Ende die Metadaten
 * (paket.zip) und zuletzt manifest.json. Ohne manifest.json gilt das Paket als unvollständig.
 * Jede Datei wird als .part geschrieben und nach einer Unterbrechung dort fortgesetzt; fertige Dateien werden übersprungen.
 */
@Singleton
class PackageExportExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val packageDao: PackageDao,
    private val selection: PackageSelectionStore,
    private val writer: BackupWriter,
    private val fingerprinter: Fingerprinter,
    private val errorLogger: ErrorLogger,
) {
    private class Resolved(val tree: Uri, val reachable: Boolean)

    suspend fun execute(job: JobEntity, ctx: JobContext): JobOutcome = withContext(Dispatchers.IO) {
        val params = JSONObject(job.paramsJson)
        val tree = Uri.parse(params.getString("treeUri"))
        val folder = params.getString("folder")
        val resolver = context.contentResolver
        if (!SafOps.rootReachable(resolver, tree)) return@withContext JobOutcome.Failed("Zielkarte nicht erreichbar, bitte einlegen")

        val uuids = selection.all()
        if (uuids.isEmpty()) return@withContext JobOutcome.Failed("Keine Medien ausgewählt")
        val media = uuids.chunked(500).flatMap { packageDao.byUuids(it) }
            .sortedWith(compareBy({ it.type }, { it.displayName.lowercase() }))

        val root = DocumentsContract.getTreeDocumentId(tree)
        val pkgDir = SafOps.findOrCreateDir(resolver, tree, root, folder, true)
            ?: return@withContext JobOutcome.Failed("Paketordner nicht anlegbar")
        val videoDir = SafOps.findOrCreateDir(resolver, tree, pkgDir, PackageLogic.DIR_VIDEOS, false)
            ?: return@withContext JobOutcome.Failed("Unterordner nicht anlegbar")
        val imageDir = SafOps.findOrCreateDir(resolver, tree, pkgDir, PackageLogic.DIR_IMAGES, false)
            ?: return@withContext JobOutcome.Failed("Unterordner nicht anlegbar")

        // Ein früher fertiger Stand wird ungültig, sobald ein neuer Lauf beginnt.
        SafOps.children(resolver, tree, pkgDir).filter { !it.isDir && (it.name == PackageLogic.MANIFEST_NAME || it.name == PackageLogic.METADATA_NAME) }
            .forEach { SafOps.delete(resolver, tree, it.docId) }

        val existing = mapOf(
            MediaType.VIDEO to SafOps.children(resolver, tree, videoDir).filter { !it.isDir }.associateBy { it.name }.toMutableMap(),
            MediaType.IMAGE to SafOps.children(resolver, tree, imageDir).filter { !it.isDir }.associateBy { it.name }.toMutableMap(),
        )
        val dirs = mapOf(MediaType.VIDEO to videoDir, MediaType.IMAGE to imageDir)

        val sources = HashMap<String, Pair<SourceEntity?, Boolean>>()
        val totalBytes = media.sumOf { it.size }.coerceAtLeast(1L)
        var doneBytes = 0L
        var exported = 0
        var skipped = 0
        var broken = 0
        val files = ArrayList<ManifestFile>()
        val keepNames = HashMap<MediaType, MutableSet<String>>().apply {
            put(MediaType.VIDEO, HashSet()); put(MediaType.IMAGE, HashSet())
        }

        for (m in media) {
            ctx.checkpoint()
            val (source, reachable) = sources.getOrPut(m.sourceUuid) {
                val s = sourceDao.getByUuid(m.sourceUuid)
                s to (s != null && SafOps.rootReachable(resolver, Uri.parse(s.treeUri)))
            }
            if (source == null || !reachable) { skipped++; continue }
            val srcTree = Uri.parse(source.treeUri)
            val srcDocId = resolveSource(resolver, srcTree, m)
            val srcSize = srcDocId?.let { SafOps.stat(resolver, srcTree, it)?.size }
            if (srcDocId == null || srcSize == null) { skipped++; continue }

            val name = PackageLogic.fileName(m.uuid, m.displayName)
            val dir = dirs.getValue(m.type)
            val known = existing.getValue(m.type)
            keepNames.getValue(m.type) += name
            keepNames.getValue(m.type) += "$name.part"
            ctx.report("Export: ${exported + broken + skipped + 1} von ${media.size}", doneBytes.toFloat() / totalBytes)

            try {
                var docId = known[name]?.takeIf { SafOps.stat(resolver, tree, it.docId)?.size == srcSize }?.docId
                if (docId == null) {
                    known[name]?.let { SafOps.delete(resolver, tree, it.docId) }
                    val part = known.remove("$name.part")
                    docId = copyResumable(ctx, resolver, srcTree, srcDocId, tree, dir, name, srcSize, part, doneBytes, totalBytes)
                } else {
                    known.remove("$name.part")?.let { SafOps.delete(resolver, tree, it.docId) }
                }
                val fp = fingerprinter.compute(DocumentsContract.buildDocumentUriUsingTree(tree, docId), srcSize)
                if (m.fingerprint != null && fp != m.fingerprint) {
                    SafOps.delete(resolver, tree, docId)
                    errorLogger.logNow(ErrorType.PACKAGE, "Paket-Export: Prüfsumme einer Datei stimmt nicht")
                    broken++
                    continue
                }
                val folderName = if (m.type == MediaType.VIDEO) PackageLogic.DIR_VIDEOS else PackageLogic.DIR_IMAGES
                files += ManifestFile(m.uuid, m.type.name, "$folderName/$name", srcSize, m.fingerprint, fp, false)
                exported++
                doneBytes += srcSize
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLogger.logNow(ErrorType.PACKAGE, "Paket-Export: Datei fehlgeschlagen: $e")
                broken++
            }
        }
        ctx.checkpoint()
        if (files.isEmpty()) return@withContext JobOutcome.Failed("Keine Datei konnte exportiert werden")

        // Nicht mehr benötigte Dateien und liegengebliebene Teilstücke aufräumen.
        for (type in listOf(MediaType.VIDEO, MediaType.IMAGE)) {
            existing.getValue(type).values.filter { it.name !in keepNames.getValue(type) }
                .forEach { SafOps.delete(resolver, tree, it.docId) }
        }

        // Metadaten, dann das Manifest als Letztes.
        ctx.report("Daten werden geschrieben", null, force = true)
        val temp = File(context.cacheDir, "paket-work.zip")
        temp.delete()
        try {
            writer.write(temp, BuildConfig.VERSION_NAME, includeSelection = true)
            val pkgUri = DocumentsContract.buildDocumentUriUsingTree(tree, pkgDir)
            val zipUri = DocumentsContract.createDocument(resolver, pkgUri, "application/octet-stream", PackageLogic.METADATA_NAME)
                ?: return@withContext JobOutcome.Failed("Metadaten nicht schreibbar")
            temp.inputStream().use { input -> resolver.openOutputStream(zipUri, "w")?.use { input.copyTo(it) } ?: error("Metadaten nicht schreibbar") }
            val written = SafOps.stat(resolver, tree, DocumentsContract.getDocumentId(zipUri))?.size
            check(written == temp.length()) { "Metadaten unvollständig" }
            val manifest = PackageManifest(MediathekDatabase.VERSION, BuildConfig.VERSION_NAME, System.currentTimeMillis(), folder, files)
            val mUri = DocumentsContract.createDocument(resolver, pkgUri, "application/octet-stream", PackageLogic.MANIFEST_NAME)
                ?: return@withContext JobOutcome.Failed("Manifest nicht schreibbar")
            resolver.openOutputStream(mUri, "w")?.use { it.write(PackageLogic.encode(manifest).toByteArray(Charsets.UTF_8)) } ?: error("Manifest nicht schreibbar")
        } finally {
            temp.delete()
        }
        selection.clear()
        val gb = "%.1f".format(files.sumOf { it.size } / PackageLogic.GB.toDouble())
        JobOutcome.Completed("Paket fertig: ${files.size} Dateien ($gb GB)" + (if (broken + skipped > 0) ", ${broken + skipped} übersprungen" else ""))
    }

    private fun resolveSource(resolver: ContentResolver, srcTree: Uri, m: MediaFileEntity): String? {
        m.documentIdCache?.let { if (SafOps.stat(resolver, srcTree, it) != null) return it }
        val dir = m.relativePath.substringBeforeLast('/', "")
        val name = m.relativePath.substringAfterLast('/')
        val parent = SafOps.resolveDir(resolver, srcTree, dir) ?: return null
        return SafOps.children(resolver, srcTree, parent).firstOrNull { !it.isDir && it.name == name }?.docId
    }

    /** Kopiert Quelle → name.part (fortsetzend), benennt nach Größenprüfung um und gibt die Dokument-ID zurück. */
    private suspend fun copyResumable(
        ctx: JobContext, resolver: ContentResolver, srcTree: Uri, srcDocId: String,
        dstTree: Uri, dstDir: String, name: String, size: Long, part: SafOps.Child?,
        doneBefore: Long, totalBytes: Long,
    ): String {
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(dstTree, dstDir)
        var offset = 0L
        var partUri: Uri? = null
        if (part != null) {
            val partSize = SafOps.stat(resolver, dstTree, part.docId)?.size
            offset = PackageLogic.resumeOffset(partSize, size)
            if (offset > 0L) partUri = DocumentsContract.buildDocumentUriUsingTree(dstTree, part.docId) else SafOps.delete(resolver, dstTree, part.docId)
        }
        val target = partUri ?: DocumentsContract.createDocument(resolver, dirUri, "application/octet-stream", "$name.part")
            ?: error("Zieldatei nicht anlegbar")
        val src = DocumentsContract.buildDocumentUriUsingTree(srcTree, srcDocId)
        resolver.openInputStream(src)?.use { input ->
            var toSkip = offset
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) { if (input.read() < 0) break else toSkip-- } else toSkip -= n
            }
            resolver.openOutputStream(target, if (offset > 0L) "wa" else "w")?.use { out ->
                val buffer = ByteArray(256 * 1024)
                var written = offset
                var sinceCheck = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    sinceCheck += n
                    if (sinceCheck >= 8L * 1024 * 1024) {
                        sinceCheck = 0
                        ctx.report(null, (doneBefore + written).toFloat() / totalBytes)
                        ctx.checkpoint()
                    }
                }
            } ?: error("Ziel nicht beschreibbar")
        } ?: error("Quelle nicht lesbar")
        val partDocId = DocumentsContract.getDocumentId(target)
        val got = SafOps.stat(resolver, dstTree, partDocId)?.size
        if (got != size) {
            SafOps.delete(resolver, dstTree, partDocId)
            error("Größe stimmt nach dem Kopieren nicht")
        }
        val renamed = DocumentsContract.renameDocument(resolver, target, name) ?: error("Umbenennen fehlgeschlagen")
        return DocumentsContract.getDocumentId(renamed)
    }
}
