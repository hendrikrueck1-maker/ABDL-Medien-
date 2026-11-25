package de.hr.mediathek.data.story

import de.hr.mediathek.data.db.StoryCategory
import de.hr.mediathek.data.db.StoryDao
import de.hr.mediathek.data.db.StoryDraftVersionEntity
import de.hr.mediathek.data.db.StoryEntity
import de.hr.mediathek.data.db.StoryRow
import de.hr.mediathek.data.db.StoryTextEntity
import de.hr.mediathek.data.db.nowMillis
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoryRepository @Inject constructor(private val dao: StoryDao) {
    fun observeActive(): Flow<List<StoryRow>> = dao.observeActive()
    fun observeDeleted(): Flow<List<StoryRow>> = dao.observeDeleted()
    fun observe(uuid: String): Flow<StoryEntity?> = dao.observe(uuid)
    fun recentSources(): Flow<List<String>> = dao.observeRecentSources()
    fun recentUrls(): Flow<List<String>> = dao.observeRecentUrls()
    fun tagNames(uuid: String): Flow<List<String>> = dao.observeTagNames(uuid)
    fun seriesParts(seriesUuid: String): Flow<List<de.hr.mediathek.data.db.SeriesPart>> = dao.observeSeriesParts(seriesUuid)
    suspend fun seriesName(seriesUuid: String): String? = dao.seriesName(seriesUuid)

    suspend fun get(uuid: String): StoryEntity? = dao.get(uuid)
    suspend fun text(uuid: String): String = dao.getText(uuid) ?: ""
    suspend fun duplicateTitle(text: String): String? = dao.titleByHash(TextCleaner.hash(text))

    /** Legt eine Geschichte samt Text an. */
    suspend fun create(
        title: String,
        text: String,
        category: StoryCategory = StoryCategory.MIXED,
        sourceText: String? = null,
        sourceUrl: String? = null,
    ): String {
        val story = StoryEntity(
            title = title.ifBlank { "Ohne Titel" },
            category = category,
            sourceText = sourceText?.ifBlank { null },
            sourceUrl = sourceUrl?.ifBlank { null },
            textHash = if (text.isBlank()) null else TextCleaner.hash(text),
        )
        dao.insert(story.copy(wordCount = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }))
        dao.upsertText(StoryTextEntity(story.uuid, text))
        return story.uuid
    }

    suspend fun update(story: StoryEntity) {
        val now = nowMillis()
        dao.update(story.copy(updatedAt = now, userUpdatedAt = now))
    }

    /** Speichert den Text; legt höchstens alle fünf Minuten eine Entwurfsversion an (die letzten zehn bleiben). */
    suspend fun saveText(uuid: String, text: String) {
        val old = dao.getText(uuid)
        if (old == text) return
        val now = nowMillis()
        if (old != null && old.isNotBlank()) {
            val last = dao.lastDraftAt(uuid)
            if (last == null || now - last >= DRAFT_INTERVAL_MS) {
                dao.insertDraft(StoryDraftVersionEntity(storyUuid = uuid, text = old))
                dao.pruneDrafts(uuid, MAX_DRAFTS)
            }
        }
        dao.upsertText(StoryTextEntity(uuid, text))
        dao.get(uuid)?.let { dao.update(it.copy(textHash = TextCleaner.hash(text), wordCount = text.trim().split(Regex("\\s+")).count { w -> w.isNotEmpty() }, updatedAt = now, userUpdatedAt = now)) }
    }

    suspend fun drafts(uuid: String): List<StoryDraftVersionEntity> = dao.drafts(uuid)

    /** Merkt die Leseposition und den Fortschritt; nur wirklich gelesen (nicht bloß geöffnet) zählt als "zuletzt gelesen". */
    suspend fun setRating(uuid: String, rating: Int) = dao.setRating(uuid, rating, nowMillis())
    suspend fun setFavorite(uuid: String, favorite: Boolean) = dao.setFavorite(uuid, favorite, nowMillis())
    suspend fun setContinue(uuid: String, flag: Boolean) = dao.setContinue(uuid, flag, nowMillis())

    suspend fun saveReading(uuid: String, paragraph: Int, offset: Int, paragraphCount: Int) {
        val progress = if (paragraphCount <= 1) 0f else (paragraph.toFloat() / (paragraphCount - 1)).coerceIn(0f, 1f)
        if (paragraph == 0 && offset == 0) {
            dao.setReading(uuid, 0, 0)
            return
        }
        dao.setReadingFull(uuid, paragraph, offset, progress, nowMillis())
    }

    fun observeBookmarks(uuid: String) = dao.observeBookmarks(uuid)
    suspend fun addBookmark(storyUuid: String, paragraph: Int, label: String) {
        dao.insertBookmark(de.hr.mediathek.data.db.StoryBookmarkEntity(storyUuid = storyUuid, paragraph = paragraph, label = label))
    }
    suspend fun deleteBookmark(uuid: String) = dao.deleteBookmark(uuid, nowMillis())

    fun observeSeriesRows() = dao.observeSeriesRows()
    fun observeFolders() = dao.observeFolders()
    fun observeFolderItems() = dao.observeFolderItems()
    suspend fun backfillWordCounts() = dao.backfillWordCounts()

    suspend fun delete(uuid: String) = dao.softDelete(uuid, nowMillis())
    suspend fun restore(uuid: String) = dao.restore(uuid, nowMillis())
    suspend fun purge(uuid: String) = dao.purge(uuid)

    /** Geschichten im Papierkorb werden nach 30 Tagen endgültig entfernt. */
    suspend fun purgeExpired() {
        for (uuid in dao.expiredUuids(nowMillis() - TRASH_RETENTION_MS)) dao.purge(uuid)
    }

    companion object {
        const val MAX_DRAFTS = 10
        const val DRAFT_INTERVAL_MS = 5 * 60_000L
        const val TRASH_RETENTION_MS = 30L * 86_400_000L
    }
}
