package de.hr.mediathek.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

class GeneratedThumb(
    val bitmap: Bitmap,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Erzeugt ein Vorschaubild aus einer Datei. Läuft isoliert mit Zeitlimit: hängt der Decoder oder
 * MediaMetadataRetriever bei einer kaputten Datei, wird der Auftrag abgebrochen statt die App zu blockieren.
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val isolatedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** null bei Fehler oder Zeitüberschreitung. */
    suspend fun generate(docUri: Uri, type: MediaType, maxPx: Int): GeneratedThumb? {
        val work = isolatedScope.async {
            try {
                when (type) {
                    MediaType.IMAGE -> image(docUri, maxPx)
                    MediaType.VIDEO -> video(docUri, maxPx)
                }
            } catch (e: Exception) {
                null
            }
        }
        val result = withTimeoutOrNull(TIMEOUT_MS) { work.await() }
        if (result == null) work.cancel()
        return result
    }

    private fun image(uri: Uri, maxPx: Int): GeneratedThumb {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var originalW = 0
        var originalH = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            originalW = info.size.width
            originalH = info.size.height
            val longest = max(originalW, originalH)
            if (longest > maxPx) {
                val factor = maxPx.toFloat() / longest
                decoder.setTargetSize(
                    max(1, (originalW * factor).roundToInt()),
                    max(1, (originalH * factor).roundToInt()),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { true }
        }
        return GeneratedThumb(bitmap, width = originalW, height = originalH)
    }

    private fun video(uri: Uri, maxPx: Int): GeneratedThumb? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // Aufgenommene Breite/Höhe sind unrotiert; für die Anzeige zählt die gedrehte Größe.
            if (rotation == 90 || rotation == 270) {
                val t = w; w = h; h = t
            }
            // Ein Bild nach etwa 10 % der Laufzeit ist meist aussagekräftiger als das erste (oft schwarze) Bild.
            val timeUs = ((duration ?: 0L) * 1000L / 10L).coerceAtLeast(0L)
            val frame: Bitmap? = if (w != null && h != null && w > 0 && h > 0) {
                val factor = minOf(1f, maxPx.toFloat() / max(w, h))
                val tw = max(1, (w * factor).roundToInt())
                val th = max(1, (h * factor).roundToInt())
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                    ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxPx) }
            }
            return frame?.let { GeneratedThumb(it, duration, w, h) }
        } finally {
            retriever.release()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val factor = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * factor).roundToInt()),
            max(1, (bitmap.height * factor).roundToInt()),
            true,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
    }
}
