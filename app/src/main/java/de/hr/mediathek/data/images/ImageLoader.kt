package de.hr.mediathek.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Maße eines Bildes: Rohmaße der Datei, Drehung aus EXIF und Maße nach der Drehung (so wie angezeigt). */
data class ImageInfo(val rawWidth: Int, val rawHeight: Int, val rotation: Int) {
    private val swap get() = rotation == 90 || rotation == 270
    val width: Int get() = if (swap) rawHeight else rawWidth
    val height: Int get() = if (swap) rawWidth else rawHeight
}

/**
 * Lädt Bilder speicherschonend: verkleinert (inSampleSize) für die Ansicht und kachelweise (Region) für den Zoom,
 * damit auch Bilder mit 50 bis 200 Megapixeln nie ganz im Speicher liegen. EXIF-Drehung wird berücksichtigt.
 */
@Singleton
class ImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun info(uri: Uri): ImageInfo? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
            ImageInfo(options.outWidth, options.outHeight, exifRotation(uri))
        } catch (e: Exception) {
            null
        }
    }

    /** Ganzes Bild, so verkleinert, dass die längere Seite mindestens [maxPx] Pixel hat (aber nicht viel mehr). */
    suspend fun decodeSampled(uri: Uri, info: ImageInfo, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val longest = maxOf(info.rawWidth, info.rawHeight)
            var sample = 1
            while (longest / (sample * 2) >= maxPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@withContext null
            rotate(bitmap, info.rotation)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Hält einen Region-Decoder offen, damit Kacheln beim Zoomen schnell nachgeladen werden. */
    suspend fun openRegions(uri: Uri, info: ImageInfo): RegionSession? = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(stream, false)
            stream.close()
            decoder?.let { RegionSession(it, info) }
        } catch (e: Exception) {
            null
        }
    }

    /** Liefert ein animiertes Bild (GIF, animiertes WebP) oder null, wenn die Datei nicht animiert ist. */
    suspend fun animated(uri: Uri): Drawable? = withContext(Dispatchers.IO) {
        try {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri))
            if (drawable is AnimatedImageDrawable) drawable else null
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun exifRotation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        0
    }

    class RegionSession internal constructor(private val decoder: BitmapRegionDecoder, private val info: ImageInfo) {
        /** [area] in Bildkoordinaten nach der Drehung. Das Ergebnis ist höchstens etwa [maxPx] Pixel breit oder hoch. */
        @Synchronized
        fun decode(area: Rect, maxPx: Int): Bitmap? = try {
            val raw = toRaw(area)
            var sample = 1
            while (maxOf(raw.width(), raw.height()) / sample > maxPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            decoder.decodeRegion(raw, options)?.let { rotate(it, info.rotation) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }

        /** Rechnet ein Rechteck der angezeigten (gedrehten) Ansicht in Rohkoordinaten der Datei um. */
        internal fun toRaw(d: Rect): Rect {
            val w = info.rawWidth
            val h = info.rawHeight
            return when (info.rotation) {
                90 -> Rect(d.top, h - d.right, d.bottom, h - d.left)
                180 -> Rect(w - d.right, h - d.bottom, w - d.left, h - d.top)
                270 -> Rect(w - d.bottom, d.left, w - d.top, d.right)
                else -> Rect(d)
            }.also {
                it.left = it.left.coerceIn(0, w - 1)
                it.top = it.top.coerceIn(0, h - 1)
                it.right = it.right.coerceIn(it.left + 1, w)
                it.bottom = it.bottom.coerceIn(it.top + 1, h)
            }
        }

        fun close() {
            runCatching { decoder.recycle() }
        }
    }

    private companion object {
        fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
            if (degrees == 0) return bitmap
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        }
    }
}
