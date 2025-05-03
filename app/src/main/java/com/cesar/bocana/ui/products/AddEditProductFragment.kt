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
import com.cesar.bocana.data.model.UserRole // Necesario para verificar rol si haces más estricto
import com.cesar.bocana.databinding.FragmentAddEditProductBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

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
        setupListeners() // Mover setupListeners aquí
        return binding.root
    }

    // onViewCreated ya no es necesario para listeners si se ponen en onCreateView después del binding

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
        // Ocultar acciones de editar/borrar
        binding.dividerActions.visibility = View.GONE
        binding.switchActive.visibility = View.GONE
        binding.buttonDeleteProduct.visibility = View.GONE
        showLoading(false) // Asegurar que no haya loading
    }

    private fun configureUiForEditMode() {
        val activity = requireActivity() as? AppCompatActivity
        binding.buttonSaveProduct.text = "Actualizar Datos" // Cambiar texto
        activity?.supportActionBar?.title = "Editar Producto"
        // Mostrar acciones de editar/borrar
        binding.dividerActions.visibility = View.VISIBLE
        binding.switchActive.visibility = View.VISIBLE
        binding.buttonDeleteProduct.visibility = View.VISIBLE
        // Configurar texto del Switch basado en el estado actual
        binding.switchActive.text = if (currentProductData?.isActive == true) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"

    }

    // Reemplaza esta función en AddEditProductFragment.kt
    private fun loadProductData(productId: String) {
        showLoading(true)
        binding.buttonSaveProduct.isEnabled = false
        binding.switchActive.isEnabled = false
        binding.buttonDeleteProduct.isEnabled = false

        firestore.collection("products").document(productId).get()
            .addOnSuccessListener { document ->
                if (_binding == null) return@addOnSuccessListener
                if (document != null && document.exists()) {
                    currentProductData = document.toObject(Product::class.java)
                    if (currentProductData != null) {
                        binding.editTextProductName.setText(currentProductData?.name)
                        binding.autoCompleteTextViewUnit.setText(currentProductData?.unit, false)
                        binding.editTextMinStock.setText(currentProductData?.minStock.toString())
                        binding.editTextProviderDetails.setText(currentProductData?.providerDetails)

                        // --- LOG AÑADIDO para depurar Switch ---
                        val isActiveValue = currentProductData?.isActive
                        Log.d(TAG, "loadProductData - Leyendo para producto ${currentProductData?.name}: isActive = $isActiveValue (Tipo: ${isActiveValue?.javaClass?.simpleName})")
                        // ---------------------------------------

                        // Establecer estado inicial del Switch (usando el valor leído)
                        binding.switchActive.isChecked = isActiveValue ?: true // Default a true si es null

                        configureUiForEditMode()
                    } else {
                        handleLoadError("Error al procesar datos.")
                    }
                } else {
                    handleLoadError("Error: Producto no encontrado.")
                }
                showLoading(false)
            }
            .addOnFailureListener { e ->
                if (_binding == null) return@addOnFailureListener
                handleLoadError("Error de conexión al cargar: ${e.message}")
                showLoading(false)
            }
    }
    private fun handleLoadError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        parentFragmentManager.popBackStack()
    }

    private fun setupListeners() {
        // Listener Botón Guardar/Actualizar
        binding.buttonSaveProduct.setOnClickListener {
            if (isEditing) {
                updateProductData() // Solo actualiza datos, no estado activo
            } else {
                saveNewProduct()
            }
        }

        // Listener Switch Activo/Archivado
        binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
            // Actualizar estado en Firestore inmediatamente al cambiar switch
            // Solo si estamos editando y el producto actual existe
            if (isEditing && currentProductData != null && currentProductData?.isActive != isChecked) {
                updateProductStatus(isChecked)
            }
        }

        // Listener Botón Borrar Permanente
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
            val minStock = binding.editTextMinStock.text.toString().toLongOrNull() ?: 0L
            if (minStock < 0) {
                binding.textFieldLayoutMinStock.error = "No negativo"; isValid = false
            } else { binding.textFieldLayoutMinStock.error = null }
        } catch (e: NumberFormatException) {
            binding.textFieldLayoutMinStock.error = "Número inválido"; isValid = false
        }
        return isValid
    }

    private fun showLoading(isLoading: Boolean) {
        if(_binding != null){
            binding.progressBarSave.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonSaveProduct.isEnabled = !isLoading
            // Habilitar/deshabilitar botones de acción solo si estamos editando
            if(isEditing){
                binding.switchActive.isEnabled = !isLoading
                binding.buttonDeleteProduct.isEnabled = !isLoading
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
            minStock = binding.editTextMinStock.text.toString().toLongOrNull() ?: 0L,
            providerDetails = binding.editTextProviderDetails.text.toString().trim(),
            stockMatriz = 0L, stockCongelador04 = 0L, totalStock = 0L,
            isActive = true, // Siempre activo al crear
            lastUpdatedByName = currentUserName
        )

        firestore.collection("products").add(newProduct)
            .addOnSuccessListener {
                Toast.makeText(context, "Producto añadido", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .addOnFailureListener { e ->
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Actualiza SOLO los datos editables, NO el estado 'isActive'
    private fun updateProductData() {
        val productId = editingProductId ?: return
        if (!validateInputFields()) { return }
        showLoading(true)
        val currentUser = auth.currentUser
        val currentUserName = currentUser?.displayName ?: currentUser?.email ?: "Unknown"

        val updatedData = mapOf(
            "name" to binding.editTextProductName.text.toString().trim(),
            "unit" to binding.autoCompleteTextViewUnit.text.toString(),
            "minStock" to (binding.editTextMinStock.text.toString().toLongOrNull() ?: 0L),
            "providerDetails" to binding.editTextProviderDetails.text.toString().trim(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "lastUpdatedByName" to currentUserName
            // NO incluimos 'isActive' aquí, se maneja por el Switch
        )

        firestore.collection("products").document(productId)
            .update(updatedData)
            .addOnSuccessListener {
                Toast.makeText(context, "Datos actualizados", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack() // Volver atrás después de guardar
            }
            .addOnFailureListener { e ->
                if(_binding != null) showLoading(false)
                Toast.makeText(context, "Error al actualizar datos: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Actualiza SOLO el estado Activo/Inactivo
    private fun updateProductStatus(newActiveState: Boolean) {
        val productId = editingProductId ?: return
        showLoading(true) // Mostrar carga mientras se actualiza estado
        val currentUser = auth.currentUser
        val currentUserName = currentUser?.displayName ?: currentUser?.email ?: "Unknown"
        val actionText = if (newActiveState) "activado" else "archivado"

        val statusUpdate = mapOf(
            "isActive" to newActiveState,
            "updatedAt" to FieldValue.serverTimestamp(),
            "lastUpdatedByName" to currentUserName
        )

        firestore.collection("products").document(productId)
            .update(statusUpdate)
            .addOnSuccessListener {
                Log.i(TAG, "Producto $actionText ID: $productId")
                Toast.makeText(context, "Producto $actionText", Toast.LENGTH_SHORT).show()
                currentProductData = currentProductData?.copy(isActive = newActiveState) // Actualizar estado local
                if(_binding != null) {
                    binding.switchActive.text = if (newActiveState) "Producto Activo (Visible)" else "Producto Archivado (Oculto)"
                    showLoading(false)
                }
                // No navegamos atrás, solo actualizamos estado
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al $actionText producto $productId", e)
                Toast.makeText(context, "Error al $actionText: ${e.message}", Toast.LENGTH_LONG).show()
                // Revertir visualmente el switch si falla
                if(_binding != null) {
                    binding.switchActive.isChecked = !newActiveState
                    showLoading(false)
                }
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
        showLoading(true) // Mostrar carga durante borrado
        Log.w(TAG, "BORRANDO PERMANENTEMENTE producto ID: $productId")

        firestore.collection("products").document(productId)
            .delete()
            .addOnSuccessListener {
                Log.i(TAG, "Producto BORRADO ID: $productId")
                Toast.makeText(context, "Producto borrado permanentemente.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack() // Volver a la lista anterior
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al BORRAR producto $productId", e)
                if(_binding != null) showLoading(false) // Ocultar si falla
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