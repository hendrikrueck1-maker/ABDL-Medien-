package de.hr.mediathek.data.story

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Wählbare Zeichenkodierungen beim Textimport. */
enum class TextEncoding(val label: String, val charset: Charset) {
    UTF8("UTF-8", Charsets.UTF_8),
    WINDOWS_1252("Windows-1252", Charset.forName("windows-1252")),
    ISO_8859_1("ISO-8859-1", Charsets.ISO_8859_1),
    UTF16("UTF-16", Charsets.UTF_16),
}

object TextDecoder {
    /** Erkennt die Kodierung: BOM, sonst gültiges UTF-8, sonst Windows-1252. */
    fun detect(bytes: ByteArray): TextEncoding {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return TextEncoding.UTF16
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return TextEncoding.UTF8
        }
        return if (isValidUtf8(bytes)) TextEncoding.UTF8 else TextEncoding.WINDOWS_1252
    }

    fun decode(bytes: ByteArray, encoding: TextEncoding): String {
        var text = String(bytes, encoding.charset)
        // BOM am Anfang entfernen
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return text
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}

data class CleanOptions(val smoothLines: Boolean = true, val removeRepeating: Boolean = true)

data class CleanResult(val text: String, val removedLines: List<String>)

object TextCleaner {
    private val PAGE_NUMBER = Regex("^\\s*(?:-\\s*)?(?:seite|page|s\\.)?\\s*\\d{1,4}(?:\\s*(?:/|von|of)\\s*\\d{1,4})?\\s*(?:-\\s*)?$", RegexOption.IGNORE_CASE)

    /** Vereinheitlicht Zeilenenden und entfernt Steuerzeichen. */
    fun normalize(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace(" ", " ")
            .filter { it == '\n' || it == '\t' || it == '\u000C' || !it.isISOControl() }

    /**
     * Bereinigt Text. Seiten werden durch Form Feed (\u000C) getrennt, wenn der Text aus einem PDF stammt;
     * wiederkehrende Kopf- und Fußzeilen sowie Seitenzahlen werden entfernt und in [CleanResult.removedLines] gemeldet.
     */
    fun clean(raw: String, options: CleanOptions): CleanResult {
        var text = normalize(raw)
        val removed = ArrayList<String>()
        val pages = text.split('\u000C')
        if (options.removeRepeating) {
            val repeating = findRepeating(pages)
            text = pages.joinToString("\n") { page ->
                page.lines().filter { line ->
                    val t = line.trim()
                    val drop = t.isNotEmpty() && (t in repeating || (pages.size > 1 && PAGE_NUMBER.matches(t)))
                    if (drop) removed += t
                    !drop
                }.joinToString("\n")
            }
        } else {
            text = pages.joinToString("\n")
        }
        if (options.smoothLines) text = smooth(text)
        return CleanResult(collapseBlankLines(text).trim(), removed)
    }

    /** Kurze Zeilen, die am Anfang oder Ende von mindestens der Hälfte der Seiten (mindestens 3) stehen. */
    internal fun findRepeating(pages: List<String>): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        for (page in pages) {
            val lines = page.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val edge = (lines.take(2) + lines.takeLast(2)).toSet()
            for (l in edge) if (l.length <= 80 && !PAGE_NUMBER.matches(l)) counts[l] = (counts[l] ?: 0) + 1
        }
        val need = maxOf(3, (pages.size + 1) / 2)
        return counts.filterValues { it >= need }.keys
    }

    /** Fügt hart umbrochene Zeilen eines Absatzes zusammen und verbindet Silbentrennung am Zeilenende. */
    fun smooth(text: String): String {
        val out = StringBuilder()
        val paragraphs = text.split(Regex("\n{2,}"))
        paragraphs.forEachIndexed { index, para ->
            val lines = para.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val sb = StringBuilder()
            for ((i, line) in lines.withIndex()) {
                if (i == 0) {
                    sb.append(line)
                    continue
                }
                val prev = sb
                if (prev.endsWith("-") && prev.length >= 2 && prev[prev.length - 2].isLetter() &&
                    line.first().isLowerCase()
                ) {
                    prev.setLength(prev.length - 1)
                    sb.append(line)
                } else {
                    sb.append(' ').append(line)
                }
            }
            out.append(sb)
            if (index < paragraphs.lastIndex) out.append("\n\n")
        }
        return out.toString()
    }

    fun collapseBlankLines(text: String): String = text.replace(Regex("\n{3,}"), "\n\n")

    /** Absätze für die Anzeige: durch Leerzeilen getrennt, sonst Zeile für Zeile. */
    fun paragraphs(text: String): List<String> {
        val byBlank = text.split(Regex("\n\\s*\n")).map { it.trim('\n', ' ') }.filter { it.isNotEmpty() }
        return if (byBlank.size > 1) byBlank else text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Fingerabdruck des Inhalts, unabhängig von Groß-/Kleinschreibung und Leerraum, zur Duplikat-Warnung. */
    fun hash(text: String): String {
        val norm = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val md = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }
}

class DocxRejected(message: String) : Exception(message)

/** Liest Text aus word/document.xml, ohne die Datei zu entpacken (kein Pfad außerhalb des Archivs, Größenlimit). */
object DocxReader {
    const val MAX_XML_BYTES = 30L * 1024 * 1024

    fun read(bytes: ByteArray): String {
        var xml: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_XML_BYTES) throw DocxRejected("Die Word-Datei ist zu groß")
                        buf.write(chunk, 0, n)
                    }
                    xml = buf.toString("UTF-8")
                    break
                }
            }
        }
        return extractText(xml ?: throw DocxRejected("Keine gültige Word-Datei (.docx)"))
    }

    private val TOKEN = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|<w:tab\\s*/>|<w:br\\s*/>|<w:cr\\s*/>|</w:p>")

    fun extractText(xml: String): String {
        val sb = StringBuilder()
        for (m in TOKEN.findAll(xml)) {
            val v = m.value
            when {
                v.startsWith("<w:t") && !v.startsWith("<w:tab") -> sb.append(unescape(m.groupValues[1]))
                v.startsWith("<w:tab") -> sb.append('\t')
                v == "</w:p>" -> sb.append("\n\n")
                else -> sb.append('\n')
            }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { codePoint(it.groupValues[1].toIntOrNull()) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { codePoint(it.groupValues[1].toIntOrNull(16)) }
        .replace("&amp;", "&")

    /** Zeichenreferenz als Text, auch Emoji (über 0xFFFF); ungültige Werte werden zum Ersatzzeichen. */
    private fun codePoint(cp: Int?): String =
        if (cp != null && Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else "\uFFFD"
}
