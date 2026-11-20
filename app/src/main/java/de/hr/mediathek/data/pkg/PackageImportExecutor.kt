package de.hr.mediathek.data.pkg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.core.media.Fingerprinter
import de.hr.mediathek.data.backup.BackupImporter
import de.hr.mediathek.data.backup.ImportRejected
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.MediaAliasEntity
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.PackageDao
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.SourceEntity
import de.hr.mediathek.data.db.SourceRole
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bindet ein Mobil-Paket auf dem Handy ein: prüft die Dateien (vorhanden, Größe, Fingerabdruck), führt die Metadaten
 * zusammen (nach UUID, neuere Änderung gewinnt) und verknüpft die Medien mit den Dateien im Paketordner.
 * Mehrfaches Einlesen desselben Ordners aktualisiert das Paket; Medien, die nicht mehr darin liegen, werden "offline".
 */
@Singleton
class PackageImportExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val packageDao: PackageDao,
    private val mediaDao: MediaFileDao,
    private val importer: BackupImporter,
    private val fingerprinter: Fingerprinter,
) {
    suspend fun execute(job: JobEntity, ctx: JobContext): JobOutcome = withContext(Dispatchers.IO) {
        val tree = Uri.parse(JSONObject(job.paramsJson).getString("treeUri"))
        val resolver = context.contentResolver
        if (!SafOps.rootReachable(resolver, tree)) return@withContext JobOutcome.Failed("Paketordner nicht erreichbar")
        val root = DocumentsContract.getTreeDocumentId(tree)

        val top = SafOps.children(resolver, tree, root)
        val manifestDoc = top.firstOrNull { !it.isDir && it.name == PackageLogic.MANIFEST_NAME }
            ?: return@withContext JobOutcome.Failed("Paket unvollständig: Der Export wurde nicht abgeschlossen")
        val zipDoc = top.firstOrNull { !it.isDir && it.name == PackageLogic.METADATA_NAME }
            ?: return@withContext JobOutcome.Failed("Paket unvollständig: Metadaten fehlen")

        ctx.report("Paket wird gelesen", null, force = true)
        val manifest = try {
            parse(resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, manifestDoc.docId))!!.use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) {
            return@withContext JobOutcome.Failed("Manifest nicht lesbar")
        }
        if (manifest.schemaVersion > MediathekDatabase.VERSION) {
            return@withContext JobOutcome.Failed("Das Paket stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
        }
        if (manifest.files.any { !PackageLogic.isSafePath(it.path) }) return@withContext JobOutcome.Failed("Manifest enthält ungültige Pfade")

        val dirs = HashMap<String, Map<String, SafOps.Child>>()
        for (d in listOf(PackageLogic.DIR_VIDEOS, PackageLogic.DIR_IMAGES)) {
            val id = top.firstOrNull { it.isDir && it.name == d }?.docId
            dirs[d] = if (id == null) emptyMap() else SafOps.children(resolver, tree, id).filter { !it.isDir }.associateBy { it.name }
        }

        // 1. Dateien prüfen, bevor die Datenbank angefasst wird.
        val good = ArrayList<Pair<ManifestFile, String>>()
        var missing = 0
        var broken = 0
        manifest.files.forEachIndexed { i, f ->
            ctx.checkpoint()
            ctx.report("Dateien werden geprüft: ${i + 1} von ${manifest.files.size}", (i + 1f) / manifest.files.size)
            val child = dirs[f.path.substringBefore('/')]?.get(f.path.substringAfter('/'))
            if (child == null) { missing++; return@forEachIndexed }
            val size = SafOps.stat(resolver, tree, child.docId)?.size
            if (size != f.size) { broken++; return@forEachIndexed }
            val fp = runCatching { fingerprinter.compute(DocumentsContract.buildDocumentUriUsingTree(tree, child.docId), f.size) }.getOrNull()
            if (fp != f.packageFingerprint) { broken++; return@forEachIndexed }
            good += f to child.docId
        }
        if (good.isEmpty()) return@withContext JobOutcome.Failed("Keine gültige Datei im Paket gefunden")

        // 2. Quelle anlegen oder wiederverwenden.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val source = sourceDao.findByTreeUri(tree.toString()) ?: SourceEntity(
            role = SourceRole.MEDIA,
            label = "Paket: " + manifest.name,
            volumeUuid = root.substringBefore(':', "").ifBlank { null },
            treeUri = tree.toString(),
            lastMountedAt = System.currentTimeMillis(),
        ).also { sourceDao.insert(it) }

        // 3. Metadaten zusammenführen (die Quellen des Geräts, das das Paket erstellt hat, bleiben außen vor).
        ctx.report("Daten werden zusammengeführt", null, force = true)
        val zipUri = DocumentsContract.buildDocumentUriUsingTree(tree, zipDoc.docId)
        try {
            importer.import({ resolver.openInputStream(zipUri) ?: error("Metadaten nicht lesbar") }, setOf("source"))
        } catch (e: ImportRejected) {
            return@withContext JobOutcome.Failed(e.message ?: "Paket abgelehnt")
        }

        // 4. Medien mit den Dateien verknüpfen.
        ctx.report("Medien werden verknüpft", null, force = true)
        val present = good.map { it.first.mediaUuid }.chunked(500).flatMap { packageDao.existing(it) }.toHashSet()
        val now = System.currentTimeMillis()
        var attached = 0
        var noRow = 0
        for ((f, docId) in good) {
            if (f.mediaUuid !in present) { noRow++; continue }
            packageDao.attach(f.mediaUuid, source.uuid, f.path, docId, f.packageFingerprint, f.size, now)
            val orig = f.originalFingerprint
            if (orig != null && orig != f.packageFingerprint && packageDao.aliasCount(f.mediaUuid, orig) == 0) {
                mediaDao.insertAlias(MediaAliasEntity(mediaUuid = f.mediaUuid, fingerprint = orig))
            }
            attached++
        }
        val inManifest = manifest.files.map { it.mediaUuid }.toHashSet()
        val stale = packageDao.uuidsOfSource(source.uuid).filter { it !in inManifest }
        stale.chunked(500).forEach { packageDao.markOffline(it, now) }

        val extra = buildString {
            if (missing + broken + noRow > 0) append(", ${missing + broken + noRow} nicht übernommen")
            if (stale.isNotEmpty()) append(", ${stale.size} nicht mehr im Paket")
        }
        JobOutcome.Completed("Paket eingebunden: $attached Medien$extra")
    }

    private fun parse(text: String): PackageManifest {
        val o = JSONObject(text)
        require(o.getInt("format") == PackageLogic.FORMAT) { "Format unbekannt" }
        val arr = o.getJSONArray("files")
        val files = (0 until arr.length()).map {
            val f = arr.getJSONObject(it)
            ManifestFile(
                f.getString("mediaUuid"), f.getString("type"), f.getString("path"), f.getLong("size"),
                if (f.isNull("originalFingerprint")) null else f.getString("originalFingerprint"),
                f.getString("packageFingerprint"), f.optBoolean("compressed", false),
            )
        }
        return PackageManifest(o.getInt("schemaVersion"), o.optString("appVersion"), o.optLong("createdAt"), o.optString("name", "Paket"), files)
    }
}
