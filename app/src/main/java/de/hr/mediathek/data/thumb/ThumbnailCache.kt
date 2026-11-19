package de.hr.mediathek.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.ThumbStats
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.db.ThumbnailDao
import de.hr.mediathek.data.db.ThumbnailEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vorschaubild-Cache im internen Speicher (filesDir/thumbs). Harte Obergrenze 2 GB; wird sie erreicht,
 * wird nicht still überschrieben, sondern gewarnt. Zugriffszeiten werden gesammelt und gebündelt geschrieben.
 */
@Singleton
class ThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ThumbnailDao,
    private val settingDao: SettingDao,
) {
    private val root: File get() = File(context.filesDir, "thumbs").also { it.mkdirs() }
    private val pendingTouches = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()

    fun observeStats(): Flow<ThumbStats> = dao.observeStats()
    fun observeFull(): Flow<Boolean> = settingDao.observe(KEY_FULL).map { it == "1" }
    fun observeLowSpace(): Flow<Boolean> = settingDao.observe(KEY_LOW_SPACE).map { it == "1" }

    private fun fileFor(uuid: String, variant: ThumbVariant): File {
        val dir = File(root, uuid.take(2)).also { it.mkdirs() }
        return File(dir, if (variant == ThumbVariant.SMALL) "$uuid.s.webp" else "$uuid.webp")
    }

    /** Liefert die Bilddatei oder null; die Zugriffszeit wird nur vorgemerkt, nicht sofort geschrieben. */
    fun fileIfPresent(uuid: String, variant: ThumbVariant): File? {
        val f = fileFor(uuid, variant)
        if (!f.isFile) return null
        pendingTouches[uuid] = System.currentTimeMillis()
        return f
    }

    suspend fun flushTouches() {
        if (pendingTouches.isEmpty()) return
        val snapshot = HashMap(pendingTouches)
        snapshot.keys.forEach { pendingTouches.remove(it) }
        val now = System.currentTimeMillis()
        snapshot.keys.chunked(500).forEach { dao.touch(it, now) }
    }

    /** Speichert ein Bild; gibt die Dateigröße zurück. */
    suspend fun store(mediaUuid: String, fingerprint: String?, bitmap: Bitmap, variant: ThumbVariant): Long =
        withContext(Dispatchers.IO) {
            val target = fileFor(mediaUuid, variant)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, if (variant == ThumbVariant.SMALL) 60 else 80, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Vorschaubild konnte nicht gespeichert werden")
            }
            // Das jeweils andere Format der Datei ist jetzt überholt.
            fileFor(mediaUuid, if (variant == ThumbVariant.SMALL) ThumbVariant.NORMAL else ThumbVariant.SMALL).delete()
            val now = System.currentTimeMillis()
            dao.upsert(ThumbnailEntity(mediaUuid, fingerprint, variant, target.length(), now, now))
            target.length()
        }

    suspend fun usedBytes(): Long = dao.totalBytes()

    /** Ist noch Platz für mindestens ein weiteres Bild? Setzt bzw. löscht die Warnung. */
    suspend fun hasRoom(): Boolean {
        val used = dao.totalBytes()
        val free = context.filesDir.usableSpace
        val limit = ThumbnailPolicy.effectiveLimit(used, free)
        val lowSpace = ThumbnailPolicy.isLowSpace(free)
        val full = used + RESERVE_BYTES > limit
        setFlag(KEY_LOW_SPACE, lowSpace)
        setFlag(KEY_FULL, full && !lowSpace)
        return !full && !lowSpace
    }

    /** Verkleinert den Cache bei wenig Speicher (älteste, online verfügbare Bilder zuerst). */
    suspend fun enforceLimit(): Int = lock.withLock {
        val used = dao.totalBytes()
        val limit = ThumbnailPolicy.effectiveLimit(used, context.filesDir.usableSpace)
        if (used <= limit) return 0
        val candidates = dao.evictableOnline(EVICT_BATCH).map { it.mediaUuid to it.sizeBytes }
        val plan = ThumbnailPolicy.planEviction(candidates, used, limit)
        remove(plan)
        plan.size
    }

    /** Bilder zu gelöschten Einträgen entfernen. */
    suspend fun removeOrphans(): Int {
        var total = 0
        while (true) {
            val batch = dao.orphans(500)
            if (batch.isEmpty()) break
            remove(batch)
            total += batch.size
        }
        return total
    }

    /** Offline-Dateien behalten ihr Bild nur noch im Kleinformat. */
    suspend fun shrinkOffline(): Int = withContext(Dispatchers.IO) {
        var count = 0
        while (true) {
            val batch = dao.offlineNormal(100)
            if (batch.isEmpty()) break
            for (t in batch) {
                val normal = fileFor(t.mediaUuid, ThumbVariant.NORMAL)
                val small = runCatching {
                    val bmp = BitmapFactory.decodeFile(normal.path) ?: return@runCatching null
                    val factor = ThumbnailPolicy.SMALL_PX.toFloat() / maxOf(bmp.width, bmp.height)
                    if (factor >= 1f) bmp else Bitmap.createScaledBitmap(
                        bmp, maxOf(1, (bmp.width * factor).toInt()), maxOf(1, (bmp.height * factor).toInt()), true,
                    )
                }.getOrNull()
                if (small != null) {
                    store(t.mediaUuid, t.fingerprint, small, ThumbVariant.SMALL)
                    normal.delete()
                    count++
                } else {
                    // Normalbild nicht lesbar: Eintrag entfernen, es wird bei Rückkehr neu erzeugt.
                    remove(listOf(t.mediaUuid))
                }
            }
        }
        count
    }

    suspend fun clearAll() = lock.withLock {
        withContext(Dispatchers.IO) { root.deleteRecursively() }
        dao.deleteAll()
        setFlag(KEY_FULL, false)
    }

    private suspend fun remove(uuids: List<String>) {
        if (uuids.isEmpty()) return
        withContext(Dispatchers.IO) {
            uuids.forEach {
                fileFor(it, ThumbVariant.NORMAL).delete()
                fileFor(it, ThumbVariant.SMALL).delete()
            }
        }
        uuids.chunked(500).forEach { dao.delete(it) }
    }

    private suspend fun setFlag(key: String, value: Boolean) {
        val wanted = if (value) "1" else "0"
        if (settingDao.get(key) != wanted) settingDao.set(SettingEntity(key, wanted))
    }

    companion object {
        const val KEY_FULL = "thumb_cache_full"
        const val KEY_LOW_SPACE = "thumb_cache_low_space"
        private const val RESERVE_BYTES = 200L * 1024
        private const val EVICT_BATCH = 5000
    }
}
