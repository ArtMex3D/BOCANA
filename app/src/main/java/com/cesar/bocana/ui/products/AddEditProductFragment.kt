package com.cesar.bocana.ui.products

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.FragmentAddEditProductBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Date
import java.util.Locale

class AddEditProductFragment : Fragment() {

    private var _binding: FragmentAddEditProductBinding? = null
    private val binding get() = _binding!!

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private val units = listOf("Kg", "Bolsas", "Cajas", "Piezas")

    private var isEditing = false
    private var editingProductId: String? = null
    private var currentProductData: Product? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            editingProductId = it.getString(ARG_PRODUCT_ID)
            if (editingProductId != null) {
                isEditing = true
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditProductBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth
        setupUnitSpinner()
        if (isEditing && editingProductId != null) {
            loadProductData(editingProductId!!)
        } else {
            configureUiForAddMode()
        }
        setupListeners()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUnitSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, units)
        (binding.textFieldLayoutProductUnit.editText as? AutoCompleteTextView)?.setAdapter(adapter)
    }

    private fun configureUiForAddMode() {
        val activity = requireActivity() as? AppCompatActivity
        binding.buttonSaveProduct.text = "Guardar Producto Nuevo"
        activity?.supportActionBar?.title = "Añadir Producto"
        binding.dividerActions.visibility = View.GONE
        binding.switchActive.visibility = View.GONE // Oculto para nuevos, se asume activo
        binding.buttonDeleteProduct.visibility = View.GONE
        binding.switchRequiresPackaging.isChecked = false
        showLoading(false)
    }

    private fun configureUiForEditMode() {
        val activity = requireActivity() as? AppCompatActivity
        binding.buttonSaveProduct.text = "Actualizar Datos"
        activity?.supportActionBar?.title = "Editar Producto"
        binding.dividerActions.visibility = View.VISIBLE
        binding.switchActive.visibility = View.VISIBLE
        binding.buttonDeleteProduct.visibility = View.VISIBLE
        binding.switchActive.text = if (currentProductData?.isActive == true) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"
        binding.switchRequiresPackaging.isChecked = currentProductData?.requiresPackaging ?: false
    }

    private fun loadProductData(productId: String) {
        showLoading(true)
        binding.buttonSaveProduct.isEnabled = false
        binding.switchActive.isEnabled = false
        binding.buttonDeleteProduct.isEnabled = false
        binding.switchRequiresPackaging.isEnabled = false

        firestore.collection("products").document(productId).get()
            .addOnSuccessListener { document ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                showLoading(false)
                binding.buttonSaveProduct.isEnabled = true
                binding.switchActive.isEnabled = true
                binding.buttonDeleteProduct.isEnabled = true
                binding.switchRequiresPackaging.isEnabled = true

                if (document != null && document.exists()) {
                    currentProductData = document.toObject(Product::class.java)
                    if (currentProductData != null) {
                        binding.editTextProductName.setText(currentProductData?.name)
                        binding.autoCompleteTextViewUnit.setText(currentProductData?.unit, false)
                        binding.editTextMinStock.setText(String.format(Locale.getDefault(), "%.2f", currentProductData?.minStock ?: 0.0))
                        binding.editTextProviderDetails.setText(currentProductData?.providerDetails)
                        binding.switchActive.isChecked = currentProductData?.isActive ?: true
                        binding.switchRequiresPackaging.isChecked = currentProductData?.requiresPackaging ?: false
                        configureUiForEditMode()
                    } else {
                        handleLoadError("Error al procesar datos del producto.")
                    }
                } else {
                    handleLoadError("Error: Producto no encontrado.")
                }
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                binding.buttonSaveProduct.isEnabled = true
                binding.switchActive.isEnabled = true
                binding.buttonDeleteProduct.isEnabled = true
                binding.switchRequiresPackaging.isEnabled = true
                handleLoadError("Error de conexión al cargar: ${e.message}")
                showLoading(false)
            }
    }

    private fun handleLoadError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        if (context != null) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        }
        if (isAdded && !isStateSaved) {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupListeners() {
        binding.buttonSaveProduct.setOnClickListener {
            if (isEditing) {
                updateProductData()
            } else {
                saveNewProduct()
            }
        }
        binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
            if (isEditing && currentProductData != null && currentProductData?.isActive != isChecked) {
                updateProductSingleField(isChecked, "isActive", if (isChecked) "Producto activado" else "Producto archivado")
            }
        }
        binding.switchRequiresPackaging.setOnCheckedChangeListener { _, isChecked ->
            if (isEditing && currentProductData != null && currentProductData?.requiresPackaging != isChecked) {
                updateProductSingleField(isChecked, "requiresPackaging", if (isChecked) "Requiere empaque: SÍ" else "Requiere empaque: NO")
            }
        }
        binding.buttonDeleteProduct.setOnClickListener {
            if (isEditing && currentProductData != null) {
                showDeleteConfirmationDialog()
            }
        }
    }

    private fun validateInputFields(): Boolean {
        var isValid = true
        if (binding.editTextProductName.text.isNullOrBlank()) {
            binding.textFieldLayoutProductName.error = "Nombre obligatorio"
            isValid = false
        } else { binding.textFieldLayoutProductName.error = null }

        val selectedUnit = binding.autoCompleteTextViewUnit.text.toString()
        if (selectedUnit.isEmpty() || !units.contains(selectedUnit)) {
            binding.textFieldLayoutProductUnit.error = "Selecciona unidad válida"
            isValid = false
        } else { binding.textFieldLayoutProductUnit.error = null }

        try {
            val minStockString = binding.editTextMinStock.text.toString()
            val minStock = minStockString.toDoubleOrNull()
            if (minStock == null) {
                binding.textFieldLayoutMinStock.error = "Número inválido"; isValid = false
            } else if (minStock < 0.0) {
                binding.textFieldLayoutMinStock.error = "No negativo"; isValid = false
            } else {
                binding.textFieldLayoutMinStock.error = null
            }
        } catch (e: NumberFormatException) {
            binding.textFieldLayoutMinStock.error = "Número inválido"; isValid = false
        }
        return isValid
    }

    private fun showLoading(isLoading: Boolean) {
        if(_binding != null){
            binding.progressBarSave.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonSaveProduct.isEnabled = !isLoading
            if(isEditing){
                binding.switchActive.isEnabled = !isLoading
                binding.buttonDeleteProduct.isEnabled = !isLoading
                binding.switchRequiresPackaging.isEnabled = !isLoading
            }
        }
    }

    private fun saveNewProduct() {
        if (!validateInputFields()) { return }
        showLoading(true)
        val currentUser = auth.currentUser
        val currentUserName = currentUser?.displayName ?: currentUser?.email ?: "Unknown"

        val newProduct = Product(
            name = binding.editTextProductName.text.toString().trim(),
            unit = binding.autoCompleteTextViewUnit.text.toString(),
            minStock = binding.editTextMinStock.text.toString().toDoubleOrNull() ?: 0.0,
            providerDetails = binding.editTextProviderDetails.text.toString().trim(),
            stockMatriz = 0.0, stockCongelador04 = 0.0, totalStock = 0.0,
            isActive = true,
            requiresPackaging = binding.switchRequiresPackaging.isChecked,
            lastUpdatedByName = currentUserName,
            createdAt = Date(),
            updatedAt = Date()
        )

        firestore.collection("products").add(newProduct)
            .addOnSuccessListener {
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Producto añadido", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .addOnFailureListener { e ->
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateProductData() {
        val productId = editingProductId ?: return
        if (!validateInputFields()) { return }
        showLoading(true)
        val currentUser = auth.currentUser
        val currentUserName = currentUser?.displayName ?: currentUser?.email ?: "Unknown"

        val updatedData = mapOf(
            "name" to binding.editTextProductName.text.toString().trim(),
            "unit" to binding.autoCompleteTextViewUnit.text.toString(),
            "minStock" to (binding.editTextMinStock.text.toString().toDoubleOrNull() ?: 0.0),
            "providerDetails" to binding.editTextProviderDetails.text.toString().trim(),
            "requiresPackaging" to binding.switchRequiresPackaging.isChecked,
            "isActive" to binding.switchActive.isChecked,
            "updatedAt" to FieldValue.serverTimestamp(),
            "lastUpdatedByName" to currentUserName
        )

        firestore.collection("products").document(productId)
            .update(updatedData)
            .addOnSuccessListener {
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Datos actualizados", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .addOnFailureListener { e ->
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Error al actualizar datos: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateProductSingleField(newStatus: Boolean, fieldName: String, toastMessage: String) {
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
                if(_binding == null || !isAdded) return@addOnSuccessListener
                Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                if (fieldName == "isActive") {
                    currentProductData = currentProductData?.copy(isActive = newStatus)
                    binding.switchActive.text = if (newStatus) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"
                } else if (fieldName == "requiresPackaging") {
                    currentProductData = currentProductData?.copy(requiresPackaging = newStatus)
                }
                showLoading(false)
            }
            .addOnFailureListener { e ->
                if(_binding == null || !isAdded) return@addOnFailureListener
                Toast.makeText(context, "Error al actualizar: ${e.message}", Toast.LENGTH_LONG).show()
                if (fieldName == "isActive") {
                    binding.switchActive.isChecked = !newStatus
                    binding.switchActive.text = if (!newStatus) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"
                } else if (fieldName == "requiresPackaging") {
                    binding.switchRequiresPackaging.isChecked = !newStatus
                }
                showLoading(false)
            }
    }

    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("¡BORRADO PERMANENTE!")
            .setMessage("¿Estás ABSOLUTAMENTE seguro de borrar '${currentProductData?.name}'?\n\nESTA ACCIÓN NO SE PUEDE DESHACER.")
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
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Producto borrado permanentemente.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .addOnFailureListener { e ->
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Error al borrar: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    companion object {
        private const val TAG = "AddEditProductFragment"
        private const val ARG_PRODUCT_ID = "product_id"

        fun newInstance(productId: String? = null): AddEditProductFragment {
            val fragment = AddEditProductFragment()
            if (productId != null) {
                val args = Bundle()
                args.putString(ARG_PRODUCT_ID, productId)
                fragment.arguments = args
            }
            return fragment
        }
    }
}