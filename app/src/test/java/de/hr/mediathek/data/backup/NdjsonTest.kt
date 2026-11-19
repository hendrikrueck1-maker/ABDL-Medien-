package de.hr.mediathek.data.backup

import de.hr.mediathek.data.backup.Ndjson.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

class NdjsonTest {
    @Test
    fun grundtypen() {
        val line = Ndjson.encodeRow(
            listOf("a", "b", "c", "d"),
            listOf(Cell.Int(5), Cell.Text("x"), Cell.Null, Cell.Real(1.5)),
        )
        assertEquals("{\"a\":5,\"b\":\"x\",\"c\":null,\"d\":1.5}", line)
    }

    @Test
    fun sonderzeichenWerdenMaskiert() {
        val line = Ndjson.encodeRow(listOf("t"), listOf(Cell.Text("Zeile1\nZeile \"2\"\\\u0001 äöü")))
        assertEquals("{\"t\":\"Zeile1\\nZeile \\\"2\\\"\\\\\\u0001 äöü\"}", line)
    }

    @Test
    fun einZeilenFormat_keinUmbruchImErgebnis() {
        val line = Ndjson.encodeRow(listOf("t"), listOf(Cell.Text("a\r\nb")))
        assertEquals(false, line.contains('\n'))
        assertEquals(false, line.contains('\r'))
    }

    @Test
    fun unendlicheZahlWirdNull() {
        val line = Ndjson.encodeRow(listOf("x"), listOf(Cell.Real(Double.NaN)))
        assertEquals("{\"x\":null}", line)
    }
}
