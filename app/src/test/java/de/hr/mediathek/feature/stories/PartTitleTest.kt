package de.hr.mediathek.feature.stories

import org.junit.Assert.assertEquals
import org.junit.Test

class PartTitleTest {
    @Test fun stripsSeriesNameAndPartNumber() {
        assertEquals("", partTitle("Meine Windelgeschichte G* – Teil 1", "Meine Windelgeschichte G*"))
        assertEquals("Familie", partTitle("Meine Windelgeschichte Teil 1 Familie", "Meine Windelgeschichte"))
        assertEquals("Der Ausflug", partTitle("Kapitel 3: Der Ausflug", "Ganz anders"))
        assertEquals("Ganz eigener Titel", partTitle("Ganz eigener Titel", null))
    }
}

class ShortFilterAndSourceTest {
    private fun row(words: Int, source: String?) = de.hr.mediathek.data.db.StoryRow(
        uuid = "u$words", title = "T", category = de.hr.mediathek.data.db.StoryCategory.MIXED, rating = 0, favorite = false,
        continueFlag = false, sourceText = source, createdAt = 0, updatedAt = 0, deletedAt = null, seriesUuid = null,
        seriesOrder = null, author = null, lastReadAt = null, readProgress = 0f, wordCount = words,
    )

    @Test fun shortFilterKeepsOnlyStoriesUnderLimit() {
        val f = StoryFilter(maxWords = SHORT_STORY_WORDS)
        assertEquals(true, StoryListBuilder.matches(row(199, null), f))
        assertEquals(false, StoryListBuilder.matches(row(200, null), f))
        assertEquals(true, filterActive(f))
        assertEquals(false, filterActive(StoryFilter()))
    }

    @Test fun sourceKeyNormalizes() {
        assertEquals("wb-community.com", de.hr.mediathek.data.story.organize.sourceKey(" www.WB-Community.com "))
        assertEquals("", de.hr.mediathek.data.story.organize.sourceKey(null))
    }
}
