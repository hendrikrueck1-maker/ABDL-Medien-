package de.hr.mediathek.data.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VideoSuggestionsTest {
    private fun ids(n: Int, prefix: String) = (1..n).map { "$prefix$it" }

    @Test fun picksTwoTwoOne() {
        val r = VideoSuggestions.pick(ids(10, "o"), ids(10, "r"), ids(10, "n"), { it }, Random(1))
        assertEquals(5, r.size)
        assertEquals(listOf(2, 2, 1), SuggestionKind.values().map { k -> r.count { it.kind == k } })
        assertEquals(5, r.map { it.item }.toSet().size)
    }

    @Test fun noDuplicatesAcrossGroups() {
        val shared = listOf("a", "b", "c")
        val r = VideoSuggestions.pick(shared, shared, shared, { it }, Random(2))
        assertEquals(r.map { it.item }.toSet().size, r.size)
        assertEquals(3, r.size)
    }

    @Test fun fillsFromOtherGroupsWhenOneIsEmpty() {
        val r = VideoSuggestions.pick(emptyList(), ids(10, "r"), ids(10, "n"), { it }, Random(3))
        assertEquals(5, r.size)
        assertTrue(r.none { it.kind == SuggestionKind.OFTEN })
    }

    @Test fun emptyInputGivesEmptyList() {
        assertTrue(VideoSuggestions.pick(emptyList<String>(), emptyList(), emptyList(), { it }).isEmpty())
    }

    @Test fun onlyOneGroupStillGivesFive() {
        repeat(20) { seed ->
            val r = VideoSuggestions.pick(ids(30, "o"), emptyList(), emptyList(), { it }, Random(seed))
            assertEquals(5, r.size)
            assertEquals(5, r.map { it.item }.toSet().size)
            assertTrue(r.all { it.kind == SuggestionKind.OFTEN })
        }
    }
}
