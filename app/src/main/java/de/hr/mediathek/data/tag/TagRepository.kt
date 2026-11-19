package de.hr.mediathek.data.tag

import androidx.room.withTransaction
import de.hr.mediathek.data.db.EntityType
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.TagAssignmentEntity
import de.hr.mediathek.data.db.TagDao
import de.hr.mediathek.data.db.TagEntity
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

object TagNames {
    const val MAX = 40

    /** Vergleichsform: klein, ohne Akzente, Leerraum zusammengezogen ("Éa  Test" und "ea test" sind dasselbe Tag). */
    fun normalize(name: String): String =
        Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
}

/** Tags für Videos, parallel zu Kategorien: UUIDs, Tombstones, Zuordnungen als eigene Zeilen. */
@Singleton
class TagRepository @Inject constructor(
    private val db: MediathekDatabase,
    private val dao: TagDao,
    private val mediaDao: MediaFileDao,
) {
    fun observeAll(): Flow<List<TagEntity>> = dao.observeAll()

    /** Legt das Tag an, falls es nicht (in normalisierter Form) schon existiert. */
    suspend fun create(name: String): Boolean {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || clean.length > TagNames.MAX) return false
        val normalized = TagNames.normalize(clean)
        if (dao.findByNormalized(normalized) != null) return false
        dao.insert(TagEntity(name = clean, nameNormalized = normalized))
        return true
    }

    suspend fun delete(uuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        dao.tombstoneAllAssignments(uuid, now)
        dao.tombstone(uuid, now)
    }

    suspend fun usedBy(mediaUuids: List<String>): Set<String> =
        mediaUuids.chunked(CHUNK).flatMap { dao.usedTags(it) }.toSet()

    suspend fun assign(mediaUuids: List<String>, tagUuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        for (chunk in mediaUuids.chunked(CHUNK)) {
            val existing = dao.assignmentsFor(tagUuid, chunk).associateBy { it.entityUuid }
            dao.upsertAssignments(
                chunk.map { media ->
                    existing[media]?.copy(deletedAt = null, updatedAt = now, userUpdatedAt = now)
                        ?: TagAssignmentEntity(
                            entityType = EntityType.MEDIA, entityUuid = media, tagUuid = tagUuid, createdAt = now,
                        )
                },
            )
            mediaDao.touch(chunk, now)
        }
    }

    suspend fun unassign(mediaUuids: List<String>, tagUuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        mediaUuids.chunked(CHUNK).forEach {
            dao.tombstoneAssignments(tagUuid, it, now)
            mediaDao.touch(it, now)
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
