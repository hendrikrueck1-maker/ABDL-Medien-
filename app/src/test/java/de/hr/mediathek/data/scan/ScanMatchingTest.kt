package de.hr.mediathek.data.scan

import de.hr.mediathek.data.db.MediaStatus
import de.hr.mediathek.data.scan.ScanMatching.Candidate
import de.hr.mediathek.data.scan.ScanMatching.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMatchingTest {
    private val source = "src-1"

    @Test
    fun unchangedWithinFatTolerance() {
        assertTrue(ScanMatching.isUnchanged(100, 10_000, 100, 11_900))
        assertFalse(ScanMatching.isUnchanged(100, 10_000, 100, 12_100))
        assertFalse(ScanMatching.isUnchanged(100, 10_000, 101, 10_000))
        assertFalse(ScanMatching.isUnchanged(100, null, 100, 10_000))
    }

    @Test
    fun missingEntryIsClaimedAsMove() {
        val result = ScanMatching.resolve(
            candidates = listOf(Candidate("a", source, MediaStatus.ONLINE)),
            seen = emptySet(),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertEquals(Resolution.Claim(Candidate("a", source, MediaStatus.ONLINE)), result)
    }

    @Test
    fun copyOfPresentFileStaysSeparate() {
        val result = ScanMatching.resolve(
            candidates = listOf(Candidate("a", source, MediaStatus.ONLINE)),
            seen = setOf("a"),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertEquals(Resolution.CreateNew, result)
    }

    @Test
    fun alreadyClaimedEntryIsNotClaimedTwice() {
        val result = ScanMatching.resolve(
            candidates = listOf(Candidate("a", source, MediaStatus.OFFLINE)),
            seen = emptySet(),
            claimed = setOf("a"),
            currentSourceUuid = source,
        )
        assertEquals(Resolution.CreateNew, result)
    }

    @Test
    fun severalCandidatesAreAmbiguous() {
        val result = ScanMatching.resolve(
            candidates = listOf(
                Candidate("a", source, MediaStatus.OFFLINE),
                Candidate("b", source, MediaStatus.OFFLINE),
            ),
            seen = emptySet(),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertEquals(Resolution.Ambiguous(2), result)
    }

    @Test
    fun duplicateCandidateRowsCountOnce() {
        val result = ScanMatching.resolve(
            candidates = listOf(
                Candidate("a", source, MediaStatus.OFFLINE),
                Candidate("a", source, MediaStatus.OFFLINE),
            ),
            seen = emptySet(),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertTrue(result is Resolution.Claim)
    }

    @Test
    fun onlineEntryOfOtherSourceIsACopy() {
        val result = ScanMatching.resolve(
            candidates = listOf(Candidate("a", "other", MediaStatus.ONLINE)),
            seen = emptySet(),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertEquals(Resolution.CreateNew, result)
    }

    @Test
    fun offlineEntryOfOtherSourceCanMove() {
        val result = ScanMatching.resolve(
            candidates = listOf(Candidate("a", "other", MediaStatus.OFFLINE)),
            seen = emptySet(),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertTrue(result is Resolution.Claim)
    }

    @Test
    fun trashedEntryIsNeverClaimed() {
        val result = ScanMatching.resolve(
            candidates = listOf(Candidate("a", source, MediaStatus.TRASHED)),
            seen = emptySet(),
            claimed = emptySet(),
            currentSourceUuid = source,
        )
        assertEquals(Resolution.CreateNew, result)
    }

    @Test
    fun suspiciousWhenMoreThanTwentyPercentMissing() {
        assertTrue(ScanMatching.isSuspiciousMissing(totalOnline = 100, missingOnline = 21, listedFiles = 79))
        assertFalse(ScanMatching.isSuspiciousMissing(totalOnline = 100, missingOnline = 20, listedFiles = 80))
    }

    @Test
    fun suspiciousWhenListingSuddenlyEmpty() {
        assertTrue(ScanMatching.isSuspiciousMissing(totalOnline = 3, missingOnline = 3, listedFiles = 0))
        assertFalse(ScanMatching.isSuspiciousMissing(totalOnline = 0, missingOnline = 0, listedFiles = 0))
    }

    @Test
    fun smallLibrariesSkipPercentRule() {
        assertFalse(ScanMatching.isSuspiciousMissing(totalOnline = 5, missingOnline = 2, listedFiles = 3))
    }
}
