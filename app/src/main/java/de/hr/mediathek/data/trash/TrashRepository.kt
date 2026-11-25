package de.hr.mediathek.data.trash

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.EntityType
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.TrashDao
import de.hr.mediathek.data.db.TrashItemEntity
import de.hr.mediathek.data.db.TrashRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

class TrashResult(val done: Int, val failed: Int)

/**
 * Papierkorb: Dateien wandern in einen ".trash"-Ordner im selben Verzeichnis und bleiben 30 Tage.
 * Es wird nie rekursiv gelöscht, und alles Endgültige betrifft nur Dateien, die hier im Papierkorb liegen.
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trashDao: TrashDao,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
    private val errorLogger: ErrorLogger,
    private val db: de.hr.mediathek.data.db.MediathekDatabase,
) {
    fun observe(): Flow<List<TrashRow>> = trashDao.observeMedia()

    suspend fun trash(mediaUuids: List<String>): TrashResult = withContext(Dispatchers.IO) {
        var done = 0
        var failed = 0
        for (uuid in mediaUuids) {
            if (trashOne(uuid)) done++ else failed++
        }
        TrashResult(done, failed)
    }

    private suspend fun trashOne(uuid: String): Boolean {
        try {
            val media = mediaDao.getByUuid(uuid) ?: return false
            if (media.status != MediaStatus.ONLINE) return false
            val docId = media.documentIdCache ?: return false
            val tree = sourceDao.getByUuid(media.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return false
            val resolver = context.contentResolver
            val parentId = SafOps.resolveDir(resolver, tree, media.relativePath.substringBeforeLast('/', "")) ?: return false
            val trashDir = SafOps.findOrCreateDir(resolver, tree, parentId, TRASH_DIR, noMedia = true) ?: return false
            val newId = SafOps.move(resolver, tree, docId, parentId, trashDir) ?: return false
            val now = System.currentTimeMillis()
            // Eintrag und Markierung gemeinsam: entweder beides oder nichts.
            db.withTransaction {
                trashDao.insert(
                    TrashItemEntity(
                        entityType = EntityType.MEDIA,
                        entityUuid = uuid,
                        originalPath = media.relativePath,
                        trashPath = newId,
                        deletedAt = now,
                        expiresAt = now + RETENTION_MS,
                    ),
                )
                mediaDao.markTrashed(uuid, newId, now)
            }
            return true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SAF, "Papierkorb: Verschieben fehlgeschlagen (${e::class.java.simpleName})")
            return false
        }
    }

    suspend fun restore(item: TrashRow): Boolean = withContext(Dispatchers.IO) {
        try {
            val tree = sourceDao.getByUuid(item.sourceUuid)?.treeUri?.let { Uri.parse(it) } ?: return@withContext false
            val trashId = item.trashPath ?: return@withContext false
            val resolver = context.contentResolver
            val originalDir = item.originalPath?.substringBeforeLast('/', "") ?: ""
            val targetId = SafOps.resolveDir(resolver, tree, originalDir) ?: return@withContext false
            val trashDirId = SafOps.findOrCreateDir(resolver, tree, targetId, TRASH_DIR, noMedia = true)
                ?: return@withContext false
            val newId = SafOps.move(resolver, tree, trashId, trashDirId, targetId) ?: return@withContext false
            val now = System.currentTimeMillis()
            // Lag am Originalplatz schon eine gleichnamige Datei, vergibt Android einen neuen Namen („Name (1).mp4“).
            // Dann Pfad und Namen nachziehen, damit Bewertung und Favorit bei dieser Datei bleiben.
            val actualName = SafOps.stat(resolver, tree, newId)?.name
            val originalName = item.originalPath?.substringAfterLast('/')
            db.withTransaction {
                mediaDao.markRestored(item.entityUuid, newId, now)
                if (actualName != null && originalName != null && actualName != originalName) {
                    val path = if (originalDir.isEmpty()) actualName else "$originalDir/$actualName"
                    mediaDao.setPathAndName(item.entityUuid, path, actualName, now)
                }
                trashDao.delete(item.uuid)
            }
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SAF, "Papierkorb: Wiederherstellen fehlgeschlagen (${e::class.java.simpleName})")
            false
        }
    }

    /** Löscht die Datei endgültig. Betrifft ausschließlich Dateien im Papierkorb-Ordner. */
    suspend fun deleteForever(item: TrashRow): Boolean = withContext(Dispatchers.IO) {
        try {
            val tree = sourceDao.getByUuid(item.sourceUuid)?.treeUri?.let { Uri.parse(it) }
            val trashId = item.trashPath
            // Ist die Karte nicht da, bleibt der Eintrag bestehen und es wird später erneut versucht.
            if (tree == null || trashId == null || !rootReachable(tree)) return@withContext false
            val gone = SafOps.delete(context.contentResolver, tree, trashId) || !existsInTrash(tree, trashId)
            if (!gone) return@withContext false
            mediaDao.tombstone(item.entityUuid, System.currentTimeMillis())
            trashDao.delete(item.uuid)
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SAF, "Papierkorb: Löschen fehlgeschlagen (${e::class.java.simpleName})")
            false
        }
    }

    suspend fun purgeExpired(): Int {
        var removed = 0
        for (item in trashDao.expired(System.currentTimeMillis())) {
            if (deleteForever(item)) removed++
        }
        return removed
    }

    /** Ohne erreichbare Wurzel (Karte entfernt) wird nie etwas als "gelöscht" angenommen. */
    private fun rootReachable(tree: Uri): Boolean = try {
        val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
        context.contentResolver.query(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, root),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    private fun existsInTrash(tree: Uri, docId: String): Boolean = try {
        context.contentResolver.query(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    companion object {
        const val TRASH_DIR = ".trash"
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
