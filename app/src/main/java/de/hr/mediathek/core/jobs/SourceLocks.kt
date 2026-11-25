package de.hr.mediathek.core.jobs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Sperre je Medienquelle: Scan und das Umbenennen/Umhängen am Ende einer Komprimierung dürfen nie gleichzeitig
 * dieselbe Quelle anfassen, sonst entsteht beim Containerwechsel (mkv → mp4) ein doppelter Eintrag.
 */
object SourceLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withSource(sourceUuid: String, block: suspend () -> T): T =
        locks.getOrPut(sourceUuid) { Mutex() }.withLock { block() }
}
