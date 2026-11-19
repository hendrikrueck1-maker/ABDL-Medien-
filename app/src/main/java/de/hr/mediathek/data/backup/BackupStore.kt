package de.hr.mediathek.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobType
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class BackupFileInfo(val docId: String, val name: String, val lastModified: Long, val size: Long)

data class BackupStatus(
    val lastSuccessAt: Long?,
    val warning: String?,
    val latestJob: JobEntity?,
)

/** Ablage der Backups im Arbeitsordner (per SAF) und der Zustand "letztes Backup". */
@Singleton
class BackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingDao: SettingDao,
    private val jobDao: JobDao,
) {
    fun observeStatus(): Flow<BackupStatus> = combine(
        settingDao.observe(KEY_LAST_SUCCESS),
        settingDao.observe(KEY_WARNING),
        jobDao.observeLatest(JobType.BACKUP),
    ) { last, warn, job -> BackupStatus(last?.toLongOrNull(), warn?.ifBlank { null }, job) }

    suspend fun lastSuccessAt(): Long? = settingDao.get(KEY_LAST_SUCCESS)?.toLongOrNull()
    suspend fun lastRecordCount(): Int? = settingDao.get(KEY_LAST_RECORDS)?.toIntOrNull()

    suspend fun recordSuccess(records: Int, warning: String?) {
        settingDao.set(SettingEntity(KEY_LAST_SUCCESS, System.currentTimeMillis().toString()))
        settingDao.set(SettingEntity(KEY_LAST_RECORDS, records.toString()))
        settingDao.set(SettingEntity(KEY_WARNING, warning ?: ""))
    }

    fun newFileName(now: Long = System.currentTimeMillis()): String =
        "backup-" + FORMAT.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())) + ".zip"

    /** Zeitstempel (ms) aus dem Dateinamen; null, wenn der Name nicht zu unseren Backups passt. */
    fun timestampOf(name: String): Long? {
        val m = NAME_PATTERN.matchEntire(name) ?: return null
        return runCatching {
            java.time.LocalDateTime.parse(m.groupValues[1], FORMAT)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    // --- SAF-Hilfen -----------------------------------------------------------------------------

    /** Findet oder legt den Unterordner "backups" im Arbeitsordner an. null, wenn der Ordner nicht erreichbar ist. */
    suspend fun backupDir(workTree: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val rootId = DocumentsContract.getTreeDocumentId(workTree)
            val existing = children(workTree, rootId).firstOrNull { it.name == DIR_NAME && it.isDir }
            val docId = existing?.docId ?: run {
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(workTree, rootId)
                val created = DocumentsContract.createDocument(
                    context.contentResolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, DIR_NAME,
                ) ?: return@withContext null
                DocumentsContract.getDocumentId(created)
            }
            DocumentsContract.buildDocumentUriUsingTree(workTree, docId)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun list(workTree: Uri, dir: Uri): List<BackupFileInfo> = withContext(Dispatchers.IO) {
        children(workTree, DocumentsContract.getDocumentId(dir)).filter { !it.isDir }
            .map { BackupFileInfo(it.docId, it.name, it.lastModified, it.size) }
    }

    /** Kopiert [source] als Temp-Datei in den Backup-Ordner und liest sie zur Kontrolle zurück. Gibt die Dokument-URI zurück. */
    suspend fun copyAndVerify(workTree: Uri, dir: Uri, source: File, finalName: String, expectedSchema: Int): Pair<Uri, Int> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val tmpName = "$finalName.tmp"
            val tmpUri = DocumentsContract.createDocument(resolver, dir, "application/octet-stream", tmpName)
                ?: error("Temp-Datei konnte nicht angelegt werden")
            try {
                resolver.openOutputStream(tmpUri, "w")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: error("Ziel nicht beschreibbar")
                val records = resolver.openInputStream(tmpUri)?.use { BackupWriter.verify(it, expectedSchema) }
                    ?: error("Ziel nicht lesbar")
                val renamed = DocumentsContract.renameDocument(resolver, tmpUri, finalName)
                    ?: error("Umbenennen fehlgeschlagen")
                renamed to records
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, tmpUri) }
                throw e
            }
        }

    fun delete(workTree: Uri, docId: String) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(workTree, docId)
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }

    private class Child(val docId: String, val name: String, val mime: String, val size: Long, val lastModified: Long) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun children(tree: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val result = ArrayList<Child>()
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                result += Child(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4))
            }
        }
        return result
    }

    companion object {
        const val KEY_LAST_SUCCESS = "backup_last_success"
        const val KEY_LAST_RECORDS = "backup_last_records"
        const val KEY_WARNING = "backup_warning"
        const val DIR_NAME = "backups"
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")
        private val NAME_PATTERN = Regex("backup-(\\d{4}-\\d{2}-\\d{2}T\\d{6})\\.zip")
    }
}
