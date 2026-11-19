package de.hr.mediathek.data.compress

/** Qualitätsstufen: Anteil der Quell-Videobitrate, der als Ziel gilt. */
enum class CompressionLevel(val factor: Double) {
    BARELY(0.65), // "Kaum sichtbar"
    BALANCED(0.50), // "Ausgewogen"
    STRONG(0.35), // "Stark": kann sichtbar sein
}

/** Phasen des Journals. Jede Phase wird persistiert, bevor der zugehörige Schritt beginnt bzw. nachdem er sicher war. */
object CompressionPhase {
    const val ENCODING = "ENCODING"
    const val VERIFIED = "VERIFIED"
    const val ORIGINAL_MOVED = "ORIGINAL_MOVED"
    const val RENAMED = "RENAMED"
    const val DB_UPDATED = "DB_UPDATED"
    const val ORIGINAL_DELETED = "ORIGINAL_DELETED"

    /** Endzustände ohne weitere Arbeit am Original: abgelehnt oder nicht komprimierbar. */
    const val FAILED = "FAILED"
    const val BLOCKED = "BLOCKED"

    val ORDER = listOf(ENCODING, VERIFIED, ORIGINAL_MOVED, RENAMED, DB_UPDATED, ORIGINAL_DELETED)

    /** Phasen, in denen noch etwas zu Ende zu führen ist. */
    val UNFINISHED = listOf(ENCODING, VERIFIED, ORIGINAL_MOVED, RENAMED)
}

object CompressionRecovery {
    /** Vor VERIFIED wird die Temp-Datei gelöscht und das Video neu begonnen. */
    fun restartFromScratch(phase: String): Boolean = phase == CompressionPhase.ENCODING

    /** Ab VERIFIED wird nie etwas verworfen, nur zu Ende geführt. */
    fun mustFinishForward(phase: String): Boolean =
        phase in CompressionPhase.UNFINISHED && phase != CompressionPhase.ENCODING

    fun isBefore(phase: String, other: String): Boolean {
        val a = CompressionPhase.ORDER.indexOf(phase)
        val b = CompressionPhase.ORDER.indexOf(other)
        return a >= 0 && b >= 0 && a < b
    }
}

enum class Blocker { NO_VIDEO, EXTRA_AUDIO, SUBTITLES, HDR_OR_10BIT }

enum class VerifyFailure { NO_VIDEO, DURATION, NOT_SMALLER, AUDIO_CHANGED, DECODE }

/** Eigenschaften einer Videodatei, soweit für Vorprüfung und Ergebnisprüfung nötig. */
data class MediaFacts(
    val durationMs: Long?,
    val sizeBytes: Long,
    val hasVideo: Boolean,
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val audioTracks: Int,
    val audioMime: String?,
    val textTracks: Int,
    val hdrOrTenBit: Boolean,
)

object CompressionPolicy {
    const val AUDIO_ESTIMATE_BPS = 192_000L
    const val MIN_TARGET_BPS = 300_000L
    const val MIN_SAVING = 0.15
    const val MAX_DURATION_DEVIATION = 0.01
    const val GRACE_MS = 7L * 24 * 60 * 60 * 1000

    /** Unterhalb davon lohnt sich Neukodieren nicht mehr. */
    private const val MIN_VIDEO_BPS = 1_000_000L
    private const val ASSUMED_FPS = 30.0
    private const val BPP_SKIP_HEVC = 0.07
    private const val BPP_SKIP_OTHER = 0.05

    fun totalBitrate(sizeBytes: Long, durationMs: Long): Long =
        if (durationMs <= 0) 0 else sizeBytes * 8000L / durationMs

    /** Videoanteil der Gesamtbitrate; der Ton wird grob mit 192 kbit/s angesetzt. */
    fun videoBitrate(totalBps: Long): Long =
        (totalBps - AUDIO_ESTIMATE_BPS).coerceAtLeast(totalBps / 2)

    fun targetVideoBitrate(sourceVideoBps: Long, level: CompressionLevel): Long =
        Math.round(sourceVideoBps * level.factor).coerceAtLeast(MIN_TARGET_BPS)

    fun estimatedSize(targetVideoBps: Long, durationMs: Long): Long =
        (targetVideoBps + AUDIO_ESTIMATE_BPS) * durationMs / 8000L

    /** true = die Quelle ist schon so stark komprimiert, dass sich Neukodieren nicht lohnt. */
    fun isAlreadySmall(videoBps: Long, width: Int, height: Int, videoMime: String?): Boolean {
        if (videoBps < MIN_VIDEO_BPS) return true
        if (width <= 0 || height <= 0) return false
        val bpp = videoBps / (width.toDouble() * height * ASSUMED_FPS)
        val hevc = videoMime?.contains("hevc", ignoreCase = true) == true ||
            videoMime?.contains("h265", ignoreCase = true) == true
        return bpp < (if (hevc) BPP_SKIP_HEVC else BPP_SKIP_OTHER)
    }

    /** Dinge, die beim Neukodieren verloren gehen würden. NO_VIDEO ist nie bestätigbar. */
    fun blockers(f: MediaFacts): List<Blocker> = buildList {
        if (!f.hasVideo) add(Blocker.NO_VIDEO)
        if (f.audioTracks > 1) add(Blocker.EXTRA_AUDIO)
        if (f.textTracks > 0) add(Blocker.SUBTITLES)
        if (f.hdrOrTenBit) add(Blocker.HDR_OR_10BIT)
    }

    /** null = in Ordnung. Der Dekodiertest wird separat geprüft und meldet [VerifyFailure.DECODE]. */
    fun verify(src: MediaFacts, out: MediaFacts): VerifyFailure? {
        if (!out.hasVideo) return VerifyFailure.NO_VIDEO
        val s = src.durationMs
        val o = out.durationMs
        if (s == null || o == null || s <= 0) return VerifyFailure.DURATION
        if (kotlin.math.abs(s - o).toDouble() / s > MAX_DURATION_DEVIATION) return VerifyFailure.DURATION
        if (out.sizeBytes > src.sizeBytes * (1.0 - MIN_SAVING)) return VerifyFailure.NOT_SMALLER
        val expectedAudio = if (src.audioTracks >= 1) 1 else 0
        if (out.audioTracks != expectedAudio) return VerifyFailure.AUDIO_CHANGED
        if (expectedAudio == 1 && src.audioMime != out.audioMime) return VerifyFailure.AUDIO_CHANGED
        return null
    }
}
