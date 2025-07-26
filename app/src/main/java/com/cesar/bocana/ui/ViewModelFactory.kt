package com.cesar.bocana.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cesar.bocana.data.repository.InventoryRepository
import com.cesar.bocana.ui.archived.ArchivedProductsViewModel
import com.cesar.bocana.ui.devoluciones.DevolucionesViewModel
import com.cesar.bocana.ui.packaging.PackagingViewModel
import com.cesar.bocana.ui.products.ProductListViewModel
import com.cesar.bocana.ui.suppliers.SupplierViewModel

@Suppress("UNCHECKED_CAST")
class ViewModelFactory(private val repository: InventoryRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ProductListViewModel::class.java) -> {
                ProductListViewModel(repository) as T
            }
            modelClass.isAssignableFrom(SupplierViewModel::class.java) -> {
                SupplierViewModel(repository) as T
            }
            modelClass.isAssignableFrom(PackagingViewModel::class.java) -> {
                PackagingViewModel(repository) as T
            }
            modelClass.isAssignableFrom(DevolucionesViewModel::class.java) -> {
                DevolucionesViewModel(repository) as T
            }
            modelClass.isAssignableFrom(ArchivedProductsViewModel::class.java) -> {
                ArchivedProductsViewModel(repository) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

