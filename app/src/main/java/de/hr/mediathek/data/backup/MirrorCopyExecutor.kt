package de.hr.mediathek.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.storage.StorageRepository
import de.hr.mediathek.data.trash.SafOps
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kopiert die neueste Sicherung und neue oder geänderte Produktfotos aus dem Arbeitsordner auf die Spiegelkarte.
 * Es wird nur kopiert, was fehlt oder eine andere Größe hat; jede Datei wird erst als .tmp geschrieben und geprüft.
 */
@Singleton
class MirrorCopyExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val storage: StorageRepository,
    private val mirror: MirrorService,
) {
    suspend fun execute(ctx: JobContext): JobOutcome {
        val resolver = context.contentResolver
        val target = sourceDao.getMirror() ?: return JobOutcome.Failed("Keine Spiegelkarte eingerichtet")
        val dstTree = Uri.parse(target.treeUri)
        if (!SafOps.rootReachable(resolver, dstTree)) return JobOutcome.Failed("Spiegelkarte nicht erreichbar, bitte einlegen")
        val srcTree = storage.getWorkFolderUri() ?: return JobOutcome.Failed("Kein Arbeitsordner gewählt")
        if (!SafOps.rootReachable(resolver, srcTree)) return JobOutcome.Failed("Arbeitsordner nicht erreichbar")

        val dstRoot = DocumentsContract.getTreeDocumentId(dstTree)
        var copied = 0
        var skipped = 0

        // 1. Neueste Sicherung
        ctx.report("Sicherung wird gespiegelt", null, force = true)
        val srcBackups = SafOps.resolveDir(resolver, srcTree, BackupStore.DIR_NAME)
        val newest = srcBackups?.let { dir ->
            SafOps.children(resolver, srcTree, dir)
                .filter { !it.isDir && BACKUP_NAME.matches(it.name) }
                .maxByOrNull { it.name }
        }
        if (srcBackups != null && newest != null) {
            val dstBackups = SafOps.findOrCreateDir(resolver, dstTree, dstRoot, BackupStore.DIR_NAME, true)
                ?: return JobOutcome.Failed("Ordner auf der Spiegelkarte nicht anlegbar")
            if (copyIfNeeded(resolver, srcTree, newest, dstTree, dstBackups)) copied++ else skipped++
            pruneBackups(resolver, dstTree, dstBackups)
        }
        ctx.checkpoint()

        // 2. Produktfotos (rekursiv)
        val srcPhotos = SafOps.resolveDir(resolver, srcTree, PHOTO_DIR)
        if (srcPhotos != null) {
            val dstPhotos = SafOps.findOrCreateDir(resolver, dstTree, dstRoot, PHOTO_DIR, true)
                ?: return JobOutcome.Failed("Fotoordner auf der Spiegelkarte nicht anlegbar")
            val counts = IntArray(2)
            copyTree(ctx, resolver, srcTree, srcPhotos, dstTree, dstPhotos, counts)
            copied += counts[0]
            skipped += counts[1]
        }

        mirror.recordSuccess()
        return JobOutcome.Completed("Gespiegelt: $copied kopiert, $skipped unverändert")
    }

    private suspend fun copyTree(
        ctx: JobContext, resolver: ContentResolver,
        srcTree: Uri, srcDir: String, dstTree: Uri, dstDir: String, counts: IntArray,
    ) {
        for (child in SafOps.children(resolver, srcTree, srcDir)) {
            ctx.checkpoint()
            if (child.isDir) {
                val sub = SafOps.findOrCreateDir(resolver, dstTree, dstDir, child.name, true) ?: continue
                copyTree(ctx, resolver, srcTree, child.docId, dstTree, sub, counts)
            } else {
                ctx.report("Fotos werden gespiegelt: ${counts[0] + counts[1]}", null)
                if (copyIfNeeded(resolver, srcTree, child, dstTree, dstDir)) counts[0]++ else counts[1]++
            }
        }
    }

    /** true = kopiert, false = war schon vorhanden. */
    private fun copyIfNeeded(
        resolver: ContentResolver, srcTree: Uri, file: SafOps.Child, dstTree: Uri, dstDir: String,
    ): Boolean {
        val srcStat = SafOps.stat(resolver, srcTree, file.docId) ?: error("Quelle nicht lesbar: ${file.name}")
        val existing = SafOps.children(resolver, dstTree, dstDir).firstOrNull { !it.isDir && it.name == file.name }
        if (existing != null) {
            val dstStat = SafOps.stat(resolver, dstTree, existing.docId)
            if (dstStat != null && dstStat.size == srcStat.size) return false
            SafOps.delete(resolver, dstTree, existing.docId)
        }
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(dstTree, dstDir)
        val tmp = DocumentsContract.createDocument(resolver, dirUri, "application/octet-stream", file.name + ".tmp")
            ?: error("Zieldatei nicht anlegbar")
        try {
            val src = DocumentsContract.buildDocumentUriUsingTree(srcTree, file.docId)
            resolver.openInputStream(src)?.use { input ->
                resolver.openOutputStream(tmp, "w")?.use { out -> input.copyTo(out) } ?: error("Ziel nicht beschreibbar")
            } ?: error("Quelle nicht lesbar")
            val written = SafOps.stat(resolver, dstTree, DocumentsContract.getDocumentId(tmp))?.size
            if (written != srcStat.size) error("Größe stimmt nach dem Kopieren nicht: ${file.name}")
            DocumentsContract.renameDocument(resolver, tmp, file.name) ?: error("Umbenennen fehlgeschlagen")
            return true
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, tmp) }
            throw e
        }
    }

    /** Auf der Spiegelkarte bleiben die drei neuesten Sicherungen. */
    private fun pruneBackups(resolver: ContentResolver, tree: Uri, dir: String) {
        val all = SafOps.children(resolver, tree, dir).filter { !it.isDir }
        all.filter { it.name.endsWith(".tmp") }.forEach { SafOps.delete(resolver, tree, it.docId) }
        all.filter { BACKUP_NAME.matches(it.name) }.sortedByDescending { it.name }.drop(KEEP)
            .forEach { SafOps.delete(resolver, tree, it.docId) }
    }

    companion object {
        const val PHOTO_DIR = "produktfotos"
        private const val KEEP = 3
        private val BACKUP_NAME = Regex("backup-\\d{4}-\\d{2}-\\d{2}T\\d{6}\\.zip")
    }
}
