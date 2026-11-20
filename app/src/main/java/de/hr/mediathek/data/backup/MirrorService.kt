package de.hr.mediathek.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobType
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.SourceEntity
import de.hr.mediathek.data.db.SourceRole
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class MirrorStatus(val source: SourceEntity?, val lastSuccessAt: Long?, val job: JobEntity?)

/** Verwaltet die Spiegelkarte (zweites Sicherungsziel, Quelle mit Rolle BACKUP). */
@Singleton
class MirrorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val settingDao: SettingDao,
    private val jobDao: JobDao,
    private val errorLogger: ErrorLogger,
) {
    fun observeStatus(): Flow<MirrorStatus> = combine(
        sourceDao.observeMirror(),
        settingDao.observe(KEY_LAST_SUCCESS),
        jobDao.observeLatest(JobType.MIRROR_COPY),
    ) { source, last, job -> MirrorStatus(source, last?.toLongOrNull(), job) }

    suspend fun lastSuccessAt(): Long? = settingDao.get(KEY_LAST_SUCCESS)?.toLongOrNull()

    suspend fun recordSuccess() {
        settingDao.set(SettingEntity(KEY_LAST_SUCCESS, System.currentTimeMillis().toString()))
    }

    /** Legt die Spiegelkarte fest; eine vorher gewählte wird abgelöst. */
    suspend fun setMirror(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val label = docId.substringAfterLast(':').ifBlank { docId }
            val now = System.currentTimeMillis()
            sourceDao.getMirror()?.let { old ->
                if (old.treeUri != treeUri.toString()) sourceDao.tombstone(old.uuid, now)
            }
            if (sourceDao.getMirror()?.treeUri != treeUri.toString()) {
                sourceDao.insert(
                    SourceEntity(
                        role = SourceRole.BACKUP,
                        label = label,
                        volumeUuid = docId.substringBefore(':', missingDelimiterValue = "").ifBlank { null },
                        treeUri = treeUri.toString(),
                        lastMountedAt = now,
                    ),
                )
            }
            true
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SAF, "Spiegelkarte einrichten fehlgeschlagen: $e")
            false
        }
    }

    suspend fun removeMirror() = withContext(Dispatchers.IO) {
        sourceDao.getMirror()?.let { sourceDao.tombstone(it.uuid, System.currentTimeMillis()) }
        settingDao.set(SettingEntity(KEY_LAST_SUCCESS, ""))
    }

    suspend fun isReachable(source: SourceEntity): Boolean = withContext(Dispatchers.IO) {
        SafOps.rootReachable(context.contentResolver, Uri.parse(source.treeUri))
    }

    companion object {
        const val KEY_LAST_SUCCESS = "mirror_last_success"

        /** Spiegelkarte gilt nach so vielen Tagen ohne Kopie als veraltet. */
        const val STALE_DAYS = 14
    }
}
