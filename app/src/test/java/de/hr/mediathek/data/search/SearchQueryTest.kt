package de.hr.mediathek.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {
    @Test fun termsAreAndedWithPrefix() {
        assertEquals("rot* blau*", SearchQuery.parse("Rot blau")!!.match)
    }

    @Test fun phraseInQuotes() {
        assertEquals("\"rotes auto\" fahrt*", SearchQuery.parse("\"rotes auto\" fahrt")!!.match)
    }

    @Test fun exclusionIsSeparate() {
        val q = SearchQuery.parse("auto -rot -\"blaues haus\"")!!
        assertEquals("auto*", q.match)
        assertEquals(listOf("rot*", "\"blaues haus\""), q.excluded)
    }

    @Test fun hyphenWordIsOnePhrase() {
        assertEquals("\"t shirt\"", SearchQuery.parse("t-shirt")!!.match)
    }

    @Test fun specialCharsAreNeutralised() {
        assertEquals("abc*", SearchQuery.parse("abc:*")!!.match)
        assertEquals("\"a b\" c*", SearchQuery.parse("a^b ( c")!!.match)
    }

    @Test fun operatorsBecomeLowercaseTerms() {
        assertEquals("tom* or* jerry*", SearchQuery.parse("tom OR jerry")!!.match)
    }

    @Test fun onlyExclusionOrEmptyGivesNull() {
        assertNull(SearchQuery.parse("-rot"))
        assertNull(SearchQuery.parse("   "))
        assertNull(SearchQuery.parse("\"\" **"))
    }

    @Test fun unclosedQuoteTakesRest() {
        assertEquals("\"rotes auto\"", SearchQuery.parse("\"rotes auto")!!.match)
    }

    @Test fun umlautsAreKept() {
        assertEquals("grüne* bäume*", SearchQuery.parse("Grüne Bäume")!!.match)
    }
}
