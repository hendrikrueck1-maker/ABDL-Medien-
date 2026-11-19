package de.hr.mediathek.feature.diag

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

/**
 * Spike: misst, wie schnell sich ein SAF-Ordner (SD-Karte) auflisten lässt
 * und wie lange das Lesen des Fingerabdrucks (Größe + 3 x 64 KB) pro Datei dauert.
 */
@HiltViewModel
class ScanDiagViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(treeUri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = DiagUiState(running = true, progress = "Starte …")
            try {
                val report = withContext(Dispatchers.IO) { scan(treeUri) }
                _state.value = DiagUiState(result = report)
            } catch (e: CancellationException) {
                _state.value = DiagUiState(result = "Abgebrochen.")
                throw e
            } catch (e: Exception) {
                _state.value = DiagUiState(result = "Fehler: $e")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun scan(treeUri: Uri): String {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(rootId to 0)
        var dirs = 0
        var files = 0
        var videos = 0
        var images = 0
        var bytes = 0L
        var maxDepth = 0
        var entries = 0
        val samples = ArrayList<Pair<String, Long>>()

        val listStart = SystemClock.elapsedRealtime()
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (parentId, depth) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val mime = cursor.getString(1) ?: ""
                    val size = cursor.getLong(2)
                    entries++
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs++
                        stack.addLast(id to depth + 1)
                        if (depth + 1 > maxDepth) maxDepth = depth + 1
                    } else {
                        files++
                        bytes += size
                        val isVideo = mime.startsWith("video/")
                        val isImage = mime.startsWith("image/")
                        if (isVideo) videos++
                        if (isImage) images++
                        if ((isVideo || isImage) && size >= 3 * CHUNK) samples.add(id to size)
                    }
                    if (entries % 500 == 0) {
                        _state.value = DiagUiState(
                            running = true,
                            progress = "Liste: $entries Einträge ($files Dateien, $dirs Ordner)",
                        )
                    }
                }
            }
        }
        val listMs = SystemClock.elapsedRealtime() - listStart

        // Fingerabdruck-Messung an bis zu 200 gleichmäßig verteilten Medien-Dateien
        val sampleCount = minOf(200, samples.size)
        val picked = if (sampleCount == 0) emptyList()
        else List(sampleCount) { samples[(it.toLong() * samples.size / sampleCount).toInt()] }
        val perFileMs = ArrayList<Long>()
        var failures = 0
        var readBytes = 0L
        val fpStart = SystemClock.elapsedRealtime()
        for ((index, item) in picked.withIndex()) {
            currentCoroutineContext().ensureActive()
            _state.value = DiagUiState(
                running = true,
                progress = "Fingerabdruck-Test ${index + 1} von ${picked.size}",
            )
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.first)
            val t0 = SystemClock.elapsedRealtime()
            try {
                readBytes += fingerprint(docUri, item.second)
                perFileMs.add(SystemClock.elapsedRealtime() - t0)
            } catch (e: Exception) {
                failures++
            }
        }
        val fpTotalMs = SystemClock.elapsedRealtime() - fpStart

        val mediaCount = videos + images
        val avgMs = if (perFileMs.isEmpty()) 0.0 else perFileMs.average()
        val sorted = perFileMs.sorted()
        val p95 = if (sorted.isEmpty()) 0L else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        val maxMs = sorted.lastOrNull() ?: 0L
        val fpMbPerS = if (fpTotalMs > 0) readBytes / 1_048_576.0 / (fpTotalMs / 1000.0) else 0.0
        val estFpMin = avgMs * mediaCount / 1000.0 / 60.0
        val listPerSec = if (listMs > 0) entries * 1000.0 / listMs else 0.0

        return buildString {
            appendLine("SAF-Scan-Messung")
            appendLine(deviceInfoLine())
            appendLine()
            appendLine("Ordner: $dirs, Dateien: $files (Videos $videos, Bilder $images)")
            appendLine("Größe aller Dateien: ${formatMb(bytes)}")
            appendLine("Tiefste Ebene: $maxDepth")
            appendLine()
            appendLine("Auflisten: ${listMs / 1000.0} s für $entries Einträge")
            appendLine(String.format(Locale.GERMANY, "Auflisten: %.0f Einträge/s", listPerSec))
            appendLine()
            appendLine("Fingerabdruck-Test: ${picked.size} Dateien, $failures Fehler")
            appendLine(String.format(Locale.GERMANY, "Pro Datei: Ø %.0f ms, 95%% unter %d ms, Max %d ms", avgMs, p95, maxMs))
            appendLine(String.format(Locale.GERMANY, "Lesetempo beim Fingerabdruck: %.1f MB/s", fpMbPerS))
            appendLine()
            appendLine(
                String.format(
                    Locale.GERMANY,
                    "Hochrechnung Fingerabdruck für alle %d Medien: ca. %.1f min",
                    mediaCount,
                    estFpMin,
                ),
            )
        }
    }

    /** Liest Anfang, Mitte und Ende (je 64 KB) und bildet einen Hash. Gibt die gelesenen Bytes zurück. */
    private fun fingerprint(docUri: Uri, size: Long): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openFileDescriptor(docUri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val offsets = longArrayOf(0L, (size / 2 - CHUNK / 2).coerceAtLeast(0L), (size - CHUNK).coerceAtLeast(0L))
                val buffer = ByteBuffer.allocate(CHUNK.toInt())
                for (offset in offsets) {
                    buffer.clear()
                    channel.position(offset)
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) break
                    }
                    digest.update(buffer.array(), 0, buffer.position())
                    total += buffer.position()
                }
            }
        } ?: error("Datei nicht lesbar")
        digest.digest()
        return total
    }

    private companion object {
        const val CHUNK = 64L * 1024L
    }
}
