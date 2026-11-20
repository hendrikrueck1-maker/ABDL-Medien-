package de.hr.mediathek.data.compress

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobScheduler
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.CompressionDao
import de.hr.mediathek.data.db.GraceStats
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TEST_LENGTH_MS = 20_000L

data class LevelOption(val level: CompressionLevel, val targetBps: Long, val estimatedBytes: Long)

/** Ergebnis des 20-Sekunden-Testlaufs. */
data class TestResult(
    val sourceUri: String,
    val startMs: Long,
    val lengthMs: Long,
    val resultPath: String,
    val resultBytes: Long,
    val sourceBytes: Long,
    val estimatedTotalBytes: Long,
)

data class CompressionPlan(
    val mediaUuid: String,
    val durationMs: Long,
    val sourceBytes: Long,
    val sourceVideoBps: Long,
    val blockers: List<Blocker>,
    val alreadySmall: Boolean,
    val options: List<LevelOption>,
    val lowSpace: Boolean,
)

/** Vorab-Analyse für die Oberfläche, Einreihen von Jobs und Verwalten der Originale in der Schonfrist. */
@Singleton
class CompressionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
    private val dao: CompressionDao,
    private val probe: MediaProbe,
    private val scheduler: JobScheduler,
    private val engine: CompressionEngine,
    private val errorLogger: ErrorLogger,
    private val settings: CompressionSettings,
) {
    fun observeGrace(): Flow<GraceStats> = dao.observeGrace()
    fun observeBlocked(): Flow<List<String>> = dao.observeBlocked()

    suspend fun analyze(mediaUuid: String): CompressionPlan? = withContext(Dispatchers.IO) {
        val media = mediaDao.getByUuid(mediaUuid) ?: return@withContext null
        if (media.status != MediaStatus.ONLINE) return@withContext null
        val docId = media.documentIdCache ?: return@withContext null
        val source = sourceDao.getByUuid(media.sourceUuid) ?: return@withContext null
        val tree = Uri.parse(source.treeUri)
        val facts = probe.probe(DocumentsContract.buildDocumentUriUsingTree(tree, docId), media.size)
            ?: return@withContext null
        val duration = facts.durationMs?.takeIf { it > 0 } ?: return@withContext null
        val videoBps = CompressionPolicy.videoBitrate(CompressionPolicy.totalBitrate(media.size, duration))
        val options = CompressionLevel.entries.map {
            val target = CompressionPolicy.targetVideoBitrate(videoBps, it)
            LevelOption(it, target, CompressionPolicy.estimatedSize(target, duration))
        }
        val worst = options.maxOf { it.estimatedBytes }
        val dir = File(context.filesDir, "compress").apply { mkdirs() }
        val sdFree = source.volumeUuid?.let { File("/storage/$it") }?.takeIf { it.exists() }?.usableSpace
        CompressionPlan(
            mediaUuid = mediaUuid,
            durationMs = duration,
            sourceBytes = media.size,
            sourceVideoBps = videoBps,
            blockers = CompressionPolicy.blockers(facts),
            alreadySmall = CompressionPolicy.isAlreadySmall(videoBps, facts.width, facts.height, facts.videoMime),
            options = options,
            lowSpace = dir.usableSpace < worst * 12 / 10 || (sdFree != null && sdFree < worst * 11 / 10),
        )
    }

    /** Reiht alle gewählten Videos ein (ohne Bestätigungen: Videos mit Verlust-Hinweis werden übersprungen). */
    suspend fun enqueueAll(mediaUuids: List<String>, level: CompressionLevel): Int =
        mediaUuids.count { scheduler.enqueueCompress(CompressionParams(it, level, confirmed = false)) }

    suspend fun move(jobUuid: String, up: Boolean) = scheduler.moveCompress(jobUuid, up)

    private val testFile get() = File(context.cacheDir, "compress_test/test.mp4")

    /** Kodiert etwa 20 Sekunden aus der Mitte mit den echten Einstellungen und schätzt daraus die Gesamtgröße. */
    suspend fun runTest(mediaUuid: String, level: CompressionLevel, onProgress: suspend (Float) -> Unit): TestResult? =
        withContext(Dispatchers.IO) {
            val media = mediaDao.getByUuid(mediaUuid) ?: return@withContext null
            if (media.status != MediaStatus.ONLINE) return@withContext null
            val docId = media.documentIdCache ?: return@withContext null
            val tree = sourceDao.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return@withContext null
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val facts = probe.probe(docUri, media.size) ?: return@withContext null
            val duration = facts.durationMs?.takeIf { it > 0 } ?: return@withContext null
            val videoBps = CompressionPolicy.videoBitrate(CompressionPolicy.totalBitrate(media.size, duration))
            val target = CompressionPolicy.targetVideoBitrate(videoBps, level)
            val length = minOf(TEST_LENGTH_MS, duration)
            val start = maxOf(0L, duration / 2 - length / 2)
            clearTest()
            testFile.parentFile?.mkdirs()
            try {
                engine.encode(docUri, testFile, target.toInt(), start, length, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = describeFailure(e)
                errorLogger.logNow(ErrorType.COMPRESSION, "Testlauf fehlgeschlagen: $reason")
                throw IllegalStateException(reason, e)
            }
            val bytes = testFile.length()
            if (bytes <= 0) return@withContext null
            TestResult(docUri.toString(), start, length, testFile.absolutePath, bytes, media.size, bytes * duration / length)
        }

    fun clearTest() {
        runCatching { testFile.parentFile?.listFiles()?.forEach { it.delete() } }
    }

    /** Reiht die Komprimierung eines Videos ein. false, wenn für dieses Video schon eine läuft. */
    suspend fun enqueue(mediaUuid: String, level: CompressionLevel, confirmed: Boolean): Boolean =
        scheduler.enqueueCompress(CompressionParams(mediaUuid, level, confirmed))

    /** Löscht alle Originale, deren Frist abgelaufen ist, außer die Originale sollen dauerhaft bleiben (Standard). */
    suspend fun purgeDue(): Int {
        if (settings.keepOriginals()) return 0
        return purge(dao.graceExpired(System.currentTimeMillis()))
    }

    /** Löscht das aufbewahrte Original eines einzelnen Videos. false, wenn es keins (mehr) gibt oder die Karte fehlt. */
    suspend fun deleteOriginalOf(mediaUuid: String): Boolean {
        val record = dao.originalFor(mediaUuid) ?: return false
        return purge(listOf(record)) == 1
    }

    /** "Alle Originale jetzt endgültig löschen": ohne Frist abzuwarten. */
    suspend fun purgeAllNow(): Int = purge(dao.inGrace())

    private suspend fun purge(records: List<de.hr.mediathek.data.db.CompressionRecordEntity>): Int =
        withContext(Dispatchers.IO) {
            var removed = 0
            for (r in records) {
                try {
                    val tree = r.sourceUuid?.let { sourceDao.getByUuid(it) }?.treeUri?.let { Uri.parse(it) } ?: continue
                    val altId = r.altPath ?: continue
                    // Ist die Karte nicht da, wird nichts als "gelöscht" angenommen.
                    if (!SafOps.rootReachable(context.contentResolver, tree)) continue
                    val deleted = SafOps.delete(context.contentResolver, tree, altId) ||
                        SafOps.stat(context.contentResolver, tree, altId) == null
                    if (deleted) {
                        dao.update(r.copy(phase = CompressionPhase.ORIGINAL_DELETED))
                        removed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorLogger.logNow(ErrorType.COMPRESSION, "Altes Original löschen fehlgeschlagen: ${e::class.java.simpleName}")
                }
            }
            removed
        }
}
