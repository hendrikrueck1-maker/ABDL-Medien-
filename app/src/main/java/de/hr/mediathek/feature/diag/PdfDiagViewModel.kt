package de.hr.mediathek.feature.diag

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * Spike: liest den Rohtext einer PDF seitenweise mit PdfBox-Android und zählt Bilder je Seite.
 * Der Text bleibt auf dem Gerät und wird nur angezeigt bzw. auf Wunsch in die Zwischenablage kopiert.
 */
@HiltViewModel
class PdfDiagViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state.asStateFlow()

    private val _sortByPosition = MutableStateFlow(false)
    val sortByPosition: StateFlow<Boolean> = _sortByPosition.asStateFlow()

    fun setSortByPosition(enabled: Boolean) { _sortByPosition.value = enabled }

    fun start(uri: Uri) {
        _state.value = DiagUiState(running = true, progress = "Lese PDF …")
        viewModelScope.launch {
            _state.value = try {
                DiagUiState(result = withContext(Dispatchers.IO) { analyze(uri, _sortByPosition.value) })
            } catch (e: Exception) {
                DiagUiState(result = "Fehler: ${e::class.java.simpleName}: ${e.message}")
            } catch (e: OutOfMemoryError) {
                DiagUiState(result = "Fehler: Speicher reicht nicht (OutOfMemory)")
            }
        }
    }

    private fun analyze(uri: Uri, sort: Boolean): String {
        PDFBoxResourceLoader.init(context)
        val runtime = Runtime.getRuntime()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()
        val start = SystemClock.elapsedRealtime()

        val stream = context.contentResolver.openInputStream(uri) ?: error("PDF nicht lesbar")
        val document = stream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
        return document.use { doc ->
            val pages = doc.numberOfPages
            val report = StringBuilder()
            report.appendLine("PDF-Rohtext-Test (PdfBox-Android)")
            report.appendLine(deviceInfoLine())
            report.appendLine("Seiten: $pages, Sortierung nach Position: $sort")
            report.appendLine()
            var totalChars = 0
            var totalImages = 0
            for (pageNumber in 1..pages) {
                val stripper = PDFTextStripper().apply {
                    this.sortByPosition = sort
                    startPage = pageNumber
                    endPage = pageNumber
                }
                val text = stripper.getText(doc)
                var images = 0
                doc.getPage(pageNumber - 1).resources?.let { resources ->
                    for (name in resources.xObjectNames) {
                        if (resources.isImageXObject(name)) images++
                    }
                }
                totalChars += text.length
                totalImages += images
                val lines = text.lines().size
                report.appendLine("--- Seite $pageNumber: ${text.length} Zeichen, $lines Zeilen, $images Bilder ---")
                report.appendLine(if (text.length > MAX_PAGE_CHARS) text.take(MAX_PAGE_CHARS) + "\n[… gekürzt]" else text)
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            report.appendLine()
            report.appendLine(
                String.format(
                    Locale.GERMANY,
                    "Summe: %d Zeichen, %d Bilder, Dauer %.1f s, Speicherzuwachs ca. %.0f MB",
                    totalChars,
                    totalImages,
                    elapsed / 1000.0,
                    (memAfter - memBefore) / 1_048_576.0,
                ),
            )
            report.toString()
        }
    }

    private companion object {
        const val MAX_PAGE_CHARS = 4000
    }
}
