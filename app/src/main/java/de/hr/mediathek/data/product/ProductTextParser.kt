package de.hr.mediathek.data.product

import de.hr.mediathek.data.db.CriterionValueType

class ParsedCriterion(val key: String, var number: Int? = null, var text: String? = null, var note: String? = null)

class UnassignedLine(val label: String, val number: Int?, val note: String?)

class ParsedProduct(
    val title: String,
    val criteria: List<ParsedCriterion>,
    val unassigned: List<UnassignedLine>,
)

/**
 * Liest den extrahierten Text einer Bewertung anhand der bekannten Kriterienliste (nicht anhand von Positionen).
 * Mehrzeilige Anmerkungen gehören zum vorherigen Kriterium; Unbekanntes landet in [ParsedProduct.unassigned].
 */
object ProductTextParser {
    private val HEADER_WORDS = Regex("(?i)^(kriterium|bewertung\\s*\\(1-10\\)|anmerkung)(\\s+(bewertung|anmerkung).*)?$")
    private val NUMBER_FIRST = Regex("^(10|[1-9])(?:\\s+(.*))?$")
    private val UNKNOWN = Regex("^(\\p{L}[\\p{L}\\p{N} ()/.,\\-?]{2,45}?)\\s+(10|[1-9])(?:\\s+(.*))?$")

    fun parse(text: String, defs: List<CriterionDef> = CriterionCatalog.defaults): ParsedProduct {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n').lines().map { it.trim() }
        val title = lines.firstOrNull { it.isNotEmpty() } ?: ""
        val byKey = LinkedHashMap<String, ParsedCriterion>()
        val unassigned = ArrayList<UnassignedLine>()
        val sorted = defs.sortedByDescending { it.label.length }
        var current: ParsedCriterion? = null
        var currentDef: CriterionDef? = null
        var currentUnknown: Int? = null
        val unknownLabels = ArrayList<String>()
        val unknownNums = ArrayList<Int?>()
        val unknownNotes = ArrayList<StringBuilder>()

        var started = false
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (HEADER_WORDS.matches(line)) continue
            // Bekanntes Kriterium?
            var matched: CriterionDef? = null
            var end = -1
            for (def in sorted) {
                val e = matchPrefix(line, def.label)
                if (e >= 0) { matched = def; end = e; break }
            }
            if (matched != null) {
                started = true
                currentUnknown = null
                val c = byKey.getOrPut(matched.key) { ParsedCriterion(matched.key) }
                current = c
                currentDef = matched
                applyRemainder(c, matched, line.substring(end).trim().trimStart(':', '|', '-').trim())
                continue
            }
            if (!started) continue
            // Fortsetzungszeile eines bekannten Kriteriums
            val cur = current
            val curDef = currentDef
            if (cur != null && curDef != null) {
                val unk = UNKNOWN.matchEntire(line)
                if (unk != null && looksLikeLabel(unk.groupValues[1])) {
                    unknownLabels += unk.groupValues[1].trim()
                    unknownNums += unk.groupValues[2].toInt()
                    unknownNotes += StringBuilder(unk.groupValues[3])
                    currentUnknown = unknownLabels.lastIndex
                    current = null
                    currentDef = null
                } else {
                    continuation(cur, curDef, line)
                }
            } else if (currentUnknown != null) {
                val unk = UNKNOWN.matchEntire(line)
                if (unk != null && looksLikeLabel(unk.groupValues[1])) {
                    unknownLabels += unk.groupValues[1].trim()
                    unknownNums += unk.groupValues[2].toInt()
                    unknownNotes += StringBuilder(unk.groupValues[3])
                    currentUnknown = unknownLabels.lastIndex
                } else {
                    val sb = unknownNotes[currentUnknown!!]
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(line)
                }
            }
        }
        for (i in unknownLabels.indices) {
            unassigned += UnassignedLine(unknownLabels[i], unknownNums[i], unknownNotes[i].toString().ifBlank { null })
        }
        return ParsedProduct(title, byKey.values.toList(), unassigned)
    }

    private fun looksLikeLabel(s: String): Boolean = s.trim().length in 3..45 && s.trim().first().isUpperCase()

    private fun applyRemainder(c: ParsedCriterion, def: CriterionDef, rest: String) {
        if (rest.isEmpty()) return
        val isDataText = def.key in CriterionCatalog.DATA_KEYS
        val m = NUMBER_FIRST.matchEntire(rest)
        if (m != null && !isDataText) {
            c.number = m.groupValues[1].toInt()
            c.note = m.groupValues[2].ifBlank { null }
            return
        }
        when (def.type) {
            CriterionValueType.TEXT -> c.text = rest
            else -> if (isDataText) c.text = rest else if (rest.length <= 24 && !rest.contains(' ')) c.text = rest else c.note = rest
        }
    }

    private fun continuation(c: ParsedCriterion, def: CriterionDef, line: String) {
        if (c.number == null && c.text == null && c.note == null && def.key !in CriterionCatalog.DATA_KEYS) {
            val m = NUMBER_FIRST.matchEntire(line)
            if (m != null) {
                c.number = m.groupValues[1].toInt()
                c.note = m.groupValues[2].ifBlank { null }
                return
            }
        }
        if (c.text == null && c.number == null && c.note == null && line.length <= 24 && !line.contains(' ')) {
            c.text = line
            return
        }
        c.note = if (c.note.isNullOrEmpty()) line else c.note + " " + line
    }

    /** Position hinter dem Label in der Zeile, wenn die Zeile damit beginnt (Leerraum und Groß-/Kleinschreibung egal); sonst -1. */
    internal fun matchPrefix(line: String, label: String): Int {
        var i = 0
        var j = 0
        while (j < label.length) {
            while (j < label.length && label[j].isWhitespace()) j++
            while (i < line.length && line[i].isWhitespace()) i++
            if (j >= label.length) break
            if (i >= line.length || line[i].lowercaseChar() != label[j].lowercaseChar()) return -1
            i++; j++
        }
        if (i < line.length && line[i].isLetter()) return -1
        return i
    }
}
