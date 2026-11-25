package de.hr.mediathek.data.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.core.media.Fingerprinter
import de.hr.mediathek.core.media.MediaTypes
import de.hr.mediathek.data.db.DateSource
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.MediaAliasEntity
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.MediaType
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.ScanRow
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private data class ScanEntry(
    val docId: String,
    val relativePath: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val lastModified: Long,
    val type: MediaType,
)

private sealed interface Work {
    val entry: ScanEntry

    data class Changed(override val entry: ScanEntry, val row: ScanRow) : Work
    data class New(override val entry: ScanEntry) : Work
}

private class ScanStats {
    var created = 0
    var changed = 0
    var moved = 0
    var unchanged = 0
    var ambiguous = 0
    var unreadable = 0
    var skippedRecent = 0
}

/**
 * Liest eine Medienquelle inkrementell ein.
 *
 * Ablauf: (1) komplett auflisten, (2) über den Pfad abgleichen (unveränderte Dateien werden nicht gelesen),
 * (3) nur für neue/geänderte Dateien den Fingerabdruck lesen und verschobene Dateien ihrem Eintrag zuordnen,
 * (4) fehlende Dateien zählen, mit Schutzregeln gegen Massen-Verwaisung.
 *
 * Ein Scan ist wiederholbar: nach Abbruch oder Absturz beginnt er von vorn, bereits eingelesene Dateien
 * werden dabei über Pfad, Größe und Zeit schnell als unverändert erkannt.
 */
@Singleton
class ScanJobExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MediathekDatabase,
    private val sourceDao: SourceDao,
    private val mediaDao: MediaFileDao,
    private val fingerprinter: Fingerprinter,
    private val errorLogger: ErrorLogger,
    private val compressionDao: de.hr.mediathek.data.db.CompressionDao,
) {
    suspend fun execute(job: JobEntity, ctx: JobContext): JobOutcome {
        val sourceUuid = JSONObject(job.paramsJson).getString("sourceUuid")
        // Nie gleichzeitig mit dem Abschluss einer Komprimierung derselben Quelle (siehe SourceLocks).
        return de.hr.mediathek.core.jobs.SourceLocks.withSource(sourceUuid) { executeLocked(job, sourceUuid, ctx) }
    }

    private suspend fun executeLocked(job: JobEntity, sourceUuid: String, ctx: JobContext): JobOutcome {
        val source = sourceDao.getByUuid(sourceUuid) ?: return JobOutcome.Failed("Quelle nicht gefunden")
        val tree = Uri.parse(source.treeUri)
        val scanId = job.uuid

        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return JobOutcome.Failed("Quelle ungültig")
        }
        if (!isRootReadable(tree, rootId)) {
            // Schutzregel: eine nicht lesbare Quelle (Karte entfernt) darf nie zu Verwaisung führen.
            return JobOutcome.Failed("Quelle nicht erreichbar (Karte eingelegt?)")
        }

        ctx.report("Ordner werden gelesen …", null, force = true)
        val entries = try {
            listEntries(tree, rootId, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SCAN, "Auflisten fehlgeschlagen: $e")
            return JobOutcome.Failed("Auflisten fehlgeschlagen, es wurde nichts verändert")
        }

        val stats = ScanStats()
        val now = System.currentTimeMillis()

        // --- Abgleich über den Pfad ---
        ctx.report("Abgleich mit der Datenbank …", null, force = true)
        val rows = mediaDao.scanRows(source.uuid)
        val byPath = rows.associateBy { it.relativePath }
        val busy = ScanMatching.protectedPaths(
            compressionDao.allUnfinished().filter { it.sourceUuid == source.uuid }.map { it.relativePath to it.targetName },
        )
        val seenUuids = HashSet<String>()
        val seenIds = ArrayList<Long>()
        val work = ArrayList<Work>()
        for (entry in entries) {
            val row = byPath[entry.relativePath]
            val recentlyModified = ScanMatching.isRecent(now, entry.lastModified, RECENT_MS)
            if (entry.relativePath in busy) {
                // Wird gerade von einer Komprimierung bearbeitet: nichts ändern, nichts neu anlegen.
                if (row != null) { seenUuids += row.uuid; seenIds += row.id }
                stats.skippedRecent++
                continue
            }
            if (row != null) {
                seenUuids += row.uuid
                when {
                    // Datei wird gerade noch geschrieben (z. B. kopiert): erst beim nächsten Scan prüfen.
                    recentlyModified -> {
                        seenIds += row.id
                        stats.skippedRecent++
                    }
                    ScanMatching.isUnchanged(row.size, row.lastModified, entry.size, entry.lastModified) -> {
                        seenIds += row.id
                        stats.unchanged++
                    }
                    else -> work += Work.Changed(entry, row)
                }
            } else if (recentlyModified) {
                stats.skippedRecent++
            } else {
                work += Work.New(entry)
            }
        }
        // Einträge in Bearbeitung durch die Komprimierung (Original gerade im _alt-Ordner) gelten als gesehen.
        for (row in rows) if (row.relativePath in busy && seenUuids.add(row.uuid)) seenIds += row.id
        db.withTransaction {
            seenIds.chunked(SQL_CHUNK).forEach { mediaDao.markSeen(it, scanId, now) }
        }
        ctx.checkpoint()

        // --- Fingerabdrücke für neue und geänderte Dateien, Zuordnung verschobener Dateien ---
        val claimed = HashSet<String>()
        val semaphore = Semaphore(PARALLEL_READS)
        var done = 0
        for (chunk in work.chunked(CHUNK_SIZE)) {
            ctx.checkpoint()
            val fingerprints = coroutineScope {
                chunk.map { item ->
                    async(Dispatchers.IO) { semaphore.withPermit { fingerprintOrNull(tree, item.entry) } }
                }.awaitAll()
            }
            db.withTransaction {
                val inserts = ArrayList<MediaFileEntity>()
                chunk.forEachIndexed { index, item ->
                    val fingerprint = fingerprints[index]
                    if (fingerprint == null) {
                        stats.unreadable++
                        if (item is Work.Changed) mediaDao.markSeen(listOf(item.row.id), scanId, now)
                    } else {
                        when (item) {
                            is Work.Changed -> applyChanged(item, fingerprint, scanId, now, stats)
                            is Work.New -> applyNew(item.entry, fingerprint, source, scanId, now, seenUuids, claimed, inserts, stats)
                        }
                    }
                }
                if (inserts.isNotEmpty()) mediaDao.insertAll(inserts)
            }
            done += chunk.size
            ctx.report("Dateien prüfen: $done von ${work.size}", done.toFloat() / work.size)
        }

        // --- Fehlende Dateien mit Schutzregeln ---
        ctx.checkpoint()
        val totalOnline = mediaDao.countOnline(source.uuid)
        val missing = mediaDao.countMissingOnline(source.uuid, scanId)
        if (ScanMatching.isSuspiciousMissing(totalOnline, missing, entries.size)) {
            sourceDao.markMounted(source.uuid, now)
            return JobOutcome.NeedsConfirmation(
                JSONObject()
                    .put("sourceUuid", source.uuid)
                    .put("missing", missing)
                    .put("total", totalOnline)
                    .toString(),
            )
        }
        val marked = mediaDao.markMissing(source.uuid, scanId, now)
        sourceDao.markMounted(source.uuid, now)

        return JobOutcome.Completed(buildSummary(stats, marked))
    }

    /** Nach Bestätigung durch den Nutzer: fehlende Dateien dieses Scans zählen bzw. offline setzen. */
    suspend fun applyMissing(sourceUuid: String, scanId: String): Int =
        mediaDao.markMissing(sourceUuid, scanId, System.currentTimeMillis())

    private suspend fun applyChanged(
        item: Work.Changed,
        fingerprint: String,
        scanId: String,
        now: Long,
        stats: ScanStats,
    ) {
        val entry = item.entry
        val old = item.row.fingerprint
        if (old != null && old != fingerprint) {
            mediaDao.insertAlias(MediaAliasEntity(mediaUuid = item.row.uuid, fingerprint = old))
        }
        mediaDao.applyChange(
            id = item.row.id,
            size = entry.size,
            lastModified = entry.lastModified,
            fingerprint = fingerprint,
            docId = entry.docId,
            name = entry.name,
            mime = entry.mime,
            scan = scanId,
            now = now,
        )
        stats.changed++
    }

    private suspend fun applyNew(
        entry: ScanEntry,
        fingerprint: String,
        source: SourceEntity,
        scanId: String,
        now: Long,
        seenUuids: MutableSet<String>,
        claimed: MutableSet<String>,
        inserts: MutableList<MediaFileEntity>,
        stats: ScanStats,
    ) {
        val found = (mediaDao.findByFingerprint(fingerprint, entry.type) + mediaDao.findByAlias(fingerprint, entry.type))
            .distinctBy { it.uuid }
        val resolution = ScanMatching.resolve(
            candidates = found.map { ScanMatching.Candidate(it.uuid, it.sourceUuid, it.status) },
            seen = seenUuids,
            claimed = claimed,
            currentSourceUuid = source.uuid,
        )
        when (resolution) {
            is ScanMatching.Resolution.Claim -> {
                val candidate = found.first { it.uuid == resolution.candidate.uuid }
                val oldFingerprint = candidate.fingerprint
                if (oldFingerprint != null && oldFingerprint != fingerprint) {
                    mediaDao.insertAlias(MediaAliasEntity(mediaUuid = candidate.uuid, fingerprint = oldFingerprint))
                }
                mediaDao.claim(
                    uuid = candidate.uuid,
                    sourceUuid = source.uuid,
                    path = entry.relativePath,
                    docId = entry.docId,
                    name = entry.name,
                    size = entry.size,
                    lastModified = entry.lastModified,
                    mime = entry.mime,
                    fingerprint = fingerprint,
                    scan = scanId,
                    now = now,
                )
                claimed += candidate.uuid
                stats.moved++
            }
            is ScanMatching.Resolution.Ambiguous -> {
                stats.ambiguous++
                inserts += newEntity(entry, fingerprint, source, scanId, seenUuids)
                stats.created++
            }
            ScanMatching.Resolution.CreateNew -> {
                inserts += newEntity(entry, fingerprint, source, scanId, seenUuids)
                stats.created++
            }
        }
    }

    private fun newEntity(
        entry: ScanEntry,
        fingerprint: String,
        source: SourceEntity,
        scanId: String,
        seenUuids: MutableSet<String>,
    ): MediaFileEntity {
        val modified = entry.lastModified.takeIf { it > 0 }
        val entity = MediaFileEntity(
            sourceUuid = source.uuid,
            type = entry.type,
            relativePath = entry.relativePath,
            documentIdCache = entry.docId,
            displayName = entry.name,
            size = entry.size,
            fingerprint = fingerprint,
            mimeType = entry.mime,
            // Datum zunächst aus der Dateizeit; EXIF/Container-Datum ersetzt es später beim Auslesen der Metadaten.
            dateDetermined = modified,
            dateSource = if (modified != null) DateSource.LASTMODIFIED else null,
            lastModified = modified,
            lastSeenScanUuid = scanId,
        )
        seenUuids += entity.uuid
        return entity
    }

    private suspend fun fingerprintOrNull(tree: Uri, entry: ScanEntry): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId)
        return try {
            fingerprinter.compute(docUri, entry.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nur die Fehlerart, nie ein Dateiname; gleiche Fehler werden im Protokoll zusammengefasst.
            errorLogger.logNow(ErrorType.SCAN, "Fingerabdruck nicht lesbar: ${e::class.java.simpleName}")
            null
        }
    }

    private fun isRootReadable(tree: Uri, rootId: String): Boolean = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    /** Listet den ganzen Baum. Jeder nicht lesbare Ordner bricht den Scan ab (kein unvollständiges Listing). */
    private suspend fun listEntries(tree: Uri, rootId: String, ctx: JobContext): List<ScanEntry> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<ScanEntry>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to "")
        var directories = 0
        while (stack.isNotEmpty()) {
            ctx.checkpoint()
            val (dirId, relativeDir) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            val cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: error("Ordner nicht lesbar")
            cursor.use {
                while (it.moveToNext()) {
                    val docId = it.getString(0)
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2)
                    val path = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!MediaTypes.isIgnoredDir(name)) stack.addLast(docId to path)
                    } else {
                        if (MediaTypes.isIgnoredFile(name)) continue
                        val type = MediaTypes.detect(mime, name) ?: continue
                        result += ScanEntry(docId, path, name, mime, it.getLong(3), it.getLong(4), type)
                    }
                }
            }
            directories++
            if (directories % 20 == 0) ctx.report("Gefunden: ${result.size} Dateien", null)
        }
        return result
    }

    private fun buildSummary(stats: ScanStats, missing: Int): String = buildString {
        append("Fertig: ${stats.created} neu, ${stats.changed} geändert, ${stats.moved} verschoben, ")
        append("${stats.unchanged} unverändert")
        if (missing > 0) append(", $missing fehlend")
        if (stats.unreadable > 0) append(", ${stats.unreadable} nicht lesbar")
        if (stats.ambiguous > 0) append(", ${stats.ambiguous} mehrdeutig")
        if (stats.skippedRecent > 0) append(", ${stats.skippedRecent} noch in Bearbeitung")
    }

    private companion object {
        /** Dateien, die jünger sind, werden gerade noch geschrieben und beim nächsten Scan geprüft. */
        const val RECENT_MS = 10_000L
        const val CHUNK_SIZE = 64
        const val PARALLEL_READS = 4
        const val SQL_CHUNK = 500
    }
}
