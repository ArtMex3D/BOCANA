package com.cesar.bocana.ui.products

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.repository.InventoryRepository
import com.cesar.bocana.databinding.FragmentAddEditProductBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale

class AddEditProductFragment : Fragment() {

    private var _binding: FragmentAddEditProductBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: InventoryRepository

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    private var isEditing = false
    private var editingProductId: String? = null
    private var currentProductData: Product? = null
    private var allProducts: List<Product> = emptyList() // Para el selector de Rector

    companion object {
        private const val TAG = "AddEditProductFragment"
        private const val ARG_PRODUCT_ID = "product_id"

        fun newInstance(productId: String? = null): AddEditProductFragment {
            return AddEditProductFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRODUCT_ID, productId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            editingProductId = it.getString(ARG_PRODUCT_ID)
            isEditing = editingProductId != null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditProductBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth
        val database = AppDatabase.getDatabase(requireContext())
        repository = InventoryRepository(database, firestore)

        if (isEditing) {
            loadProductData(editingProductId!!)
        } else {
            configureUiForAddMode()
        }
        setupListeners()
        setupCategorySelectors()
        return binding.root
    }

    private fun configureUiForAddMode() {
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Añadir Producto"
        binding.buttonSaveProduct.text = "Guardar Producto Nuevo"
        binding.dividerActions.visibility = View.GONE
        binding.switchActive.visibility = View.GONE
        binding.buttonDeleteProduct.visibility = View.GONE
        binding.switchRequiresPackaging.isChecked = false // Valor por defecto
        showLoading(false)
    }

    private fun configureUiForEditMode(product: Product) {
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Editar Producto"
        binding.buttonSaveProduct.text = "Actualizar Datos"
        binding.dividerActions.visibility = View.VISIBLE
        binding.switchActive.visibility = View.VISIBLE
        binding.buttonDeleteProduct.visibility = View.VISIBLE
        binding.switchActive.text = if (product.isActive) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"
    }

    private fun loadProductData(productId: String) {
        showLoading(true)
        firestore.collection("products").document(productId).get()
            .addOnSuccessListener { document ->
                if (!isAdded || _binding == null) return@addOnSuccessListener
                showLoading(false)
                if (document != null && document.exists()) {
                    currentProductData = document.toObject(Product::class.java)
                    if (currentProductData != null) {
                        val product = currentProductData!!
                        binding.editTextProductName.setText(product.name)
                        binding.editTextMinStock.setText(String.format(Locale.getDefault(), "%.2f", product.minStock))
                        binding.editTextStockIdealC04.setText(String.format(Locale.getDefault(), "%.2f", product.stockIdealC04))
                        binding.switchActive.isChecked = product.isActive
                        binding.switchRequiresPackaging.isChecked = product.requiresPackaging
                        binding.autoCompleteCategoria.setText(product.categoria, false)
                        handleRectorSelectorVisibility(product.categoria)
                        configureUiForEditMode(product)
                    } else {
                        handleLoadError("Error al procesar datos del producto.")
                    }
                } else {
                    handleLoadError("Error: Producto no encontrado.")
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || _binding == null) return@addOnFailureListener
                showLoading(false)
                handleLoadError("Error de conexión al cargar: ${e.message}")
            }
    }

    private fun handleLoadError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        view?.let { Snackbar.make(it, errorMessage, Snackbar.LENGTH_LONG).show() }
        if (isAdded && !isStateSaved) {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupListeners() {
        binding.buttonSaveProduct.setOnClickListener {
            saveOrUpdateProduct()
        }

        binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
            if (isEditing && currentProductData != null && currentProductData?.isActive != isChecked) {
                updateProductSingleField(isChecked, "isActive", if (isChecked) "Producto activado" else "Producto archivado")
            }
        }

        binding.buttonDeleteProduct.setOnClickListener {
            if (isEditing && currentProductData != null) {
                showDeleteConfirmationDialog()
            }
        }
    }

    private fun setupCategorySelectors() {
        val categories = listOf("FIJO", "PESCADO_GRANDE", "PESCADO_CHICO")
        val categoryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        binding.autoCompleteCategoria.setAdapter(categoryAdapter)

        // Cargar todos los productos para el selector de rector
        lifecycleScope.launch {
            try {
                val snapshot = firestore.collection("products")
                    .whereEqualTo("isActive", true)
                    .orderBy("name")
                    .get().await()
                allProducts = snapshot.toObjects(Product::class.java)
                val productNames = allProducts.map { it.name }
                val rectorAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productNames)
                binding.autoCompleteProductoRector.setAdapter(rectorAdapter)

                // Si estamos editando, pre-seleccionar el producto rector
                currentProductData?.productoRectorId?.let { rectorId ->
                    val rectorProduct = allProducts.find { it.id == rectorId }
                    rectorProduct?.let {
                        binding.autoCompleteProductoRector.setText(it.name, false)
                    }
                }
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(context, "Error al cargar productos para el selector.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.autoCompleteCategoria.setOnItemClickListener { _, _, _, _ ->
            val selectedCategory = binding.autoCompleteCategoria.text.toString()
            handleRectorSelectorVisibility(selectedCategory)
        }
    }

    private fun handleRectorSelectorVisibility(category: String) {
        if (category == "PESCADO_CHICO") {
            binding.textFieldLayoutProductoRector.visibility = View.VISIBLE
        } else {
            binding.textFieldLayoutProductoRector.visibility = View.GONE
            binding.autoCompleteProductoRector.setText("", false) // Limpiar selección si no aplica
        }
    }

    private fun validateInputFields(): Boolean {
        var isValid = true
        binding.textFieldLayoutProductName.error = null

        if (binding.editTextProductName.text.isNullOrBlank()) {
            binding.textFieldLayoutProductName.error = "Nombre obligatorio"
            isValid = false
        }
        return isValid
    }

    private fun saveOrUpdateProduct() {
        if (!validateInputFields()) return
        showLoading(true)

        val currentUser = auth.currentUser ?: return
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        val categoria = binding.autoCompleteCategoria.text.toString()
        val rectorName = binding.autoCompleteProductoRector.text.toString()
        val rectorId = if (categoria == "PESCADO_CHICO" && rectorName.isNotBlank()) {
            allProducts.find { it.name == rectorName }?.id
        } else {
            null
        }

        val productDataMap = mutableMapOf<String, Any?>(
            "name" to binding.editTextProductName.text.toString().trim(),
            "minStock" to (binding.editTextMinStock.text.toString().toDoubleOrNull() ?: 0.0),
            "stockIdealC04" to (binding.editTextStockIdealC04.text.toString().toDoubleOrNull() ?: 0.0),
            "requiresPackaging" to binding.switchRequiresPackaging.isChecked,
            "updatedAt" to FieldValue.serverTimestamp(),
            "lastUpdatedByName" to currentUserName,
            "categoria" to categoria,
            "productoRectorId" to rectorId
        )

        if (isEditing) {
            val productId = editingProductId ?: return
            firestore.collection("products").document(productId)
                .update(productDataMap)
                .addOnSuccessListener {
                    if (!isAdded) return@addOnSuccessListener
                    showLoading(false)
                    view?.let { Snackbar.make(it, "Producto actualizado con éxito.", Snackbar.LENGTH_SHORT).show() }
                    parentFragmentManager.popBackStack()
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    showLoading(false)
                    view?.let { Snackbar.make(it, "Error al actualizar: ${e.message}", Snackbar.LENGTH_LONG).show() }
                }
        } else {
            val requiresPackaging = binding.switchRequiresPackaging.isChecked
            val newProduct = Product(
                name = binding.editTextProductName.text.toString().trim(),
                minStock = binding.editTextMinStock.text.toString().toDoubleOrNull() ?: 0.0,
                stockIdealC04 = binding.editTextStockIdealC04.text.toString().toDoubleOrNull() ?: 0.0,
                requiresPackaging = requiresPackaging,
                categoria = categoria,
                productoRectorId = rectorId,
                modoManualPDF = requiresPackaging,
                lastUpdatedByName = currentUserName,
                createdAt = Date(),
                updatedAt = Date()
            )

            firestore.collection("products").add(newProduct)
                .addOnSuccessListener {
                    if (!isAdded) return@addOnSuccessListener
                    showLoading(false)
                    view?.let { Snackbar.make(it, "Producto guardado. Para editarlo, mantén presionado el item.", Snackbar.LENGTH_LONG).show() }
                    parentFragmentManager.popBackStack()
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    showLoading(false)
                    view?.let { Snackbar.make(it, "Error al guardar: ${e.message}", Snackbar.LENGTH_LONG).show() }
                }
        }
    }

    private fun updateProductSingleField(newStatus: Boolean, fieldName: String, message: String) {
        val productId = editingProductId ?: return
        showLoading(true)
        val currentUser = auth.currentUser
        val currentUserName = currentUser?.displayName ?: currentUser?.email ?: "Unknown"

        val statusUpdate = mapOf(
            fieldName to newStatus,
            "updatedAt" to FieldValue.serverTimestamp(),
            "lastUpdatedByName" to currentUserName
        )

        firestore.collection("products").document(productId)
            .update(statusUpdate)
            .addOnSuccessListener {
                if(!isAdded) return@addOnSuccessListener
                view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
                if (fieldName == "isActive") {
                    currentProductData = currentProductData?.copy(isActive = newStatus)
                    binding.switchActive.text = if (newStatus) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"
                }
                showLoading(false)
            }
            .addOnFailureListener { e ->
                if(!isAdded) return@addOnFailureListener
                view?.let { Snackbar.make(it, "Error al actualizar: ${e.message}", Snackbar.LENGTH_LONG).show() }
                if (fieldName == "isActive") {
                    binding.switchActive.isChecked = !newStatus
                }
                showLoading(false)
            }
    }

    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("¡BORRADO PERMANENTE!")
            .setMessage("¿Estás ABSOLUTAMENTE seguro de borrar '${currentProductData?.name}'?\n\nESTA ACCIÓN NO SE PUEDE DESHACER y afectará a todas las referencias existentes.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sí, Borrar Permanentemente") { _, _ ->
                deleteProductPermanently()
            }
            .show()
    }

    private fun deleteProductPermanently() {
        val productId = editingProductId ?: return
        showLoading(true)
        firestore.collection("products").document(productId)
            .delete()
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                lifecycleScope.launch {
                    repository.deleteProductById(productId)
                    if (isAdded) {
                        showLoading(false)
                        view?.let { Snackbar.make(it, "Producto borrado permanentemente.", Snackbar.LENGTH_SHORT).show() }
                        parentFragmentManager.popBackStack()
                    }
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                showLoading(false)
                view?.let { Snackbar.make(it, "Error al borrar: ${e.message}", Snackbar.LENGTH_LONG).show() }
            }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarSave.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonSaveProduct.isEnabled = !isLoading
            if (isEditing) {
                binding.switchActive.isEnabled = !isLoading
                binding.buttonDeleteProduct.isEnabled = !isLoading
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
