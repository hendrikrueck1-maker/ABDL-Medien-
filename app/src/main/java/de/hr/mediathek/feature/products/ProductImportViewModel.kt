package de.hr.mediathek.feature.products

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.CriterionCatalogEntity
import de.hr.mediathek.data.db.PhotoKind
import de.hr.mediathek.data.db.ProductEntity
import de.hr.mediathek.data.product.BrandNormalizer
import de.hr.mediathek.data.product.CriterionCatalog
import de.hr.mediathek.data.product.CriterionInput
import de.hr.mediathek.data.product.ExtractedImage
import de.hr.mediathek.data.product.ParsedProduct
import de.hr.mediathek.data.product.ProductPdfImporter
import de.hr.mediathek.data.product.ProductPhotoStore
import de.hr.mediathek.data.product.ProductRepository
import de.hr.mediathek.data.product.ProductTextParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Wohin eine nicht zugeordnete Zeile soll. */
sealed interface UnassignedTarget {
    data object Discard : UnassignedTarget
    data object Custom : UnassignedTarget
    data class Criterion(val key: String) : UnassignedTarget
}

class ImportProductItem(
    val id: Int,
    val name: String,
    val rawText: String?,
    val error: String?,
    val warnings: List<String>,
    val parsed: ParsedProduct?,
    val title: String,
    val brandName: String?,
    val duplicateOf: String?,
    val images: List<ExtractedImage>,
    val targets: List<UnassignedTarget>,
    val selected: Boolean,
)

data class ProductImportState(
    val loading: Boolean = false,
    val items: List<ImportProductItem> = emptyList(),
    val catalog: List<CriterionCatalogEntity> = emptyList(),
    val savedCount: Int? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class ProductImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: ProductPdfImporter,
    private val repository: ProductRepository,
    private val photoStore: ProductPhotoStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductImportState())
    val state: StateFlow<ProductImportState> = _state

    fun load(uris: List<Uri>) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, savedCount = null) }
            val catalog = repository.catalog()
            val brands = repository.brandInfos()
            val items = ArrayList<ImportProductItem>()
            for ((i, uri) in uris.withIndex()) {
                val name = displayName(uri)
                val raw = importer.read(uri, name)
                if (raw.error != null || raw.text == null) {
                    items += ImportProductItem(i, name, null, raw.error ?: "Datei nicht lesbar", raw.warnings, null, "", null, null, emptyList(), emptyList(), false)
                    continue
                }
                if (name.lowercase().startsWith("vorlage")) {
                    items += ImportProductItem(i, name, raw.text, "Vorlage wird nicht importiert", raw.warnings, null, "", null, null, emptyList(), emptyList(), false)
                    continue
                }
                val parsed = ProductTextParser.parse(raw.text)
                val title = parsed.title.ifBlank { name.substringBeforeLast('.') }
                val brand = BrandNormalizer.detect(title, brands)?.name
                items += ImportProductItem(
                    i, name, raw.text, null, raw.warnings, parsed, title, brand, repository.duplicateOf(title),
                    raw.images, parsed.unassigned.map { UnassignedTarget.Custom }, true,
                )
            }
            _state.update { it.copy(loading = false, items = items, catalog = catalog) }
        }
    }

    private fun mutate(id: Int, block: (ImportProductItem) -> ImportProductItem) {
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) block(it) else it }) }
    }

    private fun copyItem(
        it: ImportProductItem, title: String = it.title, brand: String? = it.brandName, images: List<ExtractedImage> = it.images,
        targets: List<UnassignedTarget> = it.targets, selected: Boolean = it.selected, duplicate: String? = it.duplicateOf,
    ) = ImportProductItem(it.id, it.name, it.rawText, it.error, it.warnings, it.parsed, title, brand, duplicate, images, targets, selected)

    fun setSelected(id: Int, v: Boolean) = mutate(id) { copyItem(it, selected = v) }
    fun setBrand(id: Int, name: String?) = mutate(id) { copyItem(it, brand = name?.ifBlank { null }) }

    fun setTitle(id: Int, title: String) {
        viewModelScope.launch {
            val dup = repository.duplicateOf(title)
            mutate(id) { copyItem(it, title = title, duplicate = dup) }
        }
    }

    fun setTarget(id: Int, index: Int, target: UnassignedTarget) =
        mutate(id) { item -> copyItem(item, targets = item.targets.mapIndexed { i, t -> if (i == index) target else t }) }

    fun cycleImage(id: Int, imageIndex: Int) = mutate(id) { item ->
        val img = item.images[imageIndex]
        val order = listOf(PhotoKind.PACKAGE, PhotoKind.DETAIL, PhotoKind.WORN_DRY, PhotoKind.WORN_WET, null)
        img.kind = order[(order.indexOf(img.kind) + 1) % order.size]
        copyItem(item)
    }

    fun saveSelected() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val catalog = _state.value.catalog
            var count = 0
            for (item in _state.value.items.filter { it.selected && it.parsed != null }) {
                saveOne(item, catalog)
                count++
            }
            _state.update { it.copy(saving = false, items = emptyList(), savedCount = count) }
        }
    }

    private suspend fun saveOne(item: ImportProductItem, catalog: List<CriterionCatalogEntity>) {
        val parsed = item.parsed ?: return
        val uuid = repository.createEmpty()
        val base = repository.get(uuid) ?: return
        val inputs = LinkedHashMap<String, CriterionInput>()
        val custom = ArrayList<Pair<String, CriterionInput>>()
        var product: ProductEntity = base.copy(title = item.title.trim())
        item.brandName?.let { name ->
            val brandUuid = repository.brandUuidFor(name)
            product = product.copy(brandUuid = brandUuid)
        }
        product = product.copy(sizeLabel = BrandNormalizer.sizeOf(item.title))
        for (c in parsed.criteria) {
            when (c.key) {
                CriterionCatalog.K_PRICE -> product = product.copy(rawPriceText = c.text ?: c.note)
                CriterionCatalog.K_UNIT_PRICE -> product = product.copy(rawUnitPriceText = c.text ?: c.note)
                CriterionCatalog.K_PACK -> product = product.copy(packSize = (c.text ?: c.note)?.filter { it.isDigit() }?.toIntOrNull())
                CriterionCatalog.K_WEIGHT_EMPTY -> product = product.copy(weightEmptyG = grams(c.text ?: c.note))
                CriterionCatalog.K_WEIGHT_FULL -> product = product.copy(weightFullG = grams(c.text ?: c.note))
                CriterionCatalog.K_SIZES -> product = product.copy(sizesAvailableText = c.text ?: c.note)
                CriterionCatalog.K_WOULD_BUY -> {
                    val t = (c.text ?: "").lowercase()
                    product = product.copy(wouldBuyAgain = if (t.startsWith("ja")) true else if (t.startsWith("nein")) false else null)
                }
                CriterionCatalog.K_OVERALL -> product = product.copy(overallNote = listOfNotNull(c.text, c.note).joinToString(" ").ifBlank { null })
                else -> inputs[c.key] = CriterionInput(c.number, c.text.orEmpty(), c.note.orEmpty())
            }
        }
        parsed.unassigned.forEachIndexed { i, line ->
            val input = CriterionInput(line.number, "", line.note.orEmpty())
            when (val t = item.targets.getOrNull(i) ?: UnassignedTarget.Custom) {
                UnassignedTarget.Discard -> {}
                UnassignedTarget.Custom -> custom += line.label to input
                is UnassignedTarget.Criterion -> if (t.key !in inputs) inputs[t.key] = input else custom += line.label to input
            }
        }
        repository.save(product, inputs, custom)
        // Fotos erst jetzt in den Arbeitsordner legen
        for (img in item.images) {
            val kind = img.kind ?: continue
            val name = "${kind.name.lowercase()}-${img.rowIndex}-${img.index}-p${img.page}.jpg"
            val path = photoStore.save(uuid, name, img.file.readBytes()) ?: continue
            repository.addPhoto(uuid, kind, img.rowIndex, path)
        }
        item.images.forEach { it.file.delete() }
    }

    private fun grams(raw: String?): Double? =
        raw?.let { Regex("\\d+(?:[.,]\\d+)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }

    fun reset() {
        _state.value.items.forEach { it.images.forEach { img -> img.file.delete() } }
        _state.value = ProductImportState()
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "Datei"
        }
        return uri.lastPathSegment ?: "Datei"
    }
}
