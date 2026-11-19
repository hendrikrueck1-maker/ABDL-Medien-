package de.hr.mediathek.data.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlideshowOrderTest {
    @Test fun sequentialWrapsAround() {
        val o = SlideshowOrder(3, random = false, startIndex = 1)
        assertEquals(1, o.current)
        assertEquals(listOf(2, 0, 1, 2), List(4) { o.next() })
    }

    @Test fun randomShowsEveryImageOncePerRound() {
        val o = SlideshowOrder(20, random = true, startIndex = 7, rng = Random(3))
        val round = mutableListOf(o.current)
        repeat(19) { round += o.next() }
        assertEquals((0 until 20).toSet(), round.toSet())
        assertEquals(20, round.size)
        assertEquals(7, round.first())
    }

    @Test fun randomNeverRepeatsDirectlyAcrossRounds() {
        val o = SlideshowOrder(5, random = true, rng = Random(11))
        var last = o.current
        repeat(500) {
            val n = o.next()
            assertNotEquals(last, n)
            last = n
        }
    }

    @Test fun previousWalksHistoryAndNextReplaysIt() {
        val o = SlideshowOrder(10, random = true, rng = Random(5))
        val seen = mutableListOf(o.current)
        repeat(4) { seen += o.next() }
        assertEquals(seen[3], o.previous())
        assertEquals(seen[2], o.previous())
        assertEquals(seen[3], o.next())
        assertEquals(seen[4], o.next())
    }

    @Test fun singleImageDoesNotLoopForever() {
        val o = SlideshowOrder(1, random = true)
        assertEquals(0, o.next())
        assertEquals(0, o.next())
    }

    @Test fun longRunStaysConsistent() {
        val o = SlideshowOrder(7, random = true, rng = Random(1))
        repeat(5000) { assertTrue(o.next() in 0 until 7) }
        assertTrue(o.previous() in 0 until 7)
    }
}
