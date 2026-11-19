package de.hr.mediathek.data.images

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.data.db.HashCandidate
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.SourceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berechnet für Bilder den Inhalts-Hash (SHA-256, exakte Duplikate) und den Wahrnehmungs-Hash (dHash, ähnliche Bilder).
 * Nur neue oder geänderte Bilder werden bearbeitet; das Ergebnis liegt in der Datenbank.
 */
@Singleton
class ImageHashJobExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaFileDao,
    private val sourceDao: SourceDao,
    private val loader: ImageLoader,
) {
    suspend fun execute(ctx: JobContext): JobOutcome {
        val treeBySource = HashMap<String, Uri?>()
        val skipped = HashSet<String>()
        var done = 0
        var undecodable = 0
        while (true) {
            ctx.checkpoint()
            val batch = mediaDao.imagesNeedingHash(BATCH + skipped.size).filter { it.uuid !in skipped }.take(BATCH)
            if (batch.isEmpty()) break
            for (item in batch) {
                ctx.checkpoint()
                when (processOne(item, treeBySource)) {
                    Outcome.FULL -> done++
                    Outcome.CONTENT_ONLY -> {
                        done++
                        undecodable++
                    }
                    Outcome.SKIPPED -> skipped += item.uuid
                }
                if (done % PROGRESS_EVERY == 0) {
                    val stats = mediaDao.hashStatsOnce()
                    ctx.report("Bilder analysiert: ${stats.done} von ${stats.total}", stats.done.toFloat() / stats.total.coerceAtLeast(1))
                }
            }
        }
        val parts = arrayListOf("Bilder analysiert: $done")
        if (undecodable > 0) parts += "$undecodable nicht lesbar (nur exakter Vergleich)"
        if (skipped.isNotEmpty()) parts += "${skipped.size} übersprungen"
        return JobOutcome.Completed(parts.joinToString(", "))
    }

    private enum class Outcome { FULL, CONTENT_ONLY, SKIPPED }

    private suspend fun processOne(item: HashCandidate, treeBySource: MutableMap<String, Uri?>): Outcome {
        val tree = treeBySource.getOrPut(item.sourceUuid) {
            sourceDao.getByUuid(item.sourceUuid)?.treeUri?.let { Uri.parse(it) }
        } ?: return Outcome.SKIPPED
        val docId = item.documentIdCache ?: return Outcome.SKIPPED
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

        val content = sha256(uri) ?: return Outcome.SKIPPED
        val perceptual = perceptual(uri)
        mediaDao.setHashes(item.uuid, content, perceptual, item.fingerprint)
        return if (perceptual != null) Outcome.FULL else Outcome.CONTENT_ONLY
    }

    private suspend fun sha256(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            context.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            } ?: return@withContext null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun perceptual(uri: Uri): Long? {
        val info = loader.info(uri) ?: return null
        val small = loader.decodeSampled(uri, info, SAMPLE_PX) ?: return null
        var scaled: Bitmap? = null
        try {
            scaled = Bitmap.createScaledBitmap(small, PerceptualHash.GRID_W, PerceptualHash.GRID_H, true)
            val pixels = IntArray(PerceptualHash.GRID_W * PerceptualHash.GRID_H)
            scaled.getPixels(pixels, 0, PerceptualHash.GRID_W, 0, 0, PerceptualHash.GRID_W, PerceptualHash.GRID_H)
            return PerceptualHash.dHash(IntArray(pixels.size) { PerceptualHash.luminance(pixels[it]) })
        } finally {
            if (scaled != null && scaled !== small) scaled.recycle()
            small.recycle()
        }
    }

    private companion object {
        const val BATCH = 50
        const val PROGRESS_EVERY = 10
        const val SAMPLE_PX = 128
    }
}
