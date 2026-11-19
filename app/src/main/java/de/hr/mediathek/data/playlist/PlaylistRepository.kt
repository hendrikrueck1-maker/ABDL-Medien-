package de.hr.mediathek.data.playlist

import androidx.room.withTransaction
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.PlaylistDao
import de.hr.mediathek.data.db.PlaylistEntity
import de.hr.mediathek.data.db.PlaylistItemEntity
import de.hr.mediathek.data.db.PlaylistType
import de.hr.mediathek.feature.videos.VideoFilter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Manuelle Playlists (feste Auswahl) und Smart Playlists (gespeicherter Filter mit Version und Zufalls-Seed). */
@Singleton
class PlaylistRepository @Inject constructor(
    private val db: MediathekDatabase,
    private val dao: PlaylistDao,
    private val mediaDao: MediaFileDao,
) {
    fun observeAll(): Flow<List<PlaylistEntity>> = dao.observeAll()

    suspend fun get(uuid: String): PlaylistEntity? = dao.get(uuid)

    suspend fun createManual(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME) return false
        dao.insert(PlaylistEntity(name = clean, type = PlaylistType.MANUAL))
        return true
    }

    suspend fun createSmart(name: String, filter: VideoFilter): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME) return false
        dao.insert(
            PlaylistEntity(
                name = clean,
                type = PlaylistType.SMART,
                filterJson = FilterJson.encode(filter),
                filterVersion = FilterJson.VERSION,
                randomSeed = System.currentTimeMillis() % 1_000_000_000L,
            ),
        )
        return true
    }

    suspend fun delete(uuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        dao.tombstoneAllItems(uuid, now)
        dao.tombstone(uuid, now)
    }

    /** Hängt Videos hinten an; schon enthaltene bleiben an ihrer Stelle, entfernte werden wiederbelebt. */
    suspend fun add(playlistUuid: String, mediaUuids: List<String>) = db.withTransaction {
        val now = System.currentTimeMillis()
        var next = dao.maxPosition(playlistUuid) + 1
        for (chunk in mediaUuids.chunked(CHUNK)) {
            val existing = dao.itemsFor(playlistUuid, chunk).associateBy { it.mediaUuid }
            val items = ArrayList<PlaylistItemEntity>()
            for (media in chunk) {
                val old = existing[media]
                when {
                    old == null -> items += PlaylistItemEntity(
                        playlistUuid = playlistUuid, mediaUuid = media, position = next++, createdAt = now,
                    )
                    old.deletedAt != null -> items += old.copy(
                        deletedAt = null, position = next++, updatedAt = now, userUpdatedAt = now,
                    )
                }
            }
            dao.upsertItems(items)
        }
    }

    suspend fun remove(playlistUuid: String, mediaUuids: List<String>) = db.withTransaction {
        val now = System.currentTimeMillis()
        mediaUuids.chunked(CHUNK).forEach { dao.tombstoneItems(playlistUuid, it, now) }
    }

    private companion object {
        const val CHUNK = 500
        const val MAX_NAME = 60
    }
}
