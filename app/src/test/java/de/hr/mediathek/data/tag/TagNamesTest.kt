package de.hr.mediathek.data.tag

import org.junit.Assert.assertEquals
import org.junit.Test

class TagNamesTest {
    @Test
    fun akzenteGrossKleinUndLeerraumWerdenAngeglichen() {
        assertEquals("ea test", TagNames.normalize("  Éa   TEST "))
    }

    @Test
    fun umlauteWerdenGefaltet() {
        assertEquals("uber", TagNames.normalize("Über"))
    }

    @Test
    fun gleicheNamen_gleicheNormalform() {
        assertEquals(TagNames.normalize("Nacht"), TagNames.normalize("nacht "))
    }
}
