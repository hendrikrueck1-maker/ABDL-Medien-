package de.hr.mediathek.data.compress

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Liest die Spuren einer Videodatei für Vor- und Nachprüfung der Komprimierung. */
@Singleton
class MediaProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun probe(uri: Uri, sizeBytes: Long): MediaFacts? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var hasVideo = false
            var videoMime: String? = null
            var width = 0
            var height = 0
            var audio = 0
            var audioMime: String? = null
            var text = 0
            var hdr = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> if (!hasVideo) {
                        hasVideo = true
                        videoMime = mime
                        width = intOr(format, MediaFormat.KEY_WIDTH)
                        height = intOr(format, MediaFormat.KEY_HEIGHT)
                        hdr = isHdrOrTenBit(format)
                    }
                    mime.startsWith("audio/") -> {
                        audio++
                        if (audioMime == null) audioMime = mime
                    }
                    isSubtitle(mime) -> text++
                }
            }
            MediaFacts(readDuration(uri), sizeBytes, hasVideo, videoMime, width, height, audio, audioMime, text, hdr)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun readDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun intOr(format: MediaFormat, key: String): Int =
        if (format.containsKey(key)) format.getInteger(key) else 0

    private fun isSubtitle(mime: String): Boolean =
        mime.startsWith("text/") || mime in SUBTITLE_MIMES

    private fun isHdrOrTenBit(format: MediaFormat): Boolean {
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) return true
        }
        if (format.containsKey(MediaFormat.KEY_PROFILE)) {
            val profile = format.getInteger(MediaFormat.KEY_PROFILE)
            if (profile in TEN_BIT_PROFILES) return true
        }
        return false
    }

    private companion object {
        val SUBTITLE_MIMES = setOf(
            "application/x-subrip", "application/vobsub", "application/pgs", "application/dvbsubs",
            "application/x-quicktime-tx3g", "application/ttml+xml",
        )
        val TEN_BIT_PROFILES = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
        )
    }
}
