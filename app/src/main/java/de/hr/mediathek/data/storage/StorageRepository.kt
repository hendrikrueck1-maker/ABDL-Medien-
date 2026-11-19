package de.hr.mediathek.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.SourceEntity
import de.hr.mediathek.data.db.SourceRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AddSourceResult {
    data class Added(val source: SourceEntity) : AddSourceResult
    data object AlreadyExists : AddSourceResult
    data class Failed(val message: String) : AddSourceResult
}

sealed interface WorkFolderResult {
    data object Set : WorkFolderResult
    /** Ordner liegt unter Android/data und wäre für den Nutzer nicht erreichbar. */
    data object Rejected : WorkFolderResult
    data class Failed(val message: String) : WorkFolderResult
}

@Singleton
class StorageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val settingDao: SettingDao,
    private val errorLogger: ErrorLogger,
) {
    fun observeSources(): Flow<List<SourceEntity>> = sourceDao.observeAll()
    fun observeWorkFolderLabel(): Flow<String?> = settingDao.observe(KEY_WORK_FOLDER_LABEL)
    fun observeOnboardingSeen(): Flow<String?> = settingDao.observe(KEY_SAF_ONBOARDING)

    suspend fun getSources(): List<SourceEntity> = sourceDao.getAll()

    suspend fun markOnboardingSeen() = settingDao.set(SettingEntity(KEY_SAF_ONBOARDING, "1"))

    /** Bindet einen vom Nutzer gewählten Ordner als Medienquelle ein (nur die Wurzel wird gemerkt). */
    suspend fun addMediaSource(treeUri: Uri): AddSourceResult = withContext(Dispatchers.IO) {
        try {
            if (sourceDao.findByTreeUri(treeUri.toString()) != null) return@withContext AddSourceResult.AlreadyExists
            persistPermission(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val label = queryDisplayName(treeUri, docId) ?: docId.substringAfterLast(':').ifBlank { docId }
            val source = SourceEntity(
                role = SourceRole.MEDIA,
                label = label,
                volumeUuid = docId.substringBefore(':', missingDelimiterValue = "").ifBlank { null },
                treeUri = treeUri.toString(),
                lastMountedAt = System.currentTimeMillis(),
            )
            sourceDao.insert(source)
            AddSourceResult.Added(source)
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SAF, "Quelle einbinden fehlgeschlagen: $e")
            AddSourceResult.Failed(e.message ?: e.toString())
        }
    }

    /** Prüft, ob die Wurzel der Quelle gerade lesbar ist (Karte eingelegt). */
    suspend fun isReachable(source: SourceEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val tree = Uri.parse(source.treeUri)
            val docId = DocumentsContract.getTreeDocumentId(tree)
            queryDisplayName(tree, docId) != null
        } catch (e: Exception) {
            false
        }
    }

    suspend fun markMounted(source: SourceEntity) {
        sourceDao.markMounted(source.uuid, System.currentTimeMillis())
    }

    /**
     * Setzt den Arbeitsordner (Backups, Produktfotos, Mobil-Paket). Er muss im frei zugänglichen
     * Bereich liegen, ausdrücklich nicht unter Android/data. Ein .nomedia-Marker hält Galerie-Apps fern.
     */
    suspend fun setWorkFolder(treeUri: Uri): WorkFolderResult = withContext(Dispatchers.IO) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            if (docId.contains("Android/data", ignoreCase = true) || docId.contains("Android/obb", ignoreCase = true)) {
                return@withContext WorkFolderResult.Rejected
            }
            persistPermission(treeUri)
            ensureNoMedia(treeUri)
            val label = queryDisplayName(treeUri, docId) ?: docId.substringAfterLast(':').ifBlank { docId }
            settingDao.set(SettingEntity(KEY_WORK_FOLDER_URI, treeUri.toString()))
            settingDao.set(SettingEntity(KEY_WORK_FOLDER_LABEL, label))
            WorkFolderResult.Set
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.SAF, "Arbeitsordner setzen fehlgeschlagen: $e")
            WorkFolderResult.Failed(e.message ?: e.toString())
        }
    }

    suspend fun getWorkFolderUri(): Uri? = settingDao.get(KEY_WORK_FOLDER_URI)?.let { Uri.parse(it) }

    private fun persistPermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    private fun queryDisplayName(treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    /** Legt in der Wurzel des Baums eine .nomedia-Datei an, falls sie fehlt. */
    private fun ensureNoMedia(treeUri: Uri) {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        var exists = false
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == NOMEDIA) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) {
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            DocumentsContract.createDocument(resolver, rootUri, "application/octet-stream", NOMEDIA)
        }
    }

    companion object {
        const val KEY_WORK_FOLDER_URI = "work_folder_uri"
        const val KEY_WORK_FOLDER_LABEL = "work_folder_label"
        const val KEY_SAF_ONBOARDING = "saf_onboarding_seen"
        private const val NOMEDIA = ".nomedia"
    }
}
