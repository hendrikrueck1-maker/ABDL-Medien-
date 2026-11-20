package de.hr.mediathek.data.compress

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
import de.hr.mediathek.data.db.CompressionDao
import de.hr.mediathek.data.db.CompressionRecordEntity
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.MediaAliasEntity
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.db.MediaType
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Komprimiert genau ein Video. Der Ablauf folgt dem Phasen-Journal:
 * ENCODING -> VERIFIED -> ORIGINAL_MOVED -> RENAMED -> DB_UPDATED, danach nach der Schonfrist ORIGINAL_DELETED.
 * Jede Phase wird gespeichert, bevor es weitergeht. Ab VERIFIED wird nie etwas verworfen, nur zu Ende geführt.
 */
@Singleton
class CompressionJobExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MediathekDatabase,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
    private val dao: CompressionDao,
    private val fingerprinter: Fingerprinter,
    private val probe: MediaProbe,
    private val engine: CompressionEngine,
    private val decoder: DecodeVerifier,
    private val errorLogger: ErrorLogger,
) {
    private val resolver get() = context.contentResolver
    private val workDir get() = File(context.filesDir, "compress")

    suspend fun execute(job: JobEntity, ctx: JobContext): JobOutcome = withContext(Dispatchers.IO) {
        val params = CompressionParams.parse(job.paramsJson)
        val existing = dao.unfinishedFor(params.mediaUuid)
        if (existing != null) {
            if (CompressionRecovery.restartFromScratch(existing.phase)) {
                withContext(NonCancellable) { discard(existing) }
            } else {
                val adopted = existing.copy(jobUuid = job.uuid)
                dao.update(adopted)
                return@withContext finish(adopted, ctx)
            }
        }
        fresh(job, ctx, params)
    }

    /**
     * Nach Absturz oder Neustart: angefangene Kodierungen (vor VERIFIED) werden verworfen,
     * alles ab VERIFIED wird zurückgegeben und muss zu Ende geführt werden.
     */
    suspend fun abandonInterrupted(): List<CompressionRecordEntity> = withContext(Dispatchers.IO) {
        val forward = ArrayList<CompressionRecordEntity>()
        for (r in dao.allUnfinished()) {
            if (CompressionRecovery.restartFromScratch(r.phase)) discard(r) else forward += r
        }
        forward
    }

    // --- Neuer Lauf --------------------------------------------------------------------------

    private suspend fun fresh(job: JobEntity, ctx: JobContext, p: CompressionParams): JobOutcome {
        val media = mediaDao.getByUuid(p.mediaUuid) ?: return JobOutcome.Failed("Video nicht mehr vorhanden")
        if (media.type != MediaType.VIDEO) return JobOutcome.Failed("Keine Videodatei")
        val docId = media.documentIdCache
        if (media.status != MediaStatus.ONLINE || docId == null) return JobOutcome.Failed("Datei ist gerade nicht verfügbar")
        val source = sourceDao.getByUuid(media.sourceUuid) ?: return JobOutcome.Failed("Quelle nicht gefunden")
        val tree = Uri.parse(source.treeUri)
        if (!SafOps.rootReachable(resolver, tree)) return JobOutcome.Failed("Speicherkarte nicht erreichbar")

        ctx.report("Analysiere Video", null, force = true)
        val srcUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val facts = probe.probe(srcUri, media.size) ?: return JobOutcome.Failed("Video nicht lesbar")
        val duration = facts.durationMs?.takeIf { it > 0 } ?: return JobOutcome.Failed("Videodauer unbekannt")

        val blockers = CompressionPolicy.blockers(facts)
        if (Blocker.NO_VIDEO in blockers) {
            mark(job, media.uuid, p.level, "NO_VIDEO")
            return JobOutcome.Failed("Nicht komprimierbar: keine Videospur")
        }
        if (blockers.isNotEmpty() && !p.confirmed) {
            mark(job, media.uuid, p.level, blockers.joinToString(","))
            return JobOutcome.Failed("Nicht komprimierbar: Es würden Inhalte verloren gehen")
        }
        val totalBps = CompressionPolicy.totalBitrate(media.size, duration)
        val videoBps = CompressionPolicy.videoBitrate(totalBps)
        if (!p.confirmed && CompressionPolicy.isAlreadySmall(videoBps, facts.width, facts.height, facts.videoMime)) {
            mark(job, media.uuid, p.level, "ALREADY_SMALL")
            return JobOutcome.Completed("Übersprungen: bereits stark komprimiert")
        }
        val target = CompressionPolicy.targetVideoBitrate(videoBps, p.level)
        val estimate = CompressionPolicy.estimatedSize(target, duration)

        workDir.mkdirs()
        if (workDir.usableSpace < estimate * 12 / 10) return JobOutcome.Failed("Zu wenig freier Speicher im Gerät")
        source.volumeUuid?.let { File("/storage/$it") }?.takeIf { it.exists() }?.let {
            // usableSpace = 0 heißt oft nur "nicht lesbar": dann nicht abbrechen, das Kopieren meldet echten Platzmangel.
            if (it.usableSpace in 1 until estimate * 11 / 10) return JobOutcome.Failed("Zu wenig Platz auf der Speicherkarte")
        }

        val parentRel = media.relativePath.substringBeforeLast('/', "")
        val parentId = SafOps.resolveDir(resolver, tree, parentRel) ?: return JobOutcome.Failed("Ordner nicht gefunden")
        val baseName = media.displayName.substringBeforeLast('.', media.displayName)
        val targetName = "$baseName.mp4"
        if (SafOps.children(resolver, tree, parentId).any { it.name == targetName && it.docId != docId }) {
            return JobOutcome.Failed("Im Ordner gibt es schon eine Datei mit dem Zielnamen")
        }

        val local = File(workDir, "${job.uuid}.mp4")
        var record = CompressionRecordEntity(
            jobUuid = job.uuid, mediaUuid = media.uuid, phase = CompressionPhase.ENCODING,
            sourceUuid = media.sourceUuid, relativePath = media.relativePath, origName = media.displayName,
            targetName = targetName, level = p.level.name, origDocId = docId, localTemp = local.absolutePath,
            oldFingerprint = media.fingerprint, origSize = media.size,
        )
        dao.clearMarks(media.uuid)
        record = record.copy(id = dao.insert(record))

        try {
            // Hardware-Encoder überschreiten die Zielrate manchmal deutlich. Wird das Ergebnis nicht klein genug,
            // gibt es einen zweiten Versuch mit niedrigerer Rate, bevor das Video als nicht komprimierbar gilt.
            var attemptTarget = target
            var failure: VerifyFailure? = null
            var mime = androidx.media3.common.MimeTypes.VIDEO_H265
            var attempt = 0
            while (attempt <= 1) {
                ctx.report(if (attempt == 0) "Komprimiere" else "Komprimiere (zweiter Versuch)", 0f, force = true)
                try {
                    engine.encode(srcUri, local, attemptTarget.toInt(), videoMime = mime) { fraction ->
                        ctx.checkpoint()
                        ctx.report(if (attempt == 0) "Komprimiere" else "Komprimiere (zweiter Versuch)", fraction * ENCODE_SHARE)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // H.265-Encoder fehlt oder bricht ab: einmal mit H.264 versuchen.
                    if (mime != androidx.media3.common.MimeTypes.VIDEO_H264) {
                        errorLogger.logNow(ErrorType.COMPRESSION, "H.265 fehlgeschlagen, versuche H.264: ${describeFailure(e)}")
                        mime = androidx.media3.common.MimeTypes.VIDEO_H264
                        continue
                    }
                    throw e
                }

                ctx.report("Prüfe Ergebnis", ENCODE_SHARE, force = true)
                val outFacts = probe.probe(Uri.fromFile(local), local.length())
                if (outFacts == null) return rejected(job, record, "UNREADABLE", "Ergebnis nicht lesbar")
                failure = CompressionPolicy.verify(facts, outFacts)
                val lower = attemptTarget * RETRY_FACTOR_PERCENT / 100
                if (failure == VerifyFailure.NOT_SMALLER && attempt == 0 && lower >= CompressionPolicy.MIN_TARGET_BPS) {
                    attemptTarget = lower
                    attempt++
                    continue
                }
                break
            }
            failure?.let {
                return rejected(job, record, "VERIFY_$it", "Ergebnis abgelehnt (${describe(it)}), Original unverändert")
            }
            val decodedOk = decoder.decodesCompletely(local, duration) { f ->
                ctx.checkpoint()
                ctx.report("Dekodiertest", ENCODE_SHARE + f * DECODE_SHARE)
            }
            if (!decodedOk) {
                return rejected(job, record, "VERIFY_${VerifyFailure.DECODE}", "Dekodiertest fehlgeschlagen, Original unverändert")
            }

            ctx.report("Kopiere auf Speicherkarte", ENCODE_SHARE + DECODE_SHARE, force = true)
            record = copyToTree(record, local, tree, parentId, ctx)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { discard(record) }
            throw e
        } catch (e: Exception) {
            val reason = failureReason(e)
            errorLogger.logNow(ErrorType.COMPRESSION, "Komprimierung fehlgeschlagen: $reason")
            withContext(NonCancellable) {
                discard(record)
                mark(job, media.uuid, p.level, "ENCODE_ERROR")
            }
            return JobOutcome.Failed("Komprimierung fehlgeschlagen ($reason)")
        }
        return finish(record, ctx)
    }

    private fun failureReason(e: Throwable): String = describeFailure(e)

    private suspend fun rejected(job: JobEntity, record: CompressionRecordEntity, note: String, message: String): JobOutcome {
        withContext(NonCancellable) {
            discard(record)
            mark(job, record.mediaUuid, runCatching { CompressionLevel.valueOf(record.level ?: "") }.getOrDefault(CompressionLevel.BALANCED), note)
        }
        return JobOutcome.Failed(message)
    }

    private fun describe(f: VerifyFailure): String = when (f) {
        VerifyFailure.NO_VIDEO -> "keine Videospur"
        VerifyFailure.DURATION -> "Dauer weicht ab"
        VerifyFailure.NOT_SMALLER -> "nicht klein genug"
        VerifyFailure.AUDIO_CHANGED -> "Ton verändert"
        VerifyFailure.DECODE -> "Dekodierung"
    }

    /** Schreibt das fertige Ergebnis als ".tmp" neben das Original, prüft es zurück und hält VERIFIED fest. */
    private suspend fun copyToTree(
        record: CompressionRecordEntity, local: File, tree: Uri, parentId: String, ctx: JobContext,
    ): CompressionRecordEntity {
        val tmpName = "${record.targetName}.tmp"
        SafOps.children(resolver, tree, parentId).filter { it.name == tmpName && !it.isDir }
            .forEach { SafOps.delete(resolver, tree, it.docId) }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val tmpUri = DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", tmpName)
            ?: error("Temp-Datei konnte nicht angelegt werden")
        val tmpId = DocumentsContract.getDocumentId(tmpUri)
        // Sofort festhalten, damit ein Rest nach Absturz aufgeräumt werden kann.
        var r = record.copy(tempPath = tmpId)
        dao.update(r)

        val total = local.length()
        var written = 0L
        resolver.openOutputStream(tmpUri, "w")?.use { out ->
            local.inputStream().buffered(COPY_BUFFER).use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    ctx.checkpoint()
                    ctx.report("Kopiere auf Speicherkarte", ENCODE_SHARE + DECODE_SHARE + COPY_SHARE * written / total)
                }
                out.flush()
            }
        } ?: error("Temp-Datei nicht beschreibbar")

        val stat = SafOps.stat(resolver, tree, tmpId)
        check(stat != null && stat.size == total) { "Größe der Kopie stimmt nicht" }
        val localFp = fingerprinter.compute(Uri.fromFile(local), total)
        val remoteFp = fingerprinter.compute(DocumentsContract.buildDocumentUriUsingTree(tree, tmpId), total)
        check(localFp == remoteFp) { "Fingerabdruck der Kopie stimmt nicht" }

        r = r.copy(phase = CompressionPhase.VERIFIED, newSize = total, newFingerprint = remoteFp)
        dao.update(r)
        local.delete()
        return r
    }

    // --- Zu Ende führen (ab VERIFIED, auch nach Neustart) ------------------------------------

    private suspend fun finish(start: CompressionRecordEntity, ctx: JobContext): JobOutcome {
        var r = start
        val sourceUuid = r.sourceUuid ?: return JobOutcome.Failed("Journal unvollständig")
        val source = sourceDao.getByUuid(sourceUuid) ?: return JobOutcome.Failed("Quelle nicht gefunden")
        val tree = Uri.parse(source.treeUri)
        if (!SafOps.rootReachable(resolver, tree)) return JobOutcome.Failed("Speicherkarte nicht erreichbar, wird später fortgesetzt")
        val parentRel = r.relativePath!!.substringBeforeLast('/', "")
        val parentId = SafOps.resolveDir(resolver, tree, parentRel)
            ?: return JobOutcome.Failed("Ordner nicht erreichbar, wird später fortgesetzt")
        val altId = SafOps.findOrCreateDir(resolver, tree, parentId, ALT_DIR, noMedia = true)
            ?: return JobOutcome.Failed("Ordner _alt konnte nicht angelegt werden")

        // 1) Original in den _alt-Ordner verschieben.
        if (CompressionRecovery.isBefore(r.phase, CompressionPhase.ORIGINAL_MOVED)) {
            ctx.report("Sichere Original", null, force = true)
            val inParent = SafOps.children(resolver, tree, parentId).any { it.docId == r.origDocId }
            val altChildren = SafOps.children(resolver, tree, altId)
            val altDocId: String? = if (inParent) {
                if (altChildren.any { it.name == r.origName }) {
                    return JobOutcome.Failed("Im Ordner _alt liegt schon eine gleichnamige Datei")
                }
                SafOps.move(resolver, tree, r.origDocId!!, parentId, altId)
            } else {
                altChildren.firstOrNull { it.name == r.origName }?.docId
            }
            if (altDocId == null) {
                // Das Original ist weder an seinem Platz noch im _alt-Ordner (z. B. inzwischen verschoben): abbrechen, Temp-Datei weg.
                withContext(NonCancellable) {
                    r.tempPath?.let { SafOps.delete(resolver, tree, it) }
                    dao.update(r.copy(phase = CompressionPhase.FAILED, note = "ORIGINAL_MISSING"))
                }
                return JobOutcome.Failed("Original nicht mehr am Platz, Komprimierung abgebrochen")
            }
            r = r.copy(phase = CompressionPhase.ORIGINAL_MOVED, altPath = altDocId)
            dao.update(r)
        }

        // 2) Temp-Datei auf den Zielnamen umbenennen und den tatsächlichen Namen prüfen.
        if (CompressionRecovery.isBefore(r.phase, CompressionPhase.RENAMED)) {
            ctx.report("Benenne um", null, force = true)
            val children = SafOps.children(resolver, tree, parentId)
            val newId: String = if (children.any { it.docId == r.tempPath }) {
                val renamed = DocumentsContract.renameDocument(
                    resolver, DocumentsContract.buildDocumentUriUsingTree(tree, r.tempPath!!), r.targetName!!,
                )
                renamed?.let { DocumentsContract.getDocumentId(it) } ?: r.tempPath!!
            } else {
                // Schon umbenannt (Absturz direkt nach dem Umbenennen).
                children.firstOrNull { it.name == r.targetName }?.docId
                    ?: return JobOutcome.Failed("Ergebnisdatei nicht gefunden, wird später erneut versucht")
            }
            val actual = SafOps.stat(resolver, tree, newId)
            if (actual == null || actual.name != r.targetName) {
                // Anderer Name (z. B. mit Zusatz): zurückrollen, das Original kommt an seinen Platz.
                withContext(NonCancellable) {
                    SafOps.delete(resolver, tree, newId)
                    r.altPath?.let { SafOps.move(resolver, tree, it, altId, parentId) }
                    dao.update(r.copy(phase = CompressionPhase.FAILED, note = "RENAME_MISMATCH"))
                }
                return JobOutcome.Failed("Umbenennen ergab einen anderen Namen, Original wiederhergestellt")
            }
            r = r.copy(phase = CompressionPhase.RENAMED, newDocId = newId)
            dao.update(r)
        }

        // 3) Datenbank umhängen: neuer Fingerabdruck, alter als Alias, Nutzerdaten bleiben.
        if (CompressionRecovery.isBefore(r.phase, CompressionPhase.DB_UPDATED)) {
            ctx.report("Aktualisiere Datenbank", null, force = true)
            val newName = r.targetName!!
            val newPath = if (parentRel.isEmpty()) newName else "$parentRel/$newName"
            val modified = SafOps.stat(resolver, tree, r.newDocId!!)?.modified
            val now = System.currentTimeMillis()
            db.withTransaction {
                mediaDao.applyCompression(r.mediaUuid, newPath, newName, r.newSize!!, r.newFingerprint!!, r.newDocId!!, modified, now)
                r.oldFingerprint?.let { mediaDao.insertAlias(MediaAliasEntity(mediaUuid = r.mediaUuid, fingerprint = it)) }
                r = r.copy(phase = CompressionPhase.DB_UPDATED, deleteAfter = now + CompressionPolicy.GRACE_MS)
                dao.update(r)
            }
        }

        val saved = (r.origSize ?: 0) - (r.newSize ?: 0)
        return JobOutcome.Completed("Komprimiert, ${saved / 1_000_000} MB gespart; Original bleibt im Ordner _alt")
    }

    // --- Aufräumen ---------------------------------------------------------------------------

    /** Verwirft einen Lauf vor VERIFIED: lokale und angelegte Temp-Datei weg, Journal-Eintrag löschen. */
    private suspend fun discard(r: CompressionRecordEntity) {
        r.localTemp?.let { runCatching { File(it).delete() } }
        val tempId = r.tempPath
        val sourceUuid = r.sourceUuid
        if (tempId != null && sourceUuid != null) {
            runCatching {
                val tree = sourceDao.getByUuid(sourceUuid)?.treeUri?.let { Uri.parse(it) }
                // Nur die eigene Temp-Datei, nie etwas anderes.
                if (tree != null && SafOps.rootReachable(resolver, tree)) {
                    val stat = SafOps.stat(resolver, tree, tempId)
                    if (stat != null && stat.name.endsWith(".tmp")) SafOps.delete(resolver, tree, tempId)
                }
            }
        }
        dao.delete(r.id)
    }

    private suspend fun mark(job: JobEntity, mediaUuid: String, level: CompressionLevel, note: String) {
        dao.clearMarks(mediaUuid)
        dao.insert(
            CompressionRecordEntity(
                jobUuid = job.uuid, mediaUuid = mediaUuid, phase = CompressionPhase.BLOCKED,
                level = level.name, note = note,
            ),
        )
    }

    companion object {
        const val ALT_DIR = "_alt"
        private const val COPY_BUFFER = 1 shl 20
        private const val RETRY_FACTOR_PERCENT = 65L
        private const val ENCODE_SHARE = 0.80f
        private const val DECODE_SHARE = 0.10f
        private const val COPY_SHARE = 0.10f
    }
}
