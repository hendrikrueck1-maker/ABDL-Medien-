package de.hr.mediathek.data.story.mdimport

import androidx.room.withTransaction
import de.hr.mediathek.data.db.EntityType
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.StoryDao
import de.hr.mediathek.data.db.StoryDraftVersionEntity
import de.hr.mediathek.data.db.StoryEntity
import de.hr.mediathek.data.db.StoryTextEntity
import de.hr.mediathek.data.db.TagAssignmentEntity
import de.hr.mediathek.data.db.TagDao
import de.hr.mediathek.data.db.TagEntity
import de.hr.mediathek.data.db.newUuid
import de.hr.mediathek.data.story.TextCleaner
import de.hr.mediathek.data.tag.TagNames
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomImportStore @Inject constructor(
    private val db: MediathekDatabase,
    private val dao: StoryDao,
    private val tagDao: TagDao,
) : ImportStore {

    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun find(slug: String): ExistingStory? {
        val s = dao.getBySlug(slug) ?: return null
        return ExistingStory(
            uuid = s.uuid, trashed = s.deletedAt != null, hash = s.importHash, title = s.title, category = s.category,
            author = s.author, status = s.status, publishedAt = s.publishedAt,
            tags = dao.tagNames(s.uuid).map { TagNames.normalize(it) }.toSet(),
        )
    }

    override suspend fun duplicateOf(story: ParsedStory): String? =
        dao.titleByImportHash(story.hash, story.slug)
            ?: dao.titleByTextHashExcept(TextCleaner.hash(story.body), story.slug)

    override suspend fun insert(story: ParsedStory) {
        val entity = StoryEntity(
            title = story.title, category = story.category, sourceText = null,
            textHash = TextCleaner.hash(story.body),
            createdAt = story.createdAt, updatedAt = story.updatedAt, userUpdatedAt = story.updatedAt,
            slug = story.slug, author = story.author, status = story.status, publishedAt = story.publishedAt,
            importHash = story.hash,
        )
        dao.insert(entity)
        dao.upsertText(StoryTextEntity(entity.uuid, story.body))
        applyTags(entity.uuid, story.tags)
    }

    override suspend fun update(existing: ExistingStory, story: ParsedStory) {
        val entity = dao.get(existing.uuid) ?: return
        if (existing.hash != story.hash) {
            // Vorherigen Text als Entwurfsversion sichern, falls er in der App bearbeitet wurde.
            dao.getText(entity.uuid)?.takeIf { it.isNotBlank() }?.let {
                dao.insertDraft(StoryDraftVersionEntity(storyUuid = entity.uuid, text = it))
                dao.pruneDrafts(entity.uuid, MAX_DRAFTS)
            }
            dao.upsertText(StoryTextEntity(entity.uuid, story.body))
        }
        dao.update(
            entity.copy(
                title = story.title, category = story.category, author = story.author, status = story.status,
                publishedAt = story.publishedAt, importHash = story.hash, textHash = TextCleaner.hash(story.body),
                updatedAt = story.updatedAt, userUpdatedAt = story.updatedAt,
            ),
        )
        applyTags(entity.uuid, story.tags)
    }

    /** Gleicht die Tags der Geschichte mit der Liste ab; fehlende Tags werden angelegt. */
    private suspend fun applyTags(storyUuid: String, names: List<String>) {
        val now = System.currentTimeMillis()
        val desired = LinkedHashMap<String, String>() // tagUuid -> Name
        for (name in names) {
            val normalized = TagNames.normalize(name)
            val tag = tagDao.findByNormalized(normalized)
                ?: TagEntity(name = name, nameNormalized = normalized).also { tagDao.insert(it) }
            desired[tag.uuid] = tag.name
        }
        val current = tagDao.storyAssignments(storyUuid)
        val obsolete = current.filter { it.tagUuid !in desired }.map { it.uuid }
        if (obsolete.isNotEmpty()) tagDao.tombstoneAssignmentsByUuid(obsolete, now)
        val have = current.map { it.tagUuid }.toSet()
        val missing = desired.keys.filter { it !in have }
            .map { TagAssignmentEntity(uuid = newUuid(), entityType = EntityType.STORY, entityUuid = storyUuid, tagUuid = it) }
        if (missing.isNotEmpty()) tagDao.upsertAssignments(missing)
    }

    private companion object {
        const val MAX_DRAFTS = 10
    }
}
