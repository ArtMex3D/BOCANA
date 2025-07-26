package com.cesar.bocana.ui.packaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.repository.InventoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class PackagingViewModel(repository: InventoryRepository) : ViewModel() {
    val packagingTasks: StateFlow<List<PendingPackagingTask>> = repository.getPackagingTasksStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
