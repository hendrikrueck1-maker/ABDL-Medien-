package de.hr.mediathek.feature.diag

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class SourceInfo(
    val sizeBytes: Long,
    val durationMs: Long,
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val colorTransfer: Int?,
    val audioMimes: List<String>,
    val subtitleCount: Int,
) {
    val overallBitrate: Long
        get() = if (durationMs > 0) sizeBytes * 8 * 1000 / durationMs else 0L
}

/**
 * Spike: komprimiert bis zu 5 gewählte Videos mit Media3 Transformer (HEVC, Hardware-Encoder,
 * Audio möglichst ohne Neukodierung) und misst Dauer, Größe und Audio-Verhalten.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class TranscodeDiagViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state.asStateFlow()

    /** Zielbitrate in Prozent der Quellbitrate: 65 (kaum sichtbar), 50 (ausgewogen), 35 (stark). */
    private val _stage = MutableStateFlow(50)
    val stage: StateFlow<Int> = _stage.asStateFlow()

    /** Nur die ersten 60 Sekunden jedes Videos umwandeln (schneller Test). */
    private val _clip = MutableStateFlow(true)
    val clip: StateFlow<Boolean> = _clip.asStateFlow()

    private var job: Job? = null
    @Volatile private var activeTransformer: Transformer? = null

    fun setStage(percent: Int) { _stage.value = percent }
    fun setClip(enabled: Boolean) { _clip.value = enabled }

    fun start(uris: List<Uri>) {
        if (uris.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            val outDir = File(context.cacheDir, "spike").apply { mkdirs() }
            outDir.listFiles()?.forEach { it.delete() }
            val report = StringBuilder()
            report.appendLine("Videotest (Media3 Transformer)")
            report.appendLine(deviceInfoLine())
            report.appendLine(encoderInfo())
            report.appendLine()
            val outputs = mutableListOf<File>()
            try {
                uris.take(5).forEachIndexed { index, uri ->
                    val number = index + 1
                    _state.value = DiagUiState(running = true, progress = "Video $number: Analyse …", outputs = outputs.toList())
                    report.appendLine("== Video $number ==")
                    try {
                        val info = withContext(Dispatchers.IO) { probe(uri) }
                        report.appendLine(describe(info))
                        val clipMs = if (_clip.value) 60_000L else null
                        val effectiveMs = if (clipMs != null) minOf(clipMs, info.durationMs) else info.durationMs
                        val target = (info.overallBitrate * _stage.value / 100).coerceAtLeast(500_000L)
                        report.appendLine(
                            "Ziel: HEVC, ${_stage.value} % der Quellbitrate = ${formatMbit(target)}" +
                                (if (clipMs != null) ", nur erste 60 s" else ", ganzes Video"),
                        )
                        val outFile = File(outDir, "test-$number.mp4")
                        val t0 = SystemClock.elapsedRealtime()
                        val result = transcode(uri, outFile, target.toInt(), clipMs) { percent ->
                            _state.value = DiagUiState(
                                running = true,
                                progress = "Video $number: $percent %",
                                outputs = outputs.toList(),
                            )
                        }
                        val wallMs = SystemClock.elapsedRealtime() - t0
                        outputs.add(outFile)
                        val estSource = info.overallBitrate * effectiveMs / 8000
                        val ratio = if (estSource > 0) outFile.length() * 100 / estSource else 0L
                        val passthrough = result.audioMimeType != null &&
                            info.audioMimes.firstOrNull() == result.audioMimeType
                        report.appendLine(
                            String.format(
                                Locale.GERMANY,
                                "Ergebnis: %.1f s Video in %.1f s Realzeit (Faktor %.1fx)",
                                effectiveMs / 1000.0,
                                wallMs / 1000.0,
                                if (wallMs > 0) effectiveMs.toDouble() / wallMs else 0.0,
                            ),
                        )
                        report.appendLine("Datei: ${formatMb(outFile.length())} (ca. $ratio % der Quelle für diesen Abschnitt)")
                        report.appendLine("Ausgabe Video: ${result.videoMimeType}, Audio: ${result.audioMimeType}")
                        report.appendLine("Audio ohne Neukodierung (gleicher Codec): ${if (passthrough) "ja" else "nein/unklar"}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ExportException) {
                        report.appendLine("FEHLER: ${e.errorCodeName} – ${e.message}")
                    } catch (e: Exception) {
                        report.appendLine("FEHLER: $e")
                    }
                    report.appendLine()
                }
                _state.value = DiagUiState(result = report.toString(), outputs = outputs.toList())
            } catch (e: CancellationException) {
                _state.value = DiagUiState(result = report.toString() + "\nAbgebrochen.", outputs = outputs.toList())
                throw e
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun encoderInfo(): String {
        val encoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MimeTypes.VIDEO_H265, true) } }
        if (encoders.isEmpty()) return "HEVC-Encoder: KEINER gefunden"
        return "HEVC-Encoder: " + encoders.joinToString("; ") {
            "${it.name} (Hardware: ${it.isHardwareAccelerated}, nur Software: ${it.isSoftwareOnly})"
        }
    }

    private fun probe(uri: Uri): SourceInfo {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoMime: String? = null
            var width = 0
            var height = 0
            var frameRate = 0f
            var colorTransfer: Int? = null
            var durationMs = 0L
            val audio = mutableListOf<String>()
            var subtitles = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoMime == null -> {
                        videoMime = mime
                        width = format.intOrZero(MediaFormat.KEY_WIDTH)
                        height = format.intOrZero(MediaFormat.KEY_HEIGHT)
                        frameRate = try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (e: Exception) {
                            format.intOrZero(MediaFormat.KEY_FRAME_RATE).toFloat()
                        }
                        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                            colorTransfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        }
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                        }
                    }
                    mime.startsWith("audio/") -> audio.add(mime)
                    mime.startsWith("text/") || mime.contains("subtitle") || mime.contains("tx3g") -> subtitles++
                }
            }
            return SourceInfo(size, durationMs, videoMime, width, height, frameRate, colorTransfer, audio, subtitles)
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun describe(info: SourceInfo): String = buildString {
        append("Quelle: ${info.width}x${info.height} ${info.videoMime}, ")
        append(String.format(Locale.GERMANY, "%.2f fps, ", info.frameRate))
        append("${formatMb(info.sizeBytes)}, ${formatDuration(info.durationMs)}, ")
        appendLine("Gesamtbitrate ${formatMbit(info.overallBitrate)}")
        appendLine(
            "Audio-Spuren: ${info.audioMimes.size} ${info.audioMimes}, Untertitel-Spuren: ${info.subtitleCount}, " +
                "Farbübertragung: ${info.colorTransfer ?: "unbekannt"} (6 = HDR PQ, 7 = HDR HLG)",
        )
    }.trimEnd()

    private suspend fun transcode(
        uri: Uri,
        outFile: File,
        targetBitrate: Int,
        clipMs: Long?,
        onProgress: (Int) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val mediaItemBuilder = MediaItem.Builder().setUri(uri)
            if (clipMs != null) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipMs).build(),
                )
            }
            val edited = EditedMediaItem.Builder(mediaItemBuilder.build()).build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(targetBitrate).build())
                .setEnableFallback(false)
                .build()
            val handler = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeTransformer = null
                        if (continuation.isActive) continuation.resume(exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        activeTransformer = null
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            activeTransformer = transformer
            continuation.invokeOnCancellation {
                handler.post {
                    transformer.cancel()
                    activeTransformer = null
                }
            }
            transformer.start(edited, outFile.absolutePath)

            val holder = ProgressHolder()
            val poller = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        onProgress(holder.progress)
                    }
                    handler.postDelayed(this, 500)
                }
            }
            handler.post(poller)
        }
    }
}
