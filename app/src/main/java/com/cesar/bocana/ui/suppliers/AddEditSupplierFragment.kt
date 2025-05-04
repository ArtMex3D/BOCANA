package com.cesar.bocana.ui.suppliers

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.data.model.Supplier
import com.cesar.bocana.databinding.FragmentAddEditSupplierBinding
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class AddEditSupplierFragment : Fragment() {

    private var _binding: FragmentAddEditSupplierBinding? = null
    private val binding get() = _binding!!

    private lateinit var firestore: FirebaseFirestore
    private var isEditing = false
    private var editingSupplierId: String? = null
    // Variable para guardar el estado actual que SÍ refleja Firestore (o el estado por defecto)
    private var currentActiveState: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            editingSupplierId = it.getString(ARG_SUPPLIER_ID)
            if (editingSupplierId != null) {
                isEditing = true
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditSupplierBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        setupViews()
        setupListeners()
        if (isEditing && editingSupplierId != null) {
            loadSupplierData(editingSupplierId!!)
        } else {
            // Para nuevo proveedor, asegurar estado inicial correcto
            currentActiveState = true
            binding.switchSupplierActive.isChecked = true
            binding.switchSupplierActive.text = "Proveedor Activo"
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupViews() {
        val activity = requireActivity() as? AppCompatActivity
        if (isEditing) {
            binding.buttonSaveSupplier.text = "Actualizar Proveedor"
            activity?.supportActionBar?.title = "Editar Proveedor"
            binding.dividerSupplierActions.visibility = View.VISIBLE
            binding.switchSupplierActive.visibility = View.VISIBLE
        } else {
            binding.buttonSaveSupplier.text = "Guardar Nuevo Proveedor"
            activity?.supportActionBar?.title = "Añadir Proveedor"
            binding.dividerSupplierActions.visibility = View.GONE
            binding.switchSupplierActive.visibility = View.GONE
        }
        showLoading(false)
    }

    private fun loadSupplierData(supplierId: String) {
        showLoading(true)
        firestore.collection("suppliers").document(supplierId).get()
            .addOnSuccessListener { document ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                showLoading(false)
                if (document != null && document.exists()) {
                    val supplier = document.toObject(Supplier::class.java)
                    Log.d(TAG, "Datos cargados: ${supplier?.name}, isActive=${supplier?.isActive}")

                    if (supplier != null) {
                        binding.editTextSupplierName.setText(supplier.name)
                        binding.editTextSupplierContact.setText(supplier.contactPerson ?: "")
                        binding.editTextSupplierPhone.setText(supplier.phone ?: "")
                        binding.editTextSupplierEmail.setText(supplier.email ?: "")

                        // Establecer estado REAL cargado
                        currentActiveState = supplier.isActive
                        binding.switchSupplierActive.isChecked = currentActiveState
                        binding.switchSupplierActive.text = if (currentActiveState) "Proveedor Activo" else "Proveedor Inactivo"

                    } else {
                        handleLoadError("Error al procesar datos del proveedor.")
                    }
                } else {
                    handleLoadError("Error: Proveedor no encontrado.")
                }
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                showLoading(false)
                handleLoadError("Error de conexión al cargar: ${e.message}")
            }
    }

    private fun handleLoadError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        // Comprobar contexto antes de mostrar Toast
        context?.let { Toast.makeText(it, errorMessage, Toast.LENGTH_LONG).show() }
        // Evitar popBackStack si ya se está yendo
        if (isAdded && !isStateSaved) {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupListeners() {
        binding.buttonSaveSupplier.setOnClickListener {
            saveOrUpdateSupplier()
        }
        // Listener del Switch: Actualiza texto y llama a guardar estado
        binding.switchSupplierActive.setOnCheckedChangeListener { _, isChecked ->
            binding.switchSupplierActive.text = if (isChecked) "Proveedor Activo" else "Proveedor Inactivo"
            // Llama a la función para guardar el cambio inmediatamente
            updateSupplierStatus(isChecked)
        }
    }

    // Función para guardar SOLO el estado (llamada por el Switch)
    private fun updateSupplierStatus(newActiveState: Boolean) {
        // Solo proceder si estamos editando, el ID es válido y el estado REALMENTE cambió
        if (!isEditing || editingSupplierId == null || currentActiveState == newActiveState) {
            Log.d(TAG, "updateSupplierStatus SKIPPED - Editing:$isEditing, ID:$editingSupplierId, CurrentState:$currentActiveState, NewState:$newActiveState")
            return
        }

        Log.d(TAG, "updateSupplierStatus RUNNING - ID:$editingSupplierId, NewState:$newActiveState")
        showLoading(true) // Mostrar progreso
        val supplierRef = firestore.collection("suppliers").document(editingSupplierId!!)
        val statusUpdate = mapOf(
            "isActive" to newActiveState,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        supplierRef.update(statusUpdate)
            .addOnSuccessListener {
                if (_binding == null || !isAdded) return@addOnSuccessListener
                Log.i(TAG, "Estado del proveedor $editingSupplierId actualizado a: $newActiveState")
                Toast.makeText(context, "Estado actualizado", Toast.LENGTH_SHORT).show()
                // Actualizar nuestro estado local AHORA que Firestore confirmó
                currentActiveState = newActiveState
                showLoading(false)
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                Log.e(TAG, "Error al actualizar estado del proveedor $editingSupplierId", e)
                Toast.makeText(context, "Error al actualizar estado: ${e.message}", Toast.LENGTH_LONG).show()
                // Revertir visualmente el switch si falla, usando el estado local anterior
                binding.switchSupplierActive.isChecked = currentActiveState
                binding.switchSupplierActive.text = if (currentActiveState) "Proveedor Activo" else "Proveedor Inactivo"
                showLoading(false)
            }
    }


    private fun validateInput(): Boolean {
        var isValid = true
        if (binding.editTextSupplierName.text.isNullOrBlank()) {
            binding.textFieldLayoutSupplierName.error = "Nombre obligatorio"
            isValid = false
        } else {
            binding.textFieldLayoutSupplierName.error = null
        }
        return isValid
    }

    private fun saveOrUpdateSupplier() {
        if (!validateInput()) { return }
        showLoading(true)

        val name = binding.editTextSupplierName.text.toString().trim()
        val contact = binding.editTextSupplierContact.text.toString().trim()
        val phone = binding.editTextSupplierPhone.text.toString().trim()
        val email = binding.editTextSupplierEmail.text.toString().trim()
        val isActive = binding.switchSupplierActive.isChecked // Tomar el valor actual visual del switch

        // Actualizamos el estado local por si acaso antes de crear el mapa
        currentActiveState = isActive

        val supplierData = hashMapOf<String, Any?>(
            "name" to name,
            "contactPerson" to (contact.ifEmpty { null }),
            "phone" to (phone.ifEmpty { null }),
            "email" to (email.ifEmpty { null }),
            "isActive" to isActive,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        if (isEditing && editingSupplierId != null) {
            firestore.collection("suppliers").document(editingSupplierId!!)
                .update(supplierData) // Update incluye isActive
                .addOnSuccessListener {
                    if(_binding != null) showLoading(false)
                    Toast.makeText(context, "Proveedor actualizado", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                .addOnFailureListener { e ->
                    if(_binding != null) showLoading(false)
                    Toast.makeText(context, "Error al actualizar: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            supplierData["createdAt"] = FieldValue.serverTimestamp()
            firestore.collection("suppliers").add(supplierData) // Add incluye isActive
                .addOnSuccessListener {
                    if(_binding != null) showLoading(false)
                    Toast.makeText(context, "Proveedor guardado", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                .addOnFailureListener { e ->
                    if(_binding != null) showLoading(false)
                    Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if(_binding != null){
            binding.progressBarSaveSupplier.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonSaveSupplier.isEnabled = !isLoading
            if(isEditing){
                binding.switchSupplierActive.isEnabled = !isLoading
            }
        }
    }

    companion object {
        private const val TAG = "AddEditSupplierFragment"
        private const val ARG_SUPPLIER_ID = "supplier_id"

        fun newInstance(supplierId: String? = null): AddEditSupplierFragment {
            val fragment = AddEditSupplierFragment()
            if (supplierId != null) {
                val args = Bundle()
                args.putString(ARG_SUPPLIER_ID, supplierId)
                fragment.arguments = args
            }
            return fragment
        }
    }
}