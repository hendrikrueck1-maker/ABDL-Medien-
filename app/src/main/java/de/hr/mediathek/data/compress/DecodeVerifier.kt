package de.hr.mediathek.data.compress

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Dekodiert die ganze Videospur einer fertigen Datei. Nur wer bis zum Ende ohne Fehler durchläuft, gilt als intakt. */
@Singleton
class DecodeVerifier @Inject constructor() {

    suspend fun decodesCompletely(file: File, expectedDurationMs: Long, onProgress: suspend (Float) -> Unit): Boolean =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext false
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var frames = 0L
                var lastPtsUs = 0L
                var idle = 0
                var tick = 0
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index) ?: return@withContext false
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (out >= 0) {
                        idle = 0
                        if (info.size > 0) {
                            frames++
                            if (info.presentationTimeUs > lastPtsUs) lastPtsUs = info.presentationTimeUs
                        }
                        decoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (++tick % 200 == 0 && expectedDurationMs > 0) {
                            onProgress((lastPtsUs / 1000f / expectedDurationMs).coerceIn(0f, 1f))
                        }
                    } else if (inputDone && ++idle > MAX_IDLE) {
                        return@withContext false // Decoder liefert nichts mehr, obwohl das Ende nicht erreicht ist
                    }
                }
                // Die letzte Bildzeit muss nahe an der Dauer liegen (ein Bild Toleranz, mindestens 1 s).
                frames > 0 && lastPtsUs / 1000 >= expectedDurationMs - maxOf(1000L, expectedDurationMs / 100)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 500
    }
}
