package de.hr.mediathek.data.story.web

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Ein erkanntes Datum. [relative] = Quelle nannte nur "Heute"/"Gestern"; das Datum wurde aus dem Abrufdatum berechnet. */
data class WebDate(val millis: Long, val relative: Boolean = false, val hasTime: Boolean = true)

/** Liest Datumsangaben von Webseiten und Foren (deutsches Format, ISO, "Heute"/"Gestern"). */
object WebDates {
    private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    private val RELATIVE = Regex("""(?i)\b(heute|gestern)\b[\s,]*(?:um\s*)?(\d{1,2}):(\d{2})""")
    private val GERMAN = Regex("""\b(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4})(?:[\s,]*(?:um\s*)?(\d{1,2}):(\d{2}))?""")
    /** Englische Foren (vBulletin mit englischer Sprachdatei): "05-12-2004, 07:04 PM" = Monat-Tag-Jahr. */
    private val US_DATE = Regex("""\b(\d{1,2})-(\d{1,2})-(\d{4})(?:[\s,]*(\d{1,2}):(\d{2})\s*([AaPp][Mm])?)?""")
    private val ISO_DATE = Regex("""\b(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2}))?""")

    /** Erste Datumsangabe im Text, sonst null. [today] nur für Tests änderbar. */
    fun findIn(text: String, today: LocalDate = LocalDate.now(ZONE)): WebDate? {
        RELATIVE.find(text)?.let { m ->
            val day = if (m.groupValues[1].equals("heute", true)) today else today.minusDays(1)
            return atZone(day, m.groupValues[2].toInt(), m.groupValues[3].toInt(), true)
        }
        GERMAN.find(text)?.let { m ->
            val date = safeDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) ?: return@let
            return if (m.groupValues[4].isNotEmpty()) {
                atZone(date, m.groupValues[4].toInt(), m.groupValues[5].toInt(), false)
            } else {
                dateOnly(date)
            }
        }
        US_DATE.find(text)?.let { m ->
            val date = safeDate(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt()) ?: return@let
            if (m.groupValues[4].isEmpty()) return dateOnly(date)
            var hour = m.groupValues[4].toInt()
            val ampm = m.groupValues[6].lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return atZone(date, hour, m.groupValues[5].toInt(), false)
        }
        ISO_DATE.find(text)?.let { m ->
            val date = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: return@let
            return if (m.groupValues[4].isNotEmpty()) {
                atZone(date, m.groupValues[4].toInt(), m.groupValues[5].toInt(), false)
            } else {
                dateOnly(date)
            }
        }
        return null
    }

    /** Maschinenlesbares Datum (datetime-Attribut, Meta-Tag, JSON-LD). */
    fun parseMachine(value: String?): WebDate? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        runCatching { return WebDate(OffsetDateTime.parse(s).toInstant().toEpochMilli()) }
        runCatching { return WebDate(LocalDateTime.parse(s).atZone(ZONE).toInstant().toEpochMilli()) }
        runCatching { return dateOnly(LocalDate.parse(s)) }
        return findIn(s)
    }

    /** Eingabe aus dem Vorschau-Feld: "12.05.2004" oder "12.05.2004 19:04" (leer = kein Datum). */
    fun parseInput(text: String): WebDate? = findIn(text.trim())?.takeIf { !it.relative }

    fun format(millis: Long?): String {
        if (millis == null) return ""
        val t = java.time.Instant.ofEpochMilli(millis).atZone(ZONE)
        // Genau Mitternacht = nur ein Datum bekannt: ohne „00:00“ anzeigen.
        if (t.hour == 0 && t.minute == 0) return t.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        return t.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }

    fun formatDay(millis: Long?): String {
        if (millis == null) return ""
        return java.time.Instant.ofEpochMilli(millis).atZone(ZONE).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (e: DateTimeParseException) {
        null
    } catch (e: java.time.DateTimeException) {
        null
    }

    private fun atZone(date: LocalDate, hour: Int, minute: Int, relative: Boolean): WebDate? = try {
        WebDate(LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(ZONE).toInstant().toEpochMilli(), relative, true)
    } catch (e: java.time.DateTimeException) {
        null
    }

    private fun dateOnly(date: LocalDate) = WebDate(date.atStartOfDay(ZONE).toInstant().toEpochMilli(), false, false)
}
