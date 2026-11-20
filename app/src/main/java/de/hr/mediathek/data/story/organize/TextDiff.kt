package de.hr.mediathek.data.story.organize

enum class DiffKind { SAME, ONLY_LEFT, ONLY_RIGHT }

data class DiffLine(val kind: DiffKind, val text: String)

/** Vergleicht zwei Texte absatzweise (längste gemeinsame Folge) für die Gegenüberstellung zweier Fassungen. */
object TextDiff {
    private const val MAX_CELLS = 4_000_000

    private fun norm(p: String) = p.trim().replace(Regex("\\s+"), " ").lowercase()

    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    fun diff(left: List<String>, right: List<String>): List<DiffLine> {
        val l = left.map(::norm)
        val r = right.map(::norm)
        var start = 0
        while (start < l.size && start < r.size && l[start] == r[start]) start++
        var endL = l.size
        var endR = r.size
        while (endL > start && endR > start && l[endL - 1] == r[endR - 1]) { endL--; endR-- }

        val out = ArrayList<DiffLine>()
        for (i in 0 until start) out += DiffLine(DiffKind.SAME, left[i])

        val n = endL - start
        val m = endR - start
        if (n.toLong() * m <= MAX_CELLS) {
            val w = m + 1
            val t = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                t[i * w + j] = if (l[start + i] == r[start + j]) t[(i + 1) * w + j + 1] + 1
                else maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    l[start + i] == r[start + j] -> { out += DiffLine(DiffKind.SAME, left[start + i]); i++; j++ }
                    t[(i + 1) * w + j] >= t[i * w + j + 1] -> { out += DiffLine(DiffKind.ONLY_LEFT, left[start + i]); i++ }
                    else -> { out += DiffLine(DiffKind.ONLY_RIGHT, right[start + j]); j++ }
                }
            }
            while (i < n) { out += DiffLine(DiffKind.ONLY_LEFT, left[start + i]); i++ }
            while (j < m) { out += DiffLine(DiffKind.ONLY_RIGHT, right[start + j]); j++ }
        } else {
            // Sehr lange Texte: nur Mengenvergleich, damit es schnell bleibt.
            val rs = r.subList(start, endR).toSet()
            val ls = l.subList(start, endL).toSet()
            for (i in start until endL) out += DiffLine(if (l[i] in rs) DiffKind.SAME else DiffKind.ONLY_LEFT, left[i])
            for (j in start until endR) if (r[j] !in ls) out += DiffLine(DiffKind.ONLY_RIGHT, right[j])
        }
        for (i in endL until left.size) out += DiffLine(DiffKind.SAME, left[i])
        return out
    }
}
