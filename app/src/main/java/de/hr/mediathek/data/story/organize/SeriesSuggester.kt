package de.hr.mediathek.data.story.organize

data class SeriesCandidate(
    val uuid: String,
    val title: String,
    val author: String?,
    val seriesUuid: String?,
    val createdAt: Long,
)

data class ProposedPart(val uuid: String, val title: String, val order: Int)

/**
 * Vorschlag: diese Geschichten gehören zu einer Serie. [existingSeriesUuid] ist gesetzt, wenn ein Teil der Geschichten
 * schon in einer Serie liegt und die übrigen nur ergänzt werden.
 */
data class SeriesProposal(
    val name: String,
    val existingSeriesUuid: String?,
    val parts: List<ProposedPart>,
)

/** Erkennt mehrteilige Geschichten an Titeln wie "Name Teil 2", "Name (3)", "Name - Kapitel 4" oder "Name 5". */
object SeriesSuggester {
    private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10)
    private val MARKED = Regex(
        "^(.*?\\S)[\\s,:;\\-–—]*\\(?\\s*(?:teil|tl\\.?|kapitel|kap\\.?|part|pt\\.?|folge|episode|chapter|ep\\.?|#)\\s*([0-9]{1,3}|[ivx]{1,4})\\b\\s*\\)?(?:\\s*[:\\-–—.].*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKET = Regex("^(.*?\\S)\\s*[(\\[]\\s*([0-9]{1,3})\\s*[)\\]]\\s*$")
    private val TRAILING = Regex("^(.*?[^\\s0-9])[\\s,:;\\-–—]+([0-9]{1,3})\\s*$")
    private val CONTINUATION = Regex("^(.*?\\S)[\\s,:;\\-–—]+(?:fortsetzung|forts\\.)\\s*([0-9]{1,3})?\\s*$", RegexOption.IGNORE_CASE)

    internal data class Parsed(val base: String, val number: Int?)

    internal fun parse(title: String): Parsed? {
        val t = title.trim()
        MARKED.matchEntire(t)?.let { m ->
            val n = m.groupValues[2].let { it.toIntOrNull() ?: ROMAN[it.lowercase()] }
            if (n != null) return Parsed(m.groupValues[1].trim(), n)
        }
        BRACKET.matchEntire(t)?.let { return Parsed(it.groupValues[1].trim(), it.groupValues[2].toInt()) }
        CONTINUATION.matchEntire(t)?.let { return Parsed(it.groupValues[1].trim(), it.groupValues[2].toIntOrNull() ?: 2) }
        TRAILING.matchEntire(t)?.let {
            val n = it.groupValues[2].toInt()
            // Jahreszahlen und Altersangaben wie "mit 18" sollen keine Teile sein: erst ab Zahl 1..99 und nur mit Rest-Titel.
            if (n in 1..99 && it.groupValues[1].count { c -> c.isLetter() } >= 3) return Parsed(it.groupValues[1].trim(), n)
        }
        return null
    }

    internal fun key(base: String): String =
        base.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(Regex("\\s+"), " ").trim()

    fun suggest(stories: List<SeriesCandidate>, seriesNames: Map<String, String>): List<SeriesProposal> {
        val byKey = HashMap<String, MutableList<Pair<SeriesCandidate, Parsed?>>>()
        for (s in stories) {
            val parsed = parse(s.title)
            val k = key(parsed?.base ?: s.title)
            if (k.length < 3) continue
            byKey.getOrPut(k) { ArrayList() }.add(s to parsed)
        }
        val result = ArrayList<SeriesProposal>()
        for (group in byKey.values) {
            // Gleicher Basistitel, aber verschiedene Autoren sind verschiedene Serien.
            val authors = group.mapNotNull { it.first.author?.trim()?.lowercase()?.takeIf { a -> a.isNotEmpty() } }.distinct()
            val buckets: List<List<Pair<SeriesCandidate, Parsed?>>> =
                if (authors.size <= 1) listOf(group)
                else group.groupBy { it.first.author?.trim()?.lowercase().orEmpty() }.values.toList()
            for (bucket in buckets) {
                val numbered = bucket.filter { it.second != null }
                if (numbered.isEmpty()) continue
                // Nur ein einziger Titel ohne Nummer darf als Teil 1 dazukommen.
                val unnumbered = bucket.filter { it.second == null }
                val hasOne = numbered.any { it.second!!.number == 1 }
                val members = if (!hasOne && unnumbered.size == 1) numbered + unnumbered else numbered
                if (members.size < 2) continue
                val existing = members.mapNotNull { it.first.seriesUuid }.distinct()
                if (existing.size > 1) continue
                val toAdd = members.filter { it.first.seriesUuid == null }
                if (toAdd.isEmpty()) continue
                val sorted = members.sortedWith(compareBy({ it.second?.number ?: 1 }, { it.first.createdAt }))
                val base = numbered.first().second!!.base
                val parts = sorted.filter { it.first.seriesUuid == null }.mapIndexed { i, (s, p) ->
                    ProposedPart(s.uuid, s.title, p?.number ?: 1).let { it.copy(order = if (existing.isEmpty()) i + 1 else it.order) }
                }
                result += SeriesProposal(
                    name = existing.firstOrNull()?.let { seriesNames[it] } ?: base,
                    existingSeriesUuid = existing.firstOrNull(),
                    parts = parts,
                )
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }
}
