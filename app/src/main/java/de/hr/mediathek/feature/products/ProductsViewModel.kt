package de.hr.mediathek.feature.products

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.BrandEntity
import de.hr.mediathek.data.db.ProductRow
import de.hr.mediathek.data.product.ProductExporter
import de.hr.mediathek.data.product.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProductSort(val label: String) { NEWEST("Neueste"), TITLE("Titel"), STARS("Sterne"), BRAND("Marke"), PRICE("Preis") }

data class ProductFilter(
    val query: String = "",
    val brandUuid: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: ProductSort = ProductSort.NEWEST,
)

data class ProductListItem(val row: ProductRow, val brandName: String?) {
    val unitPrice: Double? get() = row.unitPriceOverride ?: row.unitPriceMin?.let { lo -> (lo + (row.unitPriceMax ?: lo)) / 2 }
}

@HiltViewModel
class ProductsViewModel @Inject constructor(
    private val repository: ProductRepository,
    private val exporter: ProductExporter,
) : ViewModel() {
    private val _filter = MutableStateFlow(ProductFilter())
    val filter: StateFlow<ProductFilter> = _filter

    val brands: StateFlow<List<BrandEntity>> = repository.observeBrands()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<ProductListItem>> = combine(repository.observeRows(), brands, _filter) { rows, brands, f ->
        val byUuid = brands.associateBy { it.uuid }
        val q = f.query.trim().lowercase()
        val list = rows.map { ProductListItem(it, it.brandUuid?.let { b -> byUuid[b]?.name }) }.filter {
            (f.brandUuid == null || it.row.brandUuid == f.brandUuid) &&
                (!f.favoritesOnly || it.row.favorite) &&
                (q.isEmpty() || it.row.title.lowercase().contains(q) || it.brandName?.lowercase()?.contains(q) == true)
        }
        when (f.sort) {
            ProductSort.NEWEST -> list.sortedByDescending { it.row.createdAt }
            ProductSort.TITLE -> list.sortedBy { it.row.title.lowercase() }
            ProductSort.STARS -> list.sortedByDescending { it.row.stars ?: 0 }
            ProductSort.BRAND -> list.sortedBy { (it.brandName ?: "~").lowercase() }
            ProductSort.PRICE -> list.sortedBy { it.unitPrice ?: Double.MAX_VALUE }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    init {
        viewModelScope.launch { repository.ensureSeeded() }
    }

    fun update(block: (ProductFilter) -> ProductFilter) = _filter.update(block)

    suspend fun createEmpty(): String = repository.createEmpty()

    fun toggleFavorite(item: ProductListItem) {
        viewModelScope.launch { repository.setFavorite(item.row.uuid, !item.row.favorite) }
    }

    fun exportCsv(uri: Uri) = export { exporter.writeCsv(items.value.map { it.row.uuid }, uri) }
    fun exportPdf(uri: Uri) = export { exporter.writePdf(items.value.map { it.row.uuid }, uri) }

    private fun export(block: suspend () -> Result<Int>) {
        viewModelScope.launch {
            _message.value = block().fold(
                onSuccess = { "$it Produkte exportiert." },
                onFailure = { "Der Export ist fehlgeschlagen." },
            )
        }
    }
}
