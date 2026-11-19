package de.hr.mediathek.data.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PerceptualHashTest {
    @Test fun dHashFollowsBrightnessGradient() {
        // Jede Zeile fällt von links nach rechts ab: alle 64 Bit gesetzt
        val falling = IntArray(72) { 255 - (it % 9) * 20 }
        assertEquals(java.lang.Long.bitCount(PerceptualHash.dHash(falling)), 64)
        val rising = IntArray(72) { (it % 9) * 20 }
        assertEquals(0L, PerceptualHash.dHash(rising))
    }

    @Test fun luminanceIsWeighted() {
        assertEquals(255, PerceptualHash.luminance(0xFFFFFFFF.toInt()))
        assertEquals(0, PerceptualHash.luminance(0xFF000000.toInt()))
        assertTrue(PerceptualHash.luminance(0xFF00FF00.toInt()) > PerceptualHash.luminance(0xFFFF0000.toInt()))
    }

    @Test fun distanceCountsBits() {
        assertEquals(0, PerceptualHash.distance(5L, 5L))
        assertEquals(3, PerceptualHash.distance(0b111L, 0L))
    }

    @Test fun groupsIdenticalAndNearHashes() {
        val a = 0x0123456789ABCDEFL
        val items = listOf(
            HashedItem("a1", a),
            HashedItem("a2", a), // identisch
            HashedItem("a3", a xor 0b101L), // 2 Bit Unterschied
            HashedItem("b1", a.inv()), // weit weg
            HashedItem("c1", 42L),
        )
        val groups = SimilarImageGroups.build(items, 4).map { it.toSet() }
        assertEquals(listOf(setOf("a1", "a2", "a3")), groups)
    }

    @Test fun thresholdZeroOnlyGroupsIdentical() {
        val a = 0x0F0F0F0F0F0F0F0FL
        val groups = SimilarImageGroups.build(listOf(HashedItem("x", a), HashedItem("y", a), HashedItem("z", a xor 1L)), 0)
        assertEquals(listOf(setOf("x", "y")), groups.map { it.toSet() })
    }

    @Test fun matchesBruteForceOnRandomData() {
        val random = Random(7)
        val items = (0 until 400).map { i ->
            // Cluster: jeder zehnte Hash ist eine leichte Abwandlung des vorherigen
            val hash = if (i % 10 == 0) random.nextLong() else {
                var h = 0L
                repeat(3) { h = h or (1L shl random.nextInt(64)) }
                items0(i, random) xor h
            }
            HashedItem("i$i", hash).also { remember(it) }
        }
        val d = 6
        val fast = SimilarImageGroups.build(items, d).map { it.toSet() }.toSet()
        // Referenz: Vereinigung aller Paare mit Abstand <= d
        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in items.indices) for (j in i + 1 until items.size) {
            if (PerceptualHash.distance(items[i].hash, items[j].hash) <= d) parent[find(i)] = find(j)
        }
        val reference = items.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            .map { g -> g.map { items[it].id }.toSet() }.toSet()
        assertEquals(reference, fast)
    }

    private val seen = ArrayList<HashedItem>()
    private fun remember(item: HashedItem) { seen += item }
    private fun items0(i: Int, random: Random): Long = seen.lastOrNull()?.hash ?: random.nextLong()
}
