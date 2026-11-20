package de.hr.mediathek.data.story

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProcessingTest {
    @Test fun detectsUtf8WithUmlauts() {
        assertEquals(TextEncoding.UTF8, TextDecoder.detect("Grüße".toByteArray(Charsets.UTF_8)))
    }

    @Test fun detectsWindows1252WhenNotUtf8() {
        val bytes = "Grüße".toByteArray(java.nio.charset.Charset.forName("windows-1252"))
        val enc = TextDecoder.detect(bytes)
        assertEquals(TextEncoding.WINDOWS_1252, enc)
        assertEquals("Grüße", TextDecoder.decode(bytes, enc))
    }

    @Test fun detectsUtf16Bom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hi".toByteArray(Charsets.UTF_16LE)
        assertEquals(TextEncoding.UTF16, TextDecoder.detect(bytes))
        assertEquals("Hi", TextDecoder.decode(bytes, TextEncoding.UTF16))
    }

    @Test fun smoothJoinsLinesAndHyphenation() {
        val out = TextCleaner.smooth("Das ist ein Bei-\nspiel mit\numbrochenen Zeilen.\n\nNeuer Absatz.")
        assertEquals("Das ist ein Beispiel mit umbrochenen Zeilen.\n\nNeuer Absatz.", out)
    }

    @Test fun hyphenBeforeUppercaseIsKept() {
        assertEquals("Nord- Süd", TextCleaner.smooth("Nord-\nSüd"))
    }

    @Test fun removesRepeatingHeadersAndPageNumbers() {
        val pages = (1..4).joinToString("\u000C") { "Kopfzeile\nInhalt $it\nSeite $it" }
        val r = TextCleaner.clean(pages, CleanOptions(smoothLines = false, removeRepeating = true))
        assertFalse(r.text.contains("Kopfzeile"))
        assertFalse(r.text.contains("Seite 2"))
        assertTrue(r.text.contains("Inhalt 3"))
        assertTrue(r.removedLines.contains("Kopfzeile"))
    }

    @Test fun keepsRepeatingWhenOptionOff() {
        val pages = (1..4).joinToString("\u000C") { "Kopfzeile\nInhalt $it" }
        val r = TextCleaner.clean(pages, CleanOptions(smoothLines = false, removeRepeating = false))
        assertTrue(r.text.contains("Kopfzeile"))
    }

    @Test fun hashIgnoresCaseAndWhitespace() {
        assertEquals(TextCleaner.hash("Ein  Text\nhier"), TextCleaner.hash("ein text hier"))
    }

    @Test fun paragraphsSplitOnBlankLines() {
        assertEquals(listOf("A", "B"), TextCleaner.paragraphs("A\n\nB"))
        assertEquals(listOf("A", "B"), TextCleaner.paragraphs("A\nB"))
    }

    @Test fun docxTextIsExtracted() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Hallo &amp; Welt</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t xml:space=\"preserve\">Zweiter</w:t></w:r></w:p></w:body></w:document>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
        }
        assertEquals("Hallo & Welt\n\nZweiter", DocxReader.read(bos.toByteArray()))
    }

    @Test(expected = DocxRejected::class) fun docxWithoutDocumentIsRejected() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("other.txt")); z.write(1); z.closeEntry() }
        DocxReader.read(bos.toByteArray())
    }
}
