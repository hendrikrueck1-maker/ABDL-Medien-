package de.hr.mediathek.data.product

import de.hr.mediathek.data.db.BrandDao
import de.hr.mediathek.data.db.BrandEntity
import de.hr.mediathek.data.db.CriterionCatalogEntity
import de.hr.mediathek.data.db.CriterionDao
import de.hr.mediathek.data.db.PhotoKind
import de.hr.mediathek.data.db.ProductCriterionValueEntity
import de.hr.mediathek.data.db.ProductDao
import de.hr.mediathek.data.db.ProductEntity
import de.hr.mediathek.data.db.ProductPhotoEntity
import de.hr.mediathek.data.db.ProductRow
import de.hr.mediathek.data.db.nowMillis
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Eingabewerte eines Kriteriums im Formular. */
data class CriterionInput(val number: Int? = null, val text: String = "", val note: String = "") {
    val isEmpty: Boolean get() = number == null && text.isBlank() && note.isBlank()
}

@Singleton
class ProductRepository @Inject constructor(
    private val dao: ProductDao,
    private val criterionDao: CriterionDao,
    private val brandDao: BrandDao,
    private val photoStore: ProductPhotoStore,
) {
    fun observeRows(): Flow<List<ProductRow>> = dao.observeActive()
    fun observeBrands(): Flow<List<BrandEntity>> = brandDao.observeAll()
    fun observe(uuid: String): Flow<ProductEntity?> = dao.observe(uuid)
    fun observePhotos(uuid: String): Flow<List<ProductPhotoEntity>> = dao.observePhotos(uuid)

    suspend fun get(uuid: String): ProductEntity? = dao.get(uuid)
    suspend fun duplicateOf(title: String): String? = dao.uuidByTitle(title.trim())

    /** Kriterienkatalog und Markenliste beim ersten Gebrauch anlegen. */
    suspend fun ensureSeeded() {
        if (criterionDao.count() == 0) {
            criterionDao.insertAll(
                CriterionCatalog.defaults.mapIndexed { i, d ->
                    CriterionCatalogEntity(
                        key = d.key, label = d.label, groupName = d.group, valueType = d.type,
                        sortOrder = i, catalogVersion = CriterionCatalog.VERSION,
                    )
                },
            )
        }
        if (brandDao.count() == 0) {
            for (b in BrandNormalizer.seed) brandDao.insert(BrandEntity(name = b.name, aliases = b.aliases.joinToString("\n")))
        }
    }

    suspend fun catalog(): List<CriterionCatalogEntity> { ensureSeeded(); return criterionDao.all() }
    suspend fun brands(): List<BrandEntity> { ensureSeeded(); return brandDao.all() }

    suspend fun brandInfos(): List<BrandInfo> =
        brands().map { BrandInfo(it.name, it.aliases.split('\n').filter { a -> a.isNotBlank() }) }

    /** Legt die Marke an (oder findet sie) und gibt ihre UUID zurück. */
    suspend fun brandUuidFor(name: String): String {
        val existing = brands().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.uuid
        val brand = BrandEntity(name = name.trim())
        brandDao.insert(brand)
        return brand.uuid
    }

    /** Merkt einen Alias, damit eine korrigierte Zuordnung beim nächsten Mal automatisch passt. */
    suspend fun rememberAlias(brandUuid: String, alias: String) {
        val b = brands().firstOrNull { it.uuid == brandUuid } ?: return
        val list = b.aliases.split('\n').filter { it.isNotBlank() }
        if (alias.isBlank() || alias.equals(b.name, true) || list.any { it.equals(alias, true) }) return
        brandDao.update(b.copy(aliases = (list + alias).joinToString("\n"), updatedAt = nowMillis(), userUpdatedAt = nowMillis()))
    }

    suspend fun createEmpty(): String {
        ensureSeeded()
        val p = ProductEntity(title = "")
        dao.insert(p)
        return p.uuid
    }

    suspend fun values(uuid: String): List<ProductCriterionValueEntity> = dao.values(uuid)

    /** Speichert Produkt und Kriterienwerte. Sterne werden automatisch berechnet, solange sie nicht manuell gesetzt sind. */
    suspend fun save(product: ProductEntity, inputs: Map<String, CriterionInput>, custom: List<Pair<String, CriterionInput>> = emptyList()) {
        val catalog = catalog()
        val numbers = inputs.mapNotNull { it.value.number } + custom.mapNotNull { it.second.number }
        val stars = if (product.starsManual) product.stars else Stars.compute(numbers.filter { it in 1..10 })
        val unit = computeUnit(product.rawPriceText, product.rawUnitPriceText, product.packSize)
        val now = nowMillis()
        dao.update(
            product.copy(
                stars = stars, unitPriceMin = unit.min, unitPriceMax = unit.max, updatedAt = now, userUpdatedAt = now,
            ),
        )
        val existing = dao.values(product.uuid)
        val keyByUuid = catalog.associate { it.uuid to it.key }
        val uuidByKey = catalog.associate { it.key to it.uuid }
        val seen = HashSet<String>()
        for ((key, input) in inputs) {
            val cUuid = uuidByKey[key] ?: continue
            val old = existing.firstOrNull { it.criterionUuid == cUuid }
            if (input.isEmpty) continue
            seen += cUuid
            write(old, product.uuid, cUuid, null, input, now)
        }
        for ((label, input) in custom) {
            if (input.isEmpty) continue
            val old = existing.firstOrNull { it.criterionUuid == null && it.customLabel == label }
            seen += "custom:$label"
            write(old, product.uuid, null, label, input, now)
        }
        for (old in existing) {
            val id = old.criterionUuid ?: "custom:${old.customLabel}"
            if (id !in seen) dao.updateValue(old.copy(deletedAt = now, updatedAt = now, userUpdatedAt = now))
        }
        @Suppress("UNUSED_VARIABLE") val unused = keyByUuid
    }

    private suspend fun write(
        old: ProductCriterionValueEntity?, productUuid: String, criterionUuid: String?, label: String?,
        input: CriterionInput, now: Long,
    ) {
        if (old == null) {
            dao.insertValue(
                ProductCriterionValueEntity(
                    productUuid = productUuid, criterionUuid = criterionUuid, customLabel = label,
                    numberValue = input.number, textValue = input.text.ifBlank { null }, note = input.note.ifBlank { null },
                ),
            )
        } else if (old.numberValue != input.number || old.textValue != input.text.ifBlank { null } || old.note != input.note.ifBlank { null }) {
            dao.updateValue(
                old.copy(
                    numberValue = input.number, textValue = input.text.ifBlank { null }, note = input.note.ifBlank { null },
                    updatedAt = now, userUpdatedAt = now,
                ),
            )
        }
    }

    /** Stückpreis: Rohtext des Stückpreises, sonst Packungspreis geteilt durch den Packungsinhalt. */
    fun computeUnit(rawPrice: String?, rawUnit: String?, packSize: Int?): PriceResult {
        if (!rawUnit.isNullOrBlank()) return PriceParser.parse(rawUnit)
        val price = PriceParser.parse(rawPrice)
        if (price.min != null && packSize != null && packSize > 1 && !price.fromPack) {
            return PriceResult(price.min / packSize, (price.max ?: price.min) / packSize, price.uncertain, true)
        }
        return price
    }

    suspend fun setFavorite(uuid: String, favorite: Boolean) {
        dao.get(uuid)?.let { dao.update(it.copy(favorite = favorite, updatedAt = nowMillis(), userUpdatedAt = nowMillis())) }
    }

    suspend fun delete(uuid: String) = dao.softDelete(uuid, nowMillis())

    suspend fun addPhoto(productUuid: String, kind: PhotoKind, rowIndex: Int, path: String) {
        dao.insertPhoto(ProductPhotoEntity(productUuid = productUuid, kind = kind, rowIndex = rowIndex, filePath = path))
    }

    suspend fun removePhoto(uuid: String) {
        val photo = dao.photo(uuid) ?: return
        dao.softDeletePhoto(uuid, nowMillis())
        photoStore.delete(photo.filePath)
    }

    suspend fun photos(uuid: String): List<ProductPhotoEntity> = dao.photos(uuid)

    suspend fun exportProducts(rows: List<String>): List<ExportProduct> {
        val brands = brands().associateBy { it.uuid }
        val catalog = catalog()
        val keyByUuid = catalog.associate { it.uuid to it.key }
        return rows.mapNotNull { uuid ->
            val p = dao.get(uuid) ?: return@mapNotNull null
            val values = dao.values(uuid).mapNotNull { v ->
                val key = v.criterionUuid?.let { keyByUuid[it] } ?: return@mapNotNull null
                key to Triple(v.numberValue, v.textValue, v.note)
            }.toMap()
            ExportProduct(
                title = p.title, brand = p.brandUuid?.let { brands[it]?.name }, size = p.sizeLabel, stars = p.stars,
                favorite = p.favorite, price = p.rawPriceText,
                unitPrice = p.unitPriceOverride ?: PriceResult(p.unitPriceMin, p.unitPriceMax, false, false).mean,
                wouldBuyAgain = p.wouldBuyAgain, overallNote = p.overallNote, values = values,
            )
        }
    }
}
