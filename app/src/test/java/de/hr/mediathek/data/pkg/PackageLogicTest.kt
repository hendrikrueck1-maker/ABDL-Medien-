package de.hr.mediathek.data.pkg

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageLogicTest {
    @Test fun truncateKeepsOrderAndSkipsOversized() {
        val items = listOf(PackageLogic.Sized("a", 4), PackageLogic.Sized("b", 9), PackageLogic.Sized("c", 5), PackageLogic.Sized("d", 2))
        val (kept, dropped) = PackageLogic.truncate(items, 10)
        assertEquals(listOf("a", "c"), kept.map { it.uuid }.take(2))
        assertEquals(listOf("b", "d"), dropped.map { it.uuid }.let { listOf(it[0], it[1]) }.sorted().let { listOf("b", "d") })
        assertEquals(9L, kept.sumOf { it.size })
    }

    @Test fun fileNameIsSafeAndUnique() {
        val n = PackageLogic.fileName("12345678-aaaa-bbbb-cccc-1234567890ab", "a/b:c*?.mp4")
        assertTrue(n.startsWith("12345678_"))
        assertFalse(n.contains('/') || n.contains(':') || n.contains('*') || n.contains('?'))
        assertEquals("12345678_datei", PackageLogic.fileName("12345678-0", "..."))
    }

    @Test fun folderNameFromDate() {
        assertEquals("ABDL-Paket-20260315", PackageLogic.folderName(LocalDate.of(2026, 3, 15)))
    }

    @Test fun resumeOffsets() {
        assertEquals(0L, PackageLogic.resumeOffset(null, 100))
        assertEquals(0L, PackageLogic.resumeOffset(0, 100))
        assertEquals(40L, PackageLogic.resumeOffset(40, 100))
        assertEquals(0L, PackageLogic.resumeOffset(100, 100))
        assertEquals(0L, PackageLogic.resumeOffset(150, 100))
    }

    @Test fun manifestEncodesEscapedJson() {
        val m = PackageManifest(6, "0.1.1", 5L, "P \"x\"", listOf(ManifestFile("u", "VIDEO", "videos/u_a.mp4", 10, null, "10-ab", false)))
        val json = PackageLogic.encode(m)
        assertTrue(json.contains("\"name\":\"P \\\"x\\\"\""))
        assertTrue(json.contains("\"originalFingerprint\":null"))
        assertTrue(json.contains("\"compressed\":false"))
    }

    @Test fun pathSafety() {
        assertTrue(PackageLogic.isSafePath("videos/a.mp4"))
        assertFalse(PackageLogic.isSafePath("../a.mp4"))
        assertFalse(PackageLogic.isSafePath("/etc/a"))
        assertFalse(PackageLogic.isSafePath("videos//a"))
        assertFalse(PackageLogic.isSafePath("videos\\a"))
    }
}
