package de.hr.mediathek.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactorTest {
    @Test fun fileNamesAreHidden() {
        val r = Redactor.redact("Quelle nicht lesbar: Mein Film.mp4 und foto_01.JPG")
        assertFalse(r.contains("foto_01"))
        assertFalse(r.contains(".mp4"))
    }

    @Test fun uriAndPathsAreHidden() {
        assertEquals("Fehler bei <adresse> ende", Redactor.redact("Fehler bei content://com.android.externalstorage.documents/tree/1234%3A ende"))
        assertEquals("Ordner <pfad>", Redactor.redact("Ordner /storage/1234-5678/Videos/abc"))
        assertEquals("Wurzel <pfad>", Redactor.redact("Wurzel 1234-ABCD:Videos/a"))
    }

    @Test fun plainTextStays() {
        assertEquals("Größe stimmt nach dem Kopieren nicht", Redactor.redact("Größe stimmt nach dem Kopieren nicht"))
    }
}
