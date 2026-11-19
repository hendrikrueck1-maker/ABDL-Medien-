package de.hr.mediathek.data.images

import kotlin.random.Random

/**
 * Reihenfolge einer Diashow über [size] Bilder (Indizes 0 bis size-1).
 * Zufallsmodus ohne Wiederholung: erst wenn alle Bilder gezeigt wurden, wird neu gemischt (das erste Bild der
 * neuen Runde ist nie das zuletzt gezeigte). Feste Reihenfolge: der Liste nach, am Ende wieder von vorn.
 * Zurückblättern geht über den Verlauf.
 */
class SlideshowOrder(
    private val size: Int,
    private val random: Boolean,
    startIndex: Int = 0,
    private val rng: Random = Random.Default,
) {
    private val history = ArrayList<Int>()
    private var pos = -1
    private val deck = ArrayDeque<Int>()

    init {
        require(size > 0)
        if (random) {
            val first = startIndex.coerceIn(0, size - 1)
            refill(exclude = null)
            deck.remove(first)
            history += first
        } else {
            history += startIndex.coerceIn(0, size - 1)
        }
        pos = 0
    }

    val current: Int get() = history[pos]

    fun next(): Int {
        if (pos < history.size - 1) {
            pos++
            return current
        }
        val value = if (random) {
            if (deck.isEmpty()) refill(exclude = history[pos])
            deck.removeFirst()
        } else {
            (history[pos] + 1) % size
        }
        history += value
        pos++
        if (history.size > MAX_HISTORY) {
            val drop = history.size / 2
            repeat(drop) { history.removeAt(0) }
            pos -= drop
        }
        return current
    }

    fun previous(): Int {
        if (pos > 0) pos--
        return current
    }

    private fun refill(exclude: Int?) {
        val all = (0 until size).shuffled(rng).toMutableList()
        // Bei Neumischung darf das letzte gezeigte Bild nicht gleich wieder kommen (nur sinnvoll bei mehr als einem Bild).
        if (exclude != null && size > 1 && all.first() == exclude) {
            val swap = all.indices.drop(1).first()
            all[0] = all[swap].also { all[swap] = all[0] }
        }
        deck.clear()
        deck.addAll(all)
    }

    private companion object {
        const val MAX_HISTORY = 1000
    }
}
