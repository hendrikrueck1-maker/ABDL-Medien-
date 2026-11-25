package de.hr.mediathek.data.tracker

import de.hr.mediathek.data.db.BrandDao
import de.hr.mediathek.data.db.ProductDao
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.TrackerDao
import de.hr.mediathek.data.db.TrackerEntryEntity
import de.hr.mediathek.data.db.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerRepository @Inject constructor(
    private val dao: TrackerDao,
    private val productDao: ProductDao,
    private val brandDao: BrandDao,
    private val settingDao: SettingDao,
) {
    fun observeDayStart(): Flow<Int> = settingDao.observe(KEY_DAY_START).map { it?.toIntOrNull() ?: TrackerStats.DEFAULT_DAY_START_MINUTES }
    suspend fun setDayStart(minutes: Int) = settingDao.set(SettingEntity(KEY_DAY_START, minutes.toString()))

    /** Alle Einträge mit Marke, für Liste und Statistik. */
    fun observeViews(): Flow<List<EntryView>> = combine(dao.observeAll(), brandDao.observeAll(), productDao.observeActive()) { entries, brands, products ->
        val brandByProduct = products.associate { it.uuid to it.brandUuid }
        val brandName = brands.associate { it.uuid to it.name }
        entries.map { e ->
            EntryView(
                e.uuid, e.instant, e.zoneId, e.productUuid, e.productNameSnapshot,
                e.productUuid?.let { brandByProduct[it] }?.let { brandName[it] }, e.unitPriceSnapshot, e.note,
            )
        }
    }

    /** Neuer Eintrag: Name und Stückpreis werden jetzt eingefroren. */
    suspend fun add(productUuid: String, note: String? = null, instant: Long = nowMillis()): String? {
        val p = productDao.get(productUuid) ?: return null
        val unit = p.unitPriceOverride ?: p.unitPriceMin?.let { lo -> (lo + (p.unitPriceMax ?: lo)) / 2.0 }
        val entry = TrackerEntryEntity(
            productUuid = productUuid, productNameSnapshot = p.title.ifBlank { "Ohne Titel" }, unitPriceSnapshot = unit,
            instant = instant, zoneId = ZoneId.systemDefault().id, note = note?.ifBlank { null },
        )
        dao.insert(entry)
        return entry.uuid
    }

    /** Freier Eintrag ohne Produkt (z. B. „Marke X, Größe M“), optional mit Stückpreis. */
    suspend fun addFree(name: String, unitPrice: Double? = null, note: String? = null, instant: Long = nowMillis()): String {
        val entry = TrackerEntryEntity(
            productUuid = null, productNameSnapshot = name.trim().ifBlank { "Eintrag" }, unitPriceSnapshot = unitPrice,
            instant = instant, zoneId = ZoneId.systemDefault().id, note = note?.ifBlank { null },
        )
        dao.insert(entry)
        return entry.uuid
    }

    suspend fun undo(uuid: String) = dao.softDelete(uuid, nowMillis())

    suspend fun get(uuid: String) = dao.get(uuid)

    /** Zeit, Produkt und Notiz ändern. Wechselt das Produkt, werden Name und Preis neu eingefroren. */
    suspend fun edit(uuid: String, instant: Long, productUuid: String?, note: String?) {
        val old = dao.get(uuid) ?: return
        var updated = old.copy(instant = instant, note = note?.ifBlank { null }, updatedAt = nowMillis(), userUpdatedAt = nowMillis())
        if (productUuid != null && productUuid != old.productUuid) {
            val p = productDao.get(productUuid)
            if (p != null) {
                val unit = p.unitPriceOverride ?: p.unitPriceMin?.let { lo -> (lo + (p.unitPriceMax ?: lo)) / 2.0 }
                updated = updated.copy(productUuid = productUuid, productNameSnapshot = p.title.ifBlank { "Ohne Titel" }, unitPriceSnapshot = unit)
            }
        }
        dao.update(updated)
    }

    suspend fun delete(uuid: String) = dao.softDelete(uuid, nowMillis())

    companion object {
        const val KEY_DAY_START = "tracker_day_start_minutes"
    }
}
