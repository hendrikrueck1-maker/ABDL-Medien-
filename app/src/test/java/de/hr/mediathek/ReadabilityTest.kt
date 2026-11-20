package de.hr.mediathek

import de.hr.mediathek.core.ui.ImageStats
import de.hr.mediathek.core.ui.Readability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityTest {
    @Test fun higherLevelIsMoreOpaque() {
        val lo = Readability.compute(0f, false, null, false)
        val hi = Readability.compute(1f, false, null, false)
        assertTrue(hi.cardAlpha > lo.cardAlpha && hi.panelAlpha > lo.panelAlpha && hi.veilAlpha > lo.veilAlpha)
    }

    @Test fun autoBoostsBrightBusyImageInLightTheme() {
        val base = Readability.compute(0.5f, true, null, false)
        val boosted = Readability.compute(0.5f, true, ImageStats(0.9f, 0.3f), false)
        assertTrue(boosted.panelAlpha > base.panelAlpha)
    }

    @Test fun autoOffIgnoresStats() {
        assertEquals(Readability.compute(0.5f, false, null, false), Readability.compute(0.5f, false, ImageStats(1f, 1f), false))
    }

    @Test fun alwaysClamped() {
        val c = Readability.compute(1f, true, ImageStats(1f, 1f), false)
        assertTrue(c.cardAlpha <= 0.96f && c.panelAlpha <= 0.85f && c.veilAlpha <= 0.75f)
    }
}
