package de.hr.mediathek.data.category

import androidx.room.withTransaction
import de.hr.mediathek.data.db.CategoryAssignmentEntity
import de.hr.mediathek.data.db.CategoryDao
import de.hr.mediathek.data.db.CategoryEntity
import de.hr.mediathek.data.db.CategoryScope
import de.hr.mediathek.data.db.EntityType
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediathekDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Kategorien für Videos: anlegen, löschen (Tombstone) und Medien zuordnen. Alles mit UUIDs und Tombstones. */
@Singleton
class CategoryRepository @Inject constructor(
    private val db: MediathekDatabase,
    private val dao: CategoryDao,
    private val mediaDao: MediaFileDao,
) {
    fun observeVideoCategories(): Flow<List<CategoryEntity>> = dao.observeVideoCategories()

    suspend fun create(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME) return false
        dao.insert(CategoryEntity(scope = CategoryScope.VIDEO, name = clean))
        return true
    }

    suspend fun rename(uuid: String, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty() && clean.length <= MAX_NAME) dao.rename(uuid, clean, System.currentTimeMillis())
    }

    /** Die Kategorie wird als gelöscht markiert (Tombstone), ihre Zuordnungen ebenso; die Videos bleiben unberührt. */
    suspend fun delete(uuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        dao.tombstoneAllAssignments(uuid, now)
        dao.tombstone(uuid, now)
    }

    suspend fun usedBy(mediaUuids: List<String>): Set<String> =
        mediaUuids.chunked(CHUNK).flatMap { dao.usedCategories(it) }.toSet()

    suspend fun assign(mediaUuids: List<String>, categoryUuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        for (chunk in mediaUuids.chunked(CHUNK)) {
            val existing = dao.assignmentsFor(categoryUuid, chunk).associateBy { it.entityUuid }
            val items = chunk.map { media ->
                val old = existing[media]
                when {
                    // Alte, als gelöscht markierte Zuordnung wird wiederbelebt, statt eine zweite anzulegen.
                    old != null -> old.copy(deletedAt = null, updatedAt = now, userUpdatedAt = now)
                    else -> CategoryAssignmentEntity(
                        entityType = EntityType.MEDIA,
                        entityUuid = media,
                        categoryUuid = categoryUuid,
                        createdAt = now,
                    )
                }
            }
            dao.upsertAssignments(items)
        }
        touchMedia(mediaUuids, now)
    }

    suspend fun unassign(mediaUuids: List<String>, categoryUuid: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        mediaUuids.chunked(CHUNK).forEach { dao.tombstoneAssignments(categoryUuid, it, now) }
        touchMedia(mediaUuids, now)
    }

    /** Hinweis für Paging: Die Liste lädt neu, weil sich an den Medien etwas geändert hat. */
    private suspend fun touchMedia(mediaUuids: List<String>, now: Long) {
        mediaUuids.chunked(CHUNK).forEach { mediaDao.touch(it, now) }
    }

    private companion object {
        const val CHUNK = 500
        const val MAX_NAME = 60
    }
}
