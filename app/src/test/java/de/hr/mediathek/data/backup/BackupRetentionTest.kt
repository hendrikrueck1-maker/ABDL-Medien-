package de.hr.mediathek.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BackupRetentionTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(date: LocalDate, hour: Int = 12): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun leer_nichtsBehalten() {
        assertTrue(BackupRetention.keep(emptyList(), zone).isEmpty())
    }

    @Test
    fun einBackup_bleibt() {
        val t = at(LocalDate.of(2026, 11, 19))
        assertEquals(setOf(t), BackupRetention.keep(listOf(t), zone))
    }

    @Test
    fun mehrereAmGleichenTag_nurDasNeuesteZaehlt() {
        val day = LocalDate.of(2026, 11, 19)
        val morning = at(day, 6)
        val evening = at(day, 20)
        val keep = BackupRetention.keep(listOf(morning, evening), zone)
        assertTrue(evening in keep)
        assertFalse(morning in keep)
    }

    @Test
    fun taeglich_nurDieLetzten14Tage_dannWoechentlich() {
        val start = LocalDate.of(2026, 11, 19)
        val all = (0 until 60).map { at(start.minusDays(it.toLong())) }
        val keep = BackupRetention.keep(all, zone)
        // alle der letzten 14 Tage bleiben
        (0 until 14).forEach { assertTrue(at(start.minusDays(it.toLong())) in keep) }
        // der Tag 20 vor heute ist nur noch behalten, wenn er das neueste seiner Woche oder seines Monats ist
        assertTrue(keep.size < all.size)
        assertTrue(keep.size >= 14 + 6)
    }

    @Test
    fun generationenZaehlenBackups_nichtKalenderzeit_seltenerAltesBleibt() {
        val start = LocalDate.of(2026, 11, 19)
        val old = at(start.minusYears(3))
        val keep = BackupRetention.keep(listOf(at(start), old), zone)
        // Es gibt nur zwei Monats-Generationen, also bleibt auch das alte (nichts wird ohne Grund gelöscht).
        assertTrue(old in keep)
    }

    @Test
    fun mehrAlsZwoelfMonate_aeltesteFaelltRaus() {
        val start = LocalDate.of(2026, 11, 19)
        // 13 Monats-Backups, je ein Jahr-Monat-Abstand von 40 Tagen, damit Tages-Limit nicht überall greift
        val all = (0 until 40).map { at(start.minusDays((it * 40).toLong())) }
        val keep = BackupRetention.keep(all, zone)
        assertFalse(all.last() in keep)
    }

    @Test
    fun einBackupProMonat_dieNeuesten14BleibenWeilJederTagEinzelnZaehlt() {
        val start = LocalDate.of(2026, 11, 15)
        val all = (0 until 20).map { at(start.minusMonths(it.toLong())) }
        val keep = BackupRetention.keep(all, zone)
        assertEquals(14, keep.size)
        assertFalse(all.last() in keep)
    }

    @Test
    fun einBackupAlle10Wochen_bleibenBisZumMonatslimit() {
        val start = LocalDate.of(2026, 11, 15)
        val all = (0 until 12).map { at(start.minusWeeks((it * 10).toLong())) }
        val keep = BackupRetention.keep(all, zone)
        // Tage: alle 12 liegen auf verschiedenen Tagen -> die neuesten 12 sind innerhalb von 14 Tagen-Limit behalten
        assertEquals(12, keep.size)
    }
}
