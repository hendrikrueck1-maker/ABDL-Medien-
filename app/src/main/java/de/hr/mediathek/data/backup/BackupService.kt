package de.hr.mediathek.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.storage.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Wiederherstellung und manueller Export/Import, aufgerufen aus der Oberfläche. */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageRepository,
    private val store: BackupStore,
    private val writer: BackupWriter,
    private val importer: BackupImporter,
    private val errorLogger: ErrorLogger,
) {
    private var cachedTree: Uri? = null

    /** Vorhandene Backups im Arbeitsordner, neueste zuerst. Leer, wenn der Ordner nicht erreichbar ist. */
    suspend fun listBackups(): List<BackupFileInfo> {
        val tree = storage.getWorkFolderUri() ?: return emptyList()
        cachedTree = tree
        val dir = store.backupDir(tree) ?: return emptyList()
        return store.list(tree, dir)
            .filter { store.timestampOf(it.name) != null }
            .sortedByDescending { store.timestampOf(it.name) }
    }

    suspend fun restore(file: BackupFileInfo): Result<ImportResult> {
        val tree = cachedTree ?: storage.getWorkFolderUri() ?: return Result.failure(IllegalStateException("Kein Arbeitsordner"))
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.docId)
        return runImport(uri)
    }

    suspend fun importFrom(uri: Uri): Result<ImportResult> = runImport(uri)

    private suspend fun runImport(uri: Uri): Result<ImportResult> = try {
        Result.success(
            importer.import(open = {
                context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Datei nicht lesbar")
            }),
        )
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        errorLogger.logNow(ErrorType.BACKUP, "Import fehlgeschlagen: ${e::class.java.simpleName}")
        Result.failure(e)
    }

    /** Schreibt ein frisches Backup an die vom Nutzer gewählte Stelle (z. B. USB-Stick, Cloud-Ordner). */
    suspend fun exportTo(target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "backup-export.zip")
        try {
            temp.delete()
            val summary = writer.write(temp, BuildConfig.VERSION_NAME)
            temp.inputStream().use { BackupWriter.verify(it, MediathekDatabase.VERSION) }
            context.contentResolver.openOutputStream(target, "w")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("Ziel nicht beschreibbar")
            Result.success(summary.totalRecords)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLogger.logNow(ErrorType.BACKUP, "Export fehlgeschlagen: ${e::class.java.simpleName}")
            Result.failure(e)
        } finally {
            temp.delete()
        }
    }
}
