package de.hr.mediathek.feature.products

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.BrandEntity
import de.hr.mediathek.data.db.CriterionCatalogEntity
import de.hr.mediathek.data.db.PhotoKind
import de.hr.mediathek.data.db.ProductEntity
import de.hr.mediathek.data.db.ProductPhotoEntity
import de.hr.mediathek.data.product.BrandNormalizer
import de.hr.mediathek.data.product.CriterionCatalog
import de.hr.mediathek.data.product.CriterionInput
import de.hr.mediathek.data.product.PriceParser
import de.hr.mediathek.data.product.ProductPhotoStore
import de.hr.mediathek.data.product.ProductRepository
import de.hr.mediathek.data.product.Stars
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProductForm(
    val loaded: Boolean = false,
    val title: String = "",
    val brandUuid: String? = null,
    val sizeLabel: String = "",
    val rawPrice: String = "",
    val rawUnit: String = "",
    val packSize: String = "",
    val weightEmpty: String = "",
    val weightFull: String = "",
    val sizes: String = "",
    val wouldBuy: Boolean? = null,
    val overallNote: String = "",
    val starsManual: Boolean = false,
    val stars: Int? = null,
    val favorite: Boolean = false,
    val inputs: Map<String, CriterionInput> = emptyMap(),
    val custom: List<Pair<String, CriterionInput>> = emptyList(),
    val saved: Boolean = true,
    val deleted: Boolean = false,
)

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: ProductRepository,
    private val photoStore: ProductPhotoStore,
) : ViewModel() {
    private val uuid: String = checkNotNull(handle["uuid"])
    private val _form = MutableStateFlow(ProductForm())
    val form: StateFlow<ProductForm> = _form

    val catalog = MutableStateFlow<List<CriterionCatalogEntity>>(emptyList())
    val brands: StateFlow<List<BrandEntity>> = repository.observeBrands()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val photos: StateFlow<List<ProductPhotoEntity>> = repository.observePhotos(uuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var base: ProductEntity? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val cat = repository.catalog()
            catalog.value = cat
            val p = repository.get(uuid) ?: return@launch
            base = p
            val keyByUuid = cat.associate { it.uuid to it.key }
            val values = repository.values(uuid)
            val inputs = LinkedHashMap<String, CriterionInput>()
            val custom = ArrayList<Pair<String, CriterionInput>>()
            for (v in values) {
                val input = CriterionInput(v.numberValue, v.textValue.orEmpty(), v.note.orEmpty())
                val key = v.criterionUuid?.let { keyByUuid[it] }
                if (key != null) inputs[key] = input else if (v.customLabel != null) custom += v.customLabel to input
            }
            _form.value = ProductForm(
                loaded = true, title = p.title, brandUuid = p.brandUuid, sizeLabel = p.sizeLabel.orEmpty(),
                rawPrice = p.rawPriceText.orEmpty(), rawUnit = p.rawUnitPriceText.orEmpty(), packSize = p.packSize?.toString().orEmpty(),
                weightEmpty = p.weightEmptyG?.let { trimNum(it) }.orEmpty(), weightFull = p.weightFullG?.let { trimNum(it) }.orEmpty(),
                sizes = p.sizesAvailableText.orEmpty(), wouldBuy = p.wouldBuyAgain, overallNote = p.overallNote.orEmpty(),
                starsManual = p.starsManual, stars = p.stars, favorite = p.favorite, inputs = inputs, custom = custom,
            )
        }
    }

    private fun trimNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    /** Aktuelle Sterne: manuell oder aus den Zahlenwerten berechnet. */
    fun effectiveStars(f: ProductForm): Int? =
        if (f.starsManual) f.stars else Stars.compute((f.inputs.values.mapNotNull { it.number } + f.custom.mapNotNull { it.second.number }))

    fun change(block: (ProductForm) -> ProductForm) {
        _form.value = block(_form.value).copy(saved = false)
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(AUTOSAVE_MS); persist() }
    }

    fun setTitle(title: String) {
        change { it.copy(title = title, sizeLabel = if (it.sizeLabel.isBlank()) BrandNormalizer.sizeOf(title).orEmpty() else it.sizeLabel) }
        if (_form.value.brandUuid == null) {
            viewModelScope.launch {
                val detected = BrandNormalizer.detect(title, repository.brandInfos())
                if (detected != null && _form.value.brandUuid == null) {
                    val brandUuid = repository.brandUuidFor(detected.name)
                    change { it.copy(brandUuid = brandUuid) }
                }
            }
        }
    }

    /** Korrigierte Markenzuordnung: der erste Titelbegriff wird als Alias gemerkt. */
    fun setBrand(brandUuid: String?) {
        change { it.copy(brandUuid = brandUuid) }
        val first = _form.value.title.trim().split(Regex("\\s+")).firstOrNull()
        if (brandUuid != null && !first.isNullOrBlank()) viewModelScope.launch { repository.rememberAlias(brandUuid, first) }
    }

    fun setInput(key: String, input: CriterionInput) = change { it.copy(inputs = it.inputs + (key to input)) }

    private suspend fun persist() {
        val f = _form.value
        val p = base ?: repository.get(uuid) ?: return
        val updated = p.copy(
            title = f.title.trim(), brandUuid = f.brandUuid, sizeLabel = f.sizeLabel.ifBlank { null },
            rawPriceText = f.rawPrice.ifBlank { null }, rawUnitPriceText = f.rawUnit.ifBlank { null },
            packSize = f.packSize.toIntOrNull(), weightEmptyG = f.weightEmpty.replace(',', '.').toDoubleOrNull(),
            weightFullG = f.weightFull.replace(',', '.').toDoubleOrNull(), sizesAvailableText = f.sizes.ifBlank { null },
            wouldBuyAgain = f.wouldBuy, overallNote = f.overallNote.ifBlank { null },
            starsManual = f.starsManual, stars = if (f.starsManual) f.stars else p.stars, favorite = f.favorite,
        )
        repository.save(updated, f.inputs, f.custom)
        base = repository.get(uuid)
        _form.value = _form.value.copy(saved = true, stars = if (f.starsManual) f.stars else base?.stars)
    }

    fun flush() {
        saveJob?.cancel()
        if (!_form.value.saved) viewModelScope.launch { persist() }
    }

    /** Preisvorschau für das Formular: Text, den der Parser aus dem Eingabewert ableitet. */
    fun pricePreview(f: ProductForm): String? {
        val r = repository.computeUnit(f.rawPrice, f.rawUnit, f.packSize.toIntOrNull())
        val mean = r.mean ?: return null
        return "%.2f € pro Stück".format(java.util.Locale.GERMANY, mean) + (if (r.uncertain) " (unsicher)" else "") + (if (r.min != r.max) " (Spanne)" else "")
    }

    fun delete() {
        saveJob?.cancel()
        viewModelScope.launch { repository.delete(uuid); _form.value = _form.value.copy(deleted = true) }
    }

    fun addPhoto(kind: PhotoKind, rowIndex: Int, source: Uri) {
        viewModelScope.launch {
            val name = "${kind.name.lowercase()}-$rowIndex-${UUID.randomUUID().toString().take(8)}.jpg"
            val path = photoStore.saveFrom(uuid, name, source)
            if (path != null) repository.addPhoto(uuid, kind, rowIndex, path)
        }
    }

    fun removePhoto(photoUuid: String) {
        viewModelScope.launch { repository.removePhoto(photoUuid) }
    }

    suspend fun loadPhoto(path: String, maxPx: Int): Bitmap? = photoStore.load(path, maxPx)

    @Suppress("unused") private fun unusedParser() = PriceParser.parse(null)
    @Suppress("unused") private val dataKeys = CriterionCatalog.DATA_KEYS

    private companion object {
        const val AUTOSAVE_MS = 1500L
    }
}
