package de.hr.mediathek.data.suggest

import kotlin.random.Random

enum class SuggestionKind { OFTEN, RARE, NEVER }

data class Suggestion<T>(val item: T, val kind: SuggestionKind)

/**
 * Wählt bis zu fünf Vorschläge: 2 oft gesehene, 2 selten gesehene, 1 noch nie gesehenes Video.
 * Ein Video kommt nur einmal vor; fehlt eine Gruppe, springen die anderen Gruppen ein, damit möglichst fünf zusammenkommen.
 * Die Listen sind jeweils nach Eignung sortiert (beste zuerst); gewählt wird zufällig unter den ersten Kandidaten.
 */
object VideoSuggestions {
    private const val POOL = 6
    private val DISPLAY_ORDER = listOf(SuggestionKind.NEVER, SuggestionKind.RARE, SuggestionKind.OFTEN)

    fun <T> pick(
        often: List<T>,
        rare: List<T>,
        never: List<T>,
        key: (T) -> String,
        rng: Random = Random.Default,
    ): List<Suggestion<T>> {
        val used = HashSet<String>()
        val result = ArrayList<Suggestion<T>>()

        fun take(pool: List<T>, kind: SuggestionKind, count: Int) {
            val candidates = pool.filter { key(it) !in used }.take(POOL).shuffled(rng)
            for (item in candidates.take(count)) {
                used += key(item)
                result += Suggestion(item, kind)
            }
        }

        take(often, SuggestionKind.OFTEN, 2)
        take(rare, SuggestionKind.RARE, 2)
        take(never, SuggestionKind.NEVER, 1)
        // Auffüllen, falls Gruppen zu klein waren: erst noch-nie-gesehene, dann selten, dann oft gesehene.
        if (result.size < 5) take(never, SuggestionKind.NEVER, 5 - result.size)
        if (result.size < 5) take(rare, SuggestionKind.RARE, 5 - result.size)
        if (result.size < 5) take(often, SuggestionKind.OFTEN, 5 - result.size)
        // Reihenfolge der Anzeige: zuerst noch nie gesehen, dann selten, zuletzt oft gesehen.
        return result.sortedBy { DISPLAY_ORDER.indexOf(it.kind) }
    }
}
