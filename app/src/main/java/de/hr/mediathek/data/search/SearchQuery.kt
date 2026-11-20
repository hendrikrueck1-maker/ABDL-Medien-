package de.hr.mediathek.data.search

/** Übersetzte Sucheingabe: [match] ist ein FTS-Ausdruck (alle Begriffe verknüpft), [excluded] sind Ausschlüsse (je ein FTS-Ausdruck). */
data class ParsedQuery(val match: String, val excluded: List<String>)

/**
 * Wandelt Nutzereingaben in sichere FTS4-Ausdrücke um.
 * Begriffe sind UND-verknüpft, "Phrase" in Anführungszeichen, -Wort schließt aus, Wörter mit Bindestrich zählen als ein Begriff
 * (Phrase), Sonderzeichen werden neutralisiert, einfache Begriffe suchen als Wortanfang (Präfix).
 */
object SearchQuery {
    fun parse(input: String): ParsedQuery? {
        val positive = ArrayList<String>()
        val negative = ArrayList<String>()
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || (c == '-' && i + 1 < n && input[i + 1] == '"') -> {
                    val exclude = c == '-'
                    val start = if (exclude) i + 2 else i + 1
                    val end = input.indexOf('"', start).let { if (it < 0) n else it }
                    val parts = words(input.substring(start, end))
                    i = (end + 1).coerceAtMost(n).let { if (end >= n) n else it }
                    val frag = when (parts.size) {
                        0 -> null
                        1 -> parts[0]
                        else -> "\"" + parts.joinToString(" ") + "\""
                    }
                    if (frag != null) (if (exclude) negative else positive) += frag
                }
                else -> {
                    var end = i
                    while (end < n && !input[end].isWhitespace() && input[end] != '"') end++
                    var raw = input.substring(i, end)
                    i = end
                    val exclude = raw.startsWith("-")
                    if (exclude) raw = raw.drop(1)
                    val parts = words(raw)
                    val frag = when (parts.size) {
                        0 -> null
                        1 -> parts[0] + "*"
                        else -> "\"" + parts.joinToString(" ") + "\""
                    }
                    if (frag != null) (if (exclude) negative else positive) += frag
                }
            }
        }
        if (positive.isEmpty()) return null
        return ParsedQuery(positive.joinToString(" "), negative)
    }

    /** Zerlegt in Buchstaben-/Ziffernfolgen, alles andere (auch FTS-Sonderzeichen) wird zum Trenner. Kleingeschrieben, damit kein Operator wie OR/NOT entsteht. */
    private fun words(text: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch.lowercaseChar()) else if (sb.isNotEmpty()) { result += sb.toString(); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) result += sb.toString()
        return result
    }
}
