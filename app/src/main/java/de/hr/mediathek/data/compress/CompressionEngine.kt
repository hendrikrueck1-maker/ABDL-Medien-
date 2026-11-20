package de.hr.mediathek.data.compress

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Effects
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kodiert ein Video mit dem Hardware-Encoder nach H.265 in eine MP4-Datei. Der Ton wird nicht angefasst
 * (Passthrough, solange das Zielformat ihn aufnehmen kann; sonst fällt das später in der Prüfung auf).
 * Media3 Transformer schreibt nur in einen Dateipfad, deshalb entsteht das Ergebnis zuerst im App-Speicher.
 */
@Singleton
class CompressionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** [onProgress] darf [CancellationException] werfen, um abzubrechen. */
    @OptIn(UnstableApi::class)
    suspend fun encode(
        source: Uri,
        output: File,
        videoBitrate: Int,
        clipStartMs: Long = 0,
        clipLengthMs: Long = 0,
        videoMime: String = MimeTypes.VIDEO_H265,
        maxHeight: Int = 0,
        allowFallback: Boolean = false,
        onProgress: suspend (Float) -> Unit,
    ) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                .setEnableFallback(allowFallback)
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMime)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(exportException)
                    }
                })
                .build()
            val item = MediaItem.Builder().setUri(source).apply {
                if (clipLengthMs > 0) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartMs)
                            .setEndPositionMs(clipStartMs + clipLengthMs)
                            .build(),
                    )
                }
            }.build()
            val edited = EditedMediaItem.Builder(item).apply {
                if (maxHeight > 0) {
                    setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(maxHeight))))
                }
            }.build()
            transformer.start(edited, output.absolutePath)
            val poller = launch {
                val holder = ProgressHolder()
                var lastValue = -1
                var lastChange = System.currentTimeMillis()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        if (holder.progress != lastValue) {
                            lastValue = holder.progress
                            lastChange = System.currentTimeMillis()
                        }
                        try {
                            onProgress(holder.progress / 100f)
                        } catch (e: CancellationException) {
                            done.completeExceptionally(e)
                            return@launch
                        }
                    }
                    if (System.currentTimeMillis() - lastChange > STALL_TIMEOUT_MS) {
                        done.completeExceptionally(IllegalStateException("Kodierung hängt (kein Fortschritt seit 15 Minuten)"))
                        return@launch
                    }
                    delay(1000)
                }
            }
            try {
                done.await()
            } catch (e: Throwable) {
                transformer.cancel()
                throw e
            } finally {
                poller.cancel()
            }
        }
    }
}

private const val STALL_TIMEOUT_MS = 15L * 60 * 1000

/** Kurze, inhaltsfreie Fehlerursache; bei Media3-Fehlern mit dem Fehlercode (z. B. ENCODER_INIT_FAILED). */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun describeFailure(e: Throwable): String {
    val code = (e as? ExportException)?.errorCodeName
    val cause = e.cause?.let { it::class.java.simpleName }
    return listOfNotNull(e::class.java.simpleName, code, cause?.takeIf { code == null }).joinToString(" / ")
}
