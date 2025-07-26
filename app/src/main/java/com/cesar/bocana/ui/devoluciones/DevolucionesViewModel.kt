package com.cesar.bocana.ui.devoluciones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.DevolucionPendiente
import com.cesar.bocana.data.repository.InventoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DevolucionesViewModel(repository: InventoryRepository) : ViewModel() {
    val devoluciones: StateFlow<List<DevolucionPendiente>> = repository.getDevolucionesStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}