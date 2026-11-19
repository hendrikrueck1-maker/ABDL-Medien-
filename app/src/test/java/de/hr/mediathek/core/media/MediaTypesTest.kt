package de.hr.mediathek.core.media

import de.hr.mediathek.data.db.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesTest {
    @Test
    fun detectsByMime() {
        assertEquals(MediaType.VIDEO, MediaTypes.detect("video/mp4", "x.bin"))
        assertEquals(MediaType.IMAGE, MediaTypes.detect("image/jpeg", "x.bin"))
    }

    @Test
    fun fallsBackToExtensionForUnknownMime() {
        assertEquals(MediaType.VIDEO, MediaTypes.detect("application/octet-stream", "Film.MKV"))
        assertEquals(MediaType.IMAGE, MediaTypes.detect(null, "foto.HEIC"))
    }

    @Test
    fun ignoresOtherFiles() {
        assertNull(MediaTypes.detect("text/plain", "notiz.txt"))
        assertNull(MediaTypes.detect("application/octet-stream", "daten.bin"))
    }

    @Test
    fun ignoresTemporaryAndHiddenEntries() {
        assertTrue(MediaTypes.isIgnoredFile("video.mp4.tmp"))
        assertTrue(MediaTypes.isIgnoredFile("video.mp4.part"))
        assertTrue(MediaTypes.isIgnoredFile(".nomedia"))
        assertFalse(MediaTypes.isIgnoredFile("video.mp4"))
        assertTrue(MediaTypes.isIgnoredDir(".trash"))
        assertTrue(MediaTypes.isIgnoredDir("_alt"))
        assertFalse(MediaTypes.isIgnoredDir("Urlaub"))
    }
}
