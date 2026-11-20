package de.hr.mediathek.data.story.organize

import de.hr.mediathek.data.db.SeriesEntity
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.StoryDao
import de.hr.mediathek.data.db.StoryFolderEntity
import de.hr.mediathek.data.db.StoryFolderItemEntity
import de.hr.mediathek.data.db.nowMillis
import de.hr.mediathek.data.story.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Ordnung in der Geschichten-Sammlung: Duplikate, Serien und eigene Ordner. */
@Singleton
class StoryOrganizer @Inject constructor(
    private val dao: StoryDao,
    private val settings: SettingDao,
) {
    // --- Duplikate ---

    /** Liest alle Texte nacheinander (nie alle gleichzeitig im Speicher) und gruppiert gleiche und ähnliche. */
    suspend fun scanDuplicates(onProgress: (done: Int, total: Int) -> Unit): List<DupGroup> = withContext(Dispatchers.Default) {
        val briefs = dao.briefs()
        val inputs = ArrayList<DupInput>(briefs.size)
        val sigs = HashMap<String, DuplicateDetector.Signature>()
        briefs.forEachIndexed { i, b ->
            coroutineContext.ensureActive()
            val text = dao.getText(b.uuid).orEmpty()
            val hash = b.textHash ?: if (text.isBlank()) null else TextCleaner.hash(text)
            inputs += DupInput(b.uuid, hash)
            DuplicateDetector.signature(text)?.let { sigs[b.uuid] = it }
            if (i % 25 == 0) onProgress(i, briefs.size)
        }
        onProgress(briefs.size, briefs.size)
        val ignored = ignoredKeys()
        DuplicateDetector.group(inputs, sigs).filter { groupKey(it.members) !in ignored }
    }

    private fun groupKey(members: List<String>) = members.sorted().joinToString(",")

    private suspend fun ignoredKeys(): Set<String> =
        settings.get(KEY_IGNORED)?.lineSequence()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** "Kein Duplikat": diese Gruppe wird künftig nicht mehr vorgeschlagen. */
    suspend fun ignoreGroup(members: List<String>) {
        val all = ignoredKeys() + groupKey(members)
        settings.set(SettingEntity(KEY_IGNORED, all.joinToString("\n")))
    }

    /**
     * Führt Duplikate zusammen: [keep] bleibt, die anderen kommen in den Papierkorb. Bewertung (höchste), Favorit,
     * "Weiterschreiben", Serie und Ordner der anderen gehen nicht verloren.
     */
    suspend fun merge(keep: String, others: List<String>) {
        val now = nowMillis()
        val kept = dao.get(keep) ?: return
        var rating = kept.rating
        var favorite = kept.favorite
        var cont = kept.continueFlag
        var seriesUuid = kept.seriesUuid
        var order = kept.seriesOrder
        for (o in others) {
            val s = dao.get(o) ?: continue
            rating = maxOf(rating, s.rating)
            favorite = favorite || s.favorite
            cont = cont || s.continueFlag
            if (seriesUuid == null && s.seriesUuid != null) {
                seriesUuid = s.seriesUuid
                order = s.seriesOrder
            }
        }
        dao.setFlags(keep, rating, favorite, cont, now)
        if (seriesUuid != kept.seriesUuid) dao.setSeries(keep, seriesUuid, order, now)
        for (o in others) {
            // Ordnerzuordnungen mitnehmen, aber keine doppelte Zuordnung zum selben Ordner anlegen.
            dao.moveFolderItems(o, keep, now)
            dao.softDelete(o, now)
        }
    }

    // --- Serien ---

    suspend fun suggestSeries(): List<SeriesProposal> {
        val briefs = dao.briefs()
        val names = HashMap<String, String>()
        briefs.mapNotNull { it.seriesUuid }.distinct().forEach { id -> dao.seriesName(id)?.let { names[id] = it } }
        return SeriesSuggester.suggest(
            briefs.map { SeriesCandidate(it.uuid, it.title, it.author, it.seriesUuid, it.createdAt) },
            names,
        )
    }

    /** Legt die Serie an (oder nimmt eine gleichnamige / die vorhandene) und ordnet die Teile zu. */
    suspend fun applySeries(proposal: SeriesProposal, name: String) {
        val now = nowMillis()
        val seriesUuid = proposal.existingSeriesUuid
            ?: dao.seriesByName(name.trim())?.uuid
            ?: SeriesEntity(name = name.trim().ifBlank { proposal.name }).also { dao.insertSeries(it) }.uuid
        // Hinter die schon vorhandenen Teile einsortieren, falls die Serie schon Kapitel hat.
        val offset = if (proposal.existingSeriesUuid != null) 0 else 0
        for (part in proposal.parts) dao.setSeries(part.uuid, seriesUuid, part.order + offset, now)
    }

    // --- Ordner ---

    suspend fun createFolder(name: String, parentUuid: String?): String {
        val f = StoryFolderEntity(name = name.trim().ifBlank { "Neuer Ordner" }, parentUuid = parentUuid)
        dao.insertFolder(f)
        return f.uuid
    }

    suspend fun renameFolder(uuid: String, name: String) {
        if (name.isNotBlank()) dao.renameFolder(uuid, name.trim(), nowMillis())
    }

    /** Löscht den Ordner (nicht die Geschichten); Unterordner rücken eine Ebene hoch. */
    suspend fun deleteFolder(folder: StoryFolderEntity) {
        val now = nowMillis()
        dao.reparentChildren(folder.uuid, folder.parentUuid, now)
        dao.softDeleteItemsOfFolder(folder.uuid, now)
        dao.softDeleteFolder(folder.uuid, now)
    }

    /** Ordnet eine Geschichte dem Ordner zu oder nimmt sie wieder heraus. */
    suspend fun setInFolder(folderUuid: String, storyUuid: String, inFolder: Boolean) {
        val now = nowMillis()
        val existing = dao.findFolderItem(folderUuid, storyUuid)
        when {
            inFolder && existing == null -> dao.insertFolderItem(StoryFolderItemEntity(folderUuid = folderUuid, storyUuid = storyUuid))
            inFolder && existing!!.deletedAt != null -> dao.setFolderItemDeleted(existing.uuid, null, now)
            !inFolder && existing != null && existing.deletedAt == null -> dao.setFolderItemDeleted(existing.uuid, now, now)
        }
    }

    suspend fun setManyInFolder(folderUuid: String, storyUuids: List<String>, inFolder: Boolean) {
        for (id in storyUuids) setInFolder(folderUuid, id, inFolder)
    }

    private companion object {
        const val KEY_IGNORED = "story_dup_ignored"
    }
}
