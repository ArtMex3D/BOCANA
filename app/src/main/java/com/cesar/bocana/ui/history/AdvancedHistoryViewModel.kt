package com.cesar.bocana.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.cesar.bocana.data.InventoryRepository
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.StockMovement
import kotlinx.coroutines.flow.*
import java.util.Date

// Se define la clase de datos para los parámetros fuera del ViewModel para mayor claridad
private data class FilterParams(
    val startDate: Date? = null,
    val endDate: Date? = null,
    val movementTypes: List<MovementType>? = null,
    val freeText: String? = null,
    val lotIds: List<String>? = null // Añadir

)

class AdvancedHistoryViewModel(private val repository: InventoryRepository) : ViewModel() {

    private val _filterParams = MutableStateFlow(FilterParams())

    // --- Flujo de Resultados Paginados ---
    val movements: Flow<PagingData<StockMovement>> = _filterParams
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { params ->
            repository.getFilteredMovementsPaged(
                productId = null,
                startDate = params.startDate,
                endDate = params.endDate,
                movementTypes = params.movementTypes?.map { it.name },
                userName = null,
                supplierId = null,
                freeText = params.freeText,
                lotIds = params.lotIds // Añadir

            )
        }.cachedIn(viewModelScope)

    // --- Funciones para actualizar los filtros desde la UI ---
    fun setLotIdsFilter(lotIds: List<String>?) {
        _filterParams.update { it.copy(lotIds = lotIds) }
    }
    fun getLotIdsFilter(): List<String>? {
        return _filterParams.value.lotIds
    }
    fun setDateRangeFilter(startDate: Date?, endDate: Date?) {
        _filterParams.update { it.copy(startDate = startDate, endDate = endDate) }
    }
    fun setMovementTypesFilter(types: List<MovementType>?) {
        _filterParams.update { it.copy(movementTypes = types) }
    }
    fun setFreeTextFilter(text: String?) {
        _filterParams.update { it.copy(freeText = text) }
    }
}

class AdvancedHistoryViewModelFactory(private val repository: InventoryRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AdvancedHistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AdvancedHistoryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
