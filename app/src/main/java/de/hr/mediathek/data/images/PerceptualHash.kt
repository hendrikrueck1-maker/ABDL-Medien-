package de.hr.mediathek.data.images

/** Rein rechnerische Teile der Bild-Duplikaterkennung (ohne Android, damit testbar). */
object PerceptualHash {
    const val GRID_W = 9
    const val GRID_H = 8

    /**
     * dHash: [gray] sind 9 x 8 Helligkeitswerte (zeilenweise). Jedes Bit sagt, ob ein Pixel heller ist als sein rechter Nachbar.
     * Ergibt 64 Bit; ähnliche Bilder haben wenig unterschiedliche Bits.
     */
    fun dHash(gray: IntArray): Long {
        require(gray.size == GRID_W * GRID_H)
        var hash = 0L
        var bit = 0
        for (y in 0 until GRID_H) {
            for (x in 0 until GRID_W - 1) {
                if (gray[y * GRID_W + x] > gray[y * GRID_W + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}

class HashedItem(val id: String, val hash: Long)

/**
 * Bildet Gruppen ähnlicher Bilder ohne Paarvergleich aller Bilder: Die 64 Bit werden in (Schwelle + 1) Abschnitte geteilt;
 * zwei Hashes mit höchstens [maxDistance] unterschiedlichen Bits stimmen in mindestens einem Abschnitt vollständig überein.
 * Nur Hashes mit gleichem Abschnitt werden verglichen, die Treffer werden vereinigt.
 */
object SimilarImageGroups {
    const val MAX_DISTANCE = 10

    fun build(items: List<HashedItem>, maxDistance: Int): List<List<String>> {
        val d = maxDistance.coerceIn(0, MAX_DISTANCE)
        if (items.size < 2) return emptyList()

        // Gleiche Hashes zuerst zusammenfassen: so entstehen keine riesigen Buckets, etwa bei einfarbigen Bildern.
        val idsByHash = LinkedHashMap<Long, MutableList<String>>()
        for (item in items) idsByHash.getOrPut(item.hash) { ArrayList() } += item.id
        val hashes = idsByHash.keys.toLongArray()
        val parent = IntArray(hashes.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        if (d > 0) {
            val chunks = d + 1
            val base = 64 / chunks
            val extra = 64 % chunks
            var shift = 0
            for (c in 0 until chunks) {
                val width = base + if (c < extra) 1 else 0
                val mask = if (width >= 64) -1L else (1L shl width) - 1
                val buckets = HashMap<Long, MutableList<Int>>()
                for (i in hashes.indices) {
                    buckets.getOrPut((hashes[i] ushr shift) and mask) { ArrayList() } += i
                }
                for (bucket in buckets.values) {
                    if (bucket.size < 2) continue
                    for (i in 0 until bucket.size) {
                        for (j in i + 1 until bucket.size) {
                            if (PerceptualHash.distance(hashes[bucket[i]], hashes[bucket[j]]) <= d) union(bucket[i], bucket[j])
                        }
                    }
                }
                shift += width
            }
        }

        val groups = HashMap<Int, MutableList<String>>()
        for (i in hashes.indices) {
            groups.getOrPut(find(i)) { ArrayList() } += idsByHash.getValue(hashes[i])
        }
        return groups.values.filter { it.size > 1 }.sortedByDescending { it.size }
    }
}
