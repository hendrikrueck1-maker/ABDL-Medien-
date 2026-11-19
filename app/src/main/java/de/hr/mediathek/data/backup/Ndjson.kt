package de.hr.mediathek.data.backup

import java.util.Base64

/** Schreibt einzelne Datenzeilen als JSON (eine Zeile je Datensatz). Bewusst ohne org.json, damit es testbar bleibt. */
object Ndjson {
    sealed interface Cell {
        data object Null : Cell
        data class Int(val value: Long) : Cell
        data class Real(val value: Double) : Cell
        data class Text(val value: String) : Cell
        class Blob(val value: ByteArray) : Cell
    }

    fun encodeRow(columns: List<String>, cells: List<Cell>): String {
        val sb = StringBuilder(128)
        sb.append('{')
        for (i in columns.indices) {
            if (i > 0) sb.append(',')
            quote(columns[i], sb)
            sb.append(':')
            when (val c = cells[i]) {
                Cell.Null -> sb.append("null")
                is Cell.Int -> sb.append(c.value)
                is Cell.Real -> if (c.value.isFinite()) sb.append(c.value) else sb.append("null")
                is Cell.Text -> quote(c.value, sb)
                // Blobs sind selten (aktuell keine); sie werden als Objekt mit Base64 gekennzeichnet.
                is Cell.Blob -> sb.append("{\"\$b64\":\"").append(Base64.getEncoder().encodeToString(c.value)).append("\"}")
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
