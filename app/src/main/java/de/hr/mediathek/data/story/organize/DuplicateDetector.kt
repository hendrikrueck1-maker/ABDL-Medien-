package de.hr.mediathek.data.story.organize

/** Eine Geschichte für den Vergleich: Kennung, Text-Hash und der Text selbst wird erst beim Zeichnen gelesen. */
data class DupInput(val uuid: String, val textHash: String?)

enum class DupKind { IDENTICAL, SIMILAR }

/** Gruppe zusammengehöriger Geschichten; [similarity] ist die geschätzte Textübereinstimmung (0..1). */
data class DupGroup(val kind: DupKind, val members: List<String>, val similarity: Float)

/**
 * Findet identische und sehr ähnliche Texte. Ähnlichkeit wird über MinHash-Signaturen von Wort-Shingles geschätzt,
 * Kandidaten über LSH-Bänder gesucht. So bleibt es auch bei tausenden langen Geschichten schnell und braucht
 * pro Geschichte nur 48 Zahlen statt des Textes.
 */
object DuplicateDetector {
    const val HASHES = 48
    private const val BANDS = 12
    private const val ROWS = HASHES / BANDS
    private const val SHINGLE = 4
    private const val MIN_WORDS_SIMILAR = 40

    /** Signatur eines Textes; null bei zu kurzen Texten (dort zählt nur der exakte Hash). */
    class Signature(val values: LongArray, val words: Int)

    private val MULT = LongArray(HASHES) { i -> (0x9E3779B97F4A7C15uL.toLong() xor (i * 0x632BE59BD9B4E019L)) or 1L }
    private val ADD = LongArray(HASHES) { i -> 0x7F4A7C15L * (i + 3) + 0x1234567L * i }

    internal fun tokens(text: String): List<String> {
        val out = ArrayList<String>(text.length / 6)
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun signature(text: String): Signature? {
        val words = tokens(text)
        if (words.size < MIN_WORDS_SIMILAR) return null
        val mins = LongArray(HASHES) { Long.MAX_VALUE }
        for (i in 0..words.size - SHINGLE) {
            var h = 1125899906842597L
            for (k in 0 until SHINGLE) h = 31 * h + words[i + k].hashCode()
            h = mix(h)
            for (j in 0 until HASHES) {
                val v = mix(h * MULT[j] + ADD[j])
                if (v < mins[j]) mins[j] = v
            }
        }
        return Signature(mins, words.size)
    }

    private fun mix(x: Long): Long {
        var z = x
        z = (z xor (z ushr 33)) * -0xae502812aa7333L
        z = (z xor (z ushr 33)) * -0x3b314601e57a13adL
        return z xor (z ushr 33)
    }

    fun similarity(a: Signature, b: Signature): Float {
        var same = 0
        for (i in 0 until HASHES) if (a.values[i] == b.values[i]) same++
        return same.toFloat() / HASHES
    }

    /**
     * Gruppiert. [inputs] mit gleichem Hash sind identisch; Signaturen in [signatures] (nach Kennung) liefern die ähnlichen.
     * [minSimilarity] ist die Schwelle für "sehr ähnlich".
     */
    fun group(
        inputs: List<DupInput>,
        signatures: Map<String, Signature>,
        minSimilarity: Float = 0.8f,
    ): List<DupGroup> {
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            var c = x
            while (parent[c] != null && parent[c] != r && c != r) { val n = parent[c]!!; parent[c] = r; c = n }
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        inputs.forEach { parent[it.uuid] = it.uuid }

        val identicalPairs = HashSet<String>()
        inputs.filter { !it.textHash.isNullOrEmpty() }.groupBy { it.textHash }.values.filter { it.size >= 2 }.forEach { same ->
            for (i in 1 until same.size) {
                union(same[0].uuid, same[i].uuid)
                identicalPairs += same[i].uuid
            }
            identicalPairs += same[0].uuid
        }

        val similarOf = HashMap<String, Float>() // Wurzel -> beste Ähnlichkeit
        val buckets = HashMap<String, MutableList<String>>()
        for ((uuid, sig) in signatures) {
            for (band in 0 until BANDS) {
                var key = band.toString()
                for (r in 0 until ROWS) key += ":" + sig.values[band * ROWS + r]
                buckets.getOrPut(key) { ArrayList(2) }.add(uuid)
            }
        }
        val checked = HashSet<String>()
        for (list in buckets.values) {
            if (list.size < 2 || list.size > 200) continue
            for (i in 0 until list.size) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val key = if (a < b) "$a|$b" else "$b|$a"
                if (!checked.add(key)) continue
                val sa = signatures[a] ?: continue
                val sb = signatures[b] ?: continue
                val sim = similarity(sa, sb)
                if (sim >= minSimilarity) {
                    union(a, b)
                    similarOf[a] = maxOf(similarOf[a] ?: 0f, sim)
                    similarOf[b] = maxOf(similarOf[b] ?: 0f, sim)
                }
            }
        }

        val byRoot = inputs.map { it.uuid }.groupBy { find(it) }.values.filter { it.size >= 2 }
        return byRoot.map { members ->
            val allIdentical = members.all { it in identicalPairs } &&
                members.map { m -> inputs.first { it.uuid == m }.textHash }.distinct().size == 1
            val sim = if (allIdentical) 1f else members.mapNotNull { similarOf[it] }.minOrNull() ?: 1f
            DupGroup(if (allIdentical) DupKind.IDENTICAL else DupKind.SIMILAR, members, sim)
        }.sortedWith(compareBy({ it.kind }, { -it.members.size }))
    }
}
