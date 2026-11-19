package de.hr.mediathek.data.thumb

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaType
import de.hr.mediathek.data.db.PendingThumb
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.db.ThumbnailDao
import javax.inject.Inject
import javax.inject.Singleton

/** Erzeugt fehlende oder veraltete Vorschaubilder; läuft mit niedriger Priorität nach jedem Scan. */
@Singleton
class ThumbnailJobExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thumbDao: ThumbnailDao,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
    private val cache: ThumbnailCache,
    private val generator: ThumbnailGenerator,
    private val errorLogger: ErrorLogger,
) {
    suspend fun execute(ctx: JobContext): JobOutcome {
        ctx.report("Vorschaubilder werden aufgeräumt", null)
        cache.removeOrphans()
        cache.shrinkOffline()
        cache.enforceLimit()
        ctx.checkpoint()

        val treeBySource = HashMap<String, Uri?>()
        val skipped = HashSet<String>()
        var created = 0
        var defect = 0
        var stoppedFull = false

        val total = thumbDao.countPending().coerceAtLeast(1)
        try {
            while (true) {
                ctx.checkpoint()
                val batch = thumbDao.pending(BATCH + skipped.size).filter { it.uuid !in skipped }.take(BATCH)
                if (batch.isEmpty()) break
                for (item in batch) {
                    ctx.checkpoint()
                    if (!cache.hasRoom()) {
                        stoppedFull = true
                        break
                    }
                    when (processOne(item, treeBySource)) {
                        Result.CREATED -> created++
                        Result.DEFECT -> defect++
                        Result.SKIPPED -> skipped += item.uuid
                    }
                    val handled = created + defect + skipped.size
                    if (handled % PROGRESS_EVERY == 0) {
                        ctx.report("Vorschaubilder: $handled von $total", (handled.toFloat() / total).coerceAtMost(1f))
                        cache.flushTouches()
                    }
                }
                if (stoppedFull) break
            }
        } finally {
            cache.flushTouches()
        }

        val parts = ArrayList<String>()
        parts += "Vorschaubilder: $created erzeugt"
        if (defect > 0) parts += "$defect defekt"
        if (skipped.isNotEmpty()) parts += "${skipped.size} übersprungen"
        if (stoppedFull) parts += "Speicher für Vorschaubilder voll"
        return JobOutcome.Completed(parts.joinToString(", "))
    }

    private enum class Result { CREATED, DEFECT, SKIPPED }

    private suspend fun processOne(item: PendingThumb, treeBySource: MutableMap<String, Uri?>): Result {
        val tree = treeBySource.getOrPut(item.sourceUuid) {
            sourceDao.getByUuid(item.sourceUuid)?.treeUri?.let { Uri.parse(it) }
        } ?: return Result.SKIPPED
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, item.documentIdCache)

        val generated = generator.generate(docUri, item.type, ThumbnailPolicy.NORMAL_PX)
        if (generated == null) {
            // Nur als defekt markieren, wenn die Datei da und lesbar ist; fehlt sie, klärt der nächste Scan das.
            return if (exists(docUri)) {
                mediaDao.markDefect(item.uuid, System.currentTimeMillis())
                errorLogger.logNow(ErrorType.SCAN, "Vorschaubild nicht erzeugbar (${item.type.name.lowercase()})")
                Result.DEFECT
            } else {
                Result.SKIPPED
            }
        }
        try {
            cache.store(item.uuid, item.fingerprint, generated.bitmap, ThumbVariant.NORMAL)
        } finally {
            generated.bitmap.recycle()
        }
        if (item.type == MediaType.VIDEO) {
            mediaDao.setVideoMeta(item.uuid, generated.durationMs, generated.width, generated.height, System.currentTimeMillis())
        } else if (generated.width != null) {
            mediaDao.setVideoMeta(item.uuid, null, generated.width, generated.height, System.currentTimeMillis())
        }
        return Result.CREATED
    }

    private fun exists(docUri: Uri): Boolean = try {
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

    private companion object {
        const val BATCH = 100
        const val PROGRESS_EVERY = 10
    }
}
