package de.hr.mediathek.data.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionPolicyTest {
    private fun facts(
        duration: Long? = 600_000,
        size: Long = 1_000_000_000,
        audio: Int = 1,
        audioMime: String? = "audio/mp4a-latm",
        text: Int = 0,
        hdr: Boolean = false,
        video: Boolean = true,
    ) = MediaFacts(duration, size, video, "video/avc", 1920, 1080, audio, audioMime, text, hdr)

    @Test fun targetFollowsLevel() {
        assertEquals(6_500_000L, CompressionPolicy.targetVideoBitrate(10_000_000, CompressionLevel.BARELY))
        assertEquals(5_000_000L, CompressionPolicy.targetVideoBitrate(10_000_000, CompressionLevel.BALANCED))
        assertEquals(3_500_000L, CompressionPolicy.targetVideoBitrate(10_000_000, CompressionLevel.STRONG))
    }

    @Test fun targetHasFloor() {
        assertEquals(CompressionPolicy.MIN_TARGET_BPS, CompressionPolicy.targetVideoBitrate(400_000, CompressionLevel.STRONG))
    }

    @Test fun bitrateFromSize() {
        // 1 GB in 600 s = ca. 13,3 Mbit/s
        assertEquals(13_333_333L, CompressionPolicy.totalBitrate(1_000_000_000, 600_000))
        assertEquals(0L, CompressionPolicy.totalBitrate(1_000_000_000, 0))
    }

    @Test fun lowBitrateIsSkipped() {
        assertTrue(CompressionPolicy.isAlreadySmall(800_000, 1280, 720, "video/avc"))
        assertTrue(CompressionPolicy.isAlreadySmall(2_000_000, 1920, 1080, "video/avc")) // bpp ca. 0,032
        assertFalse(CompressionPolicy.isAlreadySmall(8_000_000, 1920, 1080, "video/avc"))
    }

    @Test fun hevcSourceHasStricterThreshold() {
        // 1080p mit 3,5 Mbit/s: bei AVC noch lohnend (bpp 0,056), bei HEVC nicht (bpp < 0,07)
        assertFalse(CompressionPolicy.isAlreadySmall(3_500_000, 1920, 1080, "video/avc"))
        assertTrue(CompressionPolicy.isAlreadySmall(3_500_000, 1920, 1080, "video/hevc"))
    }

    @Test fun blockersAreListed() {
        assertTrue(CompressionPolicy.blockers(facts()).isEmpty())
        assertEquals(listOf(Blocker.EXTRA_AUDIO), CompressionPolicy.blockers(facts(audio = 2)))
        assertEquals(listOf(Blocker.SUBTITLES), CompressionPolicy.blockers(facts(text = 1)))
        assertEquals(listOf(Blocker.HDR_OR_10BIT), CompressionPolicy.blockers(facts(hdr = true)))
        assertEquals(listOf(Blocker.NO_VIDEO), CompressionPolicy.blockers(facts(video = false)))
    }

    @Test fun verifyAcceptsGoodResult() {
        assertNull(CompressionPolicy.verify(facts(), facts(size = 600_000_000, duration = 600_300)))
    }

    @Test fun verifyRejectsDurationDrift() {
        // 1,5 % Abweichung
        assertEquals(VerifyFailure.DURATION, CompressionPolicy.verify(facts(), facts(size = 600_000_000, duration = 609_000)))
        assertEquals(VerifyFailure.DURATION, CompressionPolicy.verify(facts(), facts(size = 600_000_000, duration = null)))
    }

    @Test fun verifyRequiresFivePercentSaving() {
        assertEquals(VerifyFailure.NOT_SMALLER, CompressionPolicy.verify(facts(), facts(size = 980_000_000)))
        assertNull(CompressionPolicy.verify(facts(), facts(size = 950_000_000)))
        assertEquals(VerifyFailure.NOT_SMALLER, CompressionPolicy.verify(facts(), facts(size = 950_000_001)))
    }

    @Test fun verifyRequiresAudioPassthrough() {
        assertEquals(VerifyFailure.AUDIO_CHANGED, CompressionPolicy.verify(facts(), facts(size = 500_000_000, audio = 0)))
        // Anderer Ton-Codec (z. B. AC3 zu AAC, weil MP4 ihn nicht aufnimmt) ist in Ordnung, solange die Spur da ist
        assertNull(CompressionPolicy.verify(facts(), facts(size = 500_000_000, audioMime = "audio/opus")))
        // Mehrere Tonspuren in der Quelle: nur die erste bleibt (vom Nutzer bestätigt)
        assertNull(CompressionPolicy.verify(facts(audio = 3), facts(size = 500_000_000, audio = 1)))
        // Quelle ohne Ton
        assertNull(CompressionPolicy.verify(facts(audio = 0, audioMime = null), facts(size = 500_000_000, audio = 0, audioMime = null)))
    }

    @Test fun recoveryRules() {
        assertTrue(CompressionRecovery.restartFromScratch(CompressionPhase.ENCODING))
        assertFalse(CompressionRecovery.restartFromScratch(CompressionPhase.VERIFIED))
        listOf(CompressionPhase.VERIFIED, CompressionPhase.ORIGINAL_MOVED, CompressionPhase.RENAMED).forEach {
            assertTrue(CompressionRecovery.mustFinishForward(it))
        }
        assertFalse(CompressionRecovery.mustFinishForward(CompressionPhase.ENCODING))
        assertFalse(CompressionRecovery.mustFinishForward(CompressionPhase.DB_UPDATED))
        assertTrue(CompressionRecovery.isBefore(CompressionPhase.VERIFIED, CompressionPhase.ORIGINAL_MOVED))
        assertFalse(CompressionRecovery.isBefore(CompressionPhase.RENAMED, CompressionPhase.ORIGINAL_MOVED))
        assertFalse(CompressionRecovery.isBefore(CompressionPhase.FAILED, CompressionPhase.RENAMED))
    }

    @Test fun estimateMatchesTarget() {
        // (5 Mbit/s + 192 kbit/s) * 600 s / 8
        assertEquals(389_400_000L, CompressionPolicy.estimatedSize(5_000_000, 600_000))
    }
}

class CompressionRunPolicyTest {
    @Test fun runsOnlyWhenAllowed() {
        assertTrue(CompressionRunPolicy.mayRun(paused = false, onlyCharging = true, charging = true))
        assertFalse(CompressionRunPolicy.mayRun(paused = false, onlyCharging = true, charging = false))
        assertTrue(CompressionRunPolicy.mayRun(paused = false, onlyCharging = false, charging = false))
        assertFalse(CompressionRunPolicy.mayRun(paused = true, onlyCharging = false, charging = true))
    }
}
