package com.cesar.bocana.ui.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.FragmentCategoryMigrationBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CategoryMigrationFragment : Fragment() {

    private var _binding: FragmentCategoryMigrationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CategoryMigrationViewModel by viewModels()
    private lateinit var adapter: CategoryMigrationAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryMigrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Mantenimiento de Categorías"

        setupRecyclerView()
        observeViewModel()

        binding.fabSaveChanges.setOnClickListener {
            showConfirmationDialog()
        }
    }

    private fun setupRecyclerView() {
        // The adapter is initialized once the product list is available in the observer
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect {
                binding.progressBarMigration.isVisible = it
                binding.fabSaveChanges.isEnabled = !it
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { products ->
                if (products.isNotEmpty()) {
                    if (!::adapter.isInitialized) {
                        adapter = CategoryMigrationAdapter(
                            requireContext(),
                            products,
                            onCategoryChanged = { productId, newCategory ->
                                viewModel.updateProductCategory(productId, newCategory)
                            },
                            onRectorChanged = { productId, newRectorId ->
                                viewModel.updateProductRector(productId, newRectorId)
                            }
                        )
                        binding.recyclerViewCategoryMigration.adapter = adapter
                    }
                    adapter.submitList(products)
                }
            }
        }
    }

    private fun showConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Confirmar Cambios")
            .setMessage("¿Estás seguro de que quieres guardar estas categorías para todos los productos? Esta acción actualizará la base de datos.")
            .setPositiveButton("Sí, Guardar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        viewModel.saveAllChanges()
                        Toast.makeText(context, "¡Categorías actualizadas con éxito!", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
