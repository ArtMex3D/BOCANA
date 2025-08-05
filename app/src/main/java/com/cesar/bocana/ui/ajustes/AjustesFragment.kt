package com.cesar.bocana.ui.ajustes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.*
import com.cesar.bocana.databinding.FragmentAjustesBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class AjustesFragment : Fragment(), MenuProvider {

    private var _binding: FragmentAjustesBinding? = null
    private val binding get() = _binding!!

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    // Datos del Fragmento
    private var allProducts: List<Product> = emptyList()
    private var selectedProduct: Product? = null
    private var allLotesInLocation: List<StockLot> = emptyList()
    private var selectedStockLot: StockLot? = null
    private var allSuppliers: List<Supplier> = emptyList()

    // Control de UI
    private val stockEpsilon = 0.01
    private var isSelectionFromList = false
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var selectedDateCalendar: Calendar = Calendar.getInstance()
    private var originalActivityTitle: CharSequence? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjustesBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Ajuste y Edición de Lote"
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        loadInitialData()
        setupListeners()
        updateFieldStates()
    }

    private fun loadInitialData() {
        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Carga productos y proveedores en paralelo para más eficiencia
                val productsDeferred = async { loadProductsForSelection() }
                val suppliersDeferred = async { loadSuppliersForSelection() }
                productsDeferred.await()
                suppliersDeferred.await()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                Log.e(TAG, "Error cargando datos iniciales", e)
                Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (isAdded) showLoading(false)
            }
        }
    }

    private suspend fun loadProductsForSelection() {
        val result = firestore.collection("products")
            .whereEqualTo("isActive", true)
            .orderBy("name").get().await()

        allProducts = result.toObjects(Product::class.java)
        val productNames = allProducts.map { it.name }

        if (isAdded) {
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productNames)
            binding.autoCompleteTextViewAjusteProduct.setAdapter(adapter)
        }
    }

    private suspend fun loadSuppliersForSelection() {
        try {
            val result = firestore.collection("suppliers").orderBy("name").get().await()
            allSuppliers = result.toObjects(Supplier::class.java)
            if (isAdded) {
                val supplierNames = allSuppliers.map { it.name }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, supplierNames)
                (binding.textFieldLayoutAjusteProveedor.editText as? AutoCompleteTextView)?.setAdapter(adapter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando proveedores", e)
            if (isAdded) Toast.makeText(context, "No se pudieron cargar los proveedores.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        binding.autoCompleteTextViewAjusteProduct.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            isSelectionFromList = true
            val selectedName = (binding.autoCompleteTextViewAjusteProduct.adapter.getItem(position) as String)
            selectedProduct = allProducts.find { it.name == selectedName }
            clearLoteSelection()
            binding.radioGroupAjusteLocation.clearCheck()
            updateFieldStates()
            view?.post { isSelectionFromList = false }
        }

        binding.autoCompleteTextViewAjusteProduct.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isSelectionFromList && selectedProduct != null && s.toString() != selectedProduct?.name) {
                    selectedProduct = null
                    clearLoteSelection()
                    binding.radioGroupAjusteLocation.clearCheck()
                    updateFieldStates()
                }
            }
        })

        binding.radioGroupAjusteLocation.setOnCheckedChangeListener { _, checkedId ->
            clearLoteSelection()
            if (checkedId != -1 && selectedProduct != null) {
                val location = if (checkedId == R.id.radioButtonMatriz) Location.MATRIZ else Location.CONGELADOR_04
                loadLotesForSelection(selectedProduct!!.id, location)
            }
            updateFieldStates()
        }

        binding.autoCompleteTextViewAjusteLote.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            isSelectionFromList = true
            selectedStockLot = allLotesInLocation.getOrNull(position)
            populateEditFields()
            updateFieldStates()
            view?.post { isSelectionFromList = false }
        }

        binding.buttonAjusteFecha.setOnClickListener { showDatePicker() }
        binding.buttonPerformAjuste.setOnClickListener { performAjusteLote() }
    }

    private fun loadLotesForSelection(productId: String, location: String) {
        showLoading(true)
        firestore.collection("inventoryLots")
            .whereEqualTo("productId", productId)
            .whereEqualTo("location", location)
            .whereEqualTo("isDepleted", false) // Solo lotes con stock
            .orderBy("receivedAt", Query.Direction.ASCENDING) // Primero los más viejos
            .get()
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener
                allLotesInLocation = result.documents.mapNotNull { doc ->
                    doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                }
                val loteDisplayStrings = allLotesInLocation.map { formatLoteForDisplay(it) }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, loteDisplayStrings)
                binding.autoCompleteTextViewAjusteLote.setAdapter(adapter)

                if (allLotesInLocation.isEmpty()) {
                    Toast.makeText(context, "No hay lotes activos en $location.", Toast.LENGTH_SHORT).show()
                }
                updateFieldStates()
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener { if (isAdded) showLoading(false) }
    }

    private fun populateEditFields() {
        selectedStockLot?.let { lote ->
            binding.editTextAjusteNewQuantity.setText(String.format(Locale.getDefault(), "%.2f", lote.currentQuantity))
            binding.autoCompleteTextViewAjusteProveedor.setText(lote.supplierName ?: "", false)
            selectedDateCalendar.time = lote.receivedAt ?: Date()
            binding.buttonAjusteFecha.text = dateFormat.format(selectedDateCalendar.time)
        }
    }

    private fun showDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Nueva Fecha de Recepción")
            .setSelection(selectedDateCalendar.timeInMillis)
            .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            val tz = TimeZone.getDefault()
            val cal = Calendar.getInstance(tz)
            cal.timeInMillis = selection
            selectedDateCalendar.time = cal.time
            binding.buttonAjusteFecha.text = dateFormat.format(selectedDateCalendar.time)
        }
        datePicker.show(parentFragmentManager, "DATE_PICKER_AJUSTE")
    }

    private fun performAjusteLote() {
        val loteAActualizar = selectedStockLot ?: return
        val nuevaCantidad = binding.editTextAjusteNewQuantity.text.toString().toDoubleOrNull()
        val nuevoProveedorNombre = binding.autoCompleteTextViewAjusteProveedor.text.toString().trim()
        val nuevaFecha = selectedDateCalendar.time
        val motivo = binding.editTextAjusteReason.text.toString().trim()

        if (nuevaCantidad == null || nuevaCantidad < 0) {
            binding.textFieldLayoutAjusteNewQuantity.error = "Cantidad inválida"
            return
        }
        if (motivo.isBlank()) {
            binding.textFieldLayoutAjusteReason.error = "El motivo es obligatorio"
            return
        }
        showLoading(true)

        val currentUser = auth.currentUser ?: run {
            Toast.makeText(context, "Error de autenticación.", Toast.LENGTH_SHORT).show()
            showLoading(false)
            return
        }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Desconocido"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val proveedorFinal = findOrCreateSupplier(nuevoProveedorNombre)
                firestore.runTransaction { transaction ->
                    val loteRef = firestore.collection("inventoryLots").document(loteAActualizar.id)
                    val productRef = firestore.collection("products").document(loteAActualizar.productId)

                    val currentLote = transaction.get(loteRef).toObject(StockLot::class.java)
                        ?: throw FirebaseFirestoreException("El lote a editar ya no existe.", FirebaseFirestoreException.Code.ABORTED)
                    val currentProduct = transaction.get(productRef).toObject(Product::class.java)
                        ?: throw FirebaseFirestoreException("El producto asociado ya no existe.", FirebaseFirestoreException.Code.ABORTED)

                    val updateData = mutableMapOf<String, Any?>()
                    val changes = mutableListOf<String>()
                    val cantidadDiferencia = nuevaCantidad - currentLote.currentQuantity

                    if (kotlin.math.abs(cantidadDiferencia) > stockEpsilon) {
                        updateData["currentQuantity"] = nuevaCantidad
                        updateData["isDepleted"] = nuevaCantidad <= stockEpsilon
                        changes.add("Cant: ${String.format("%.2f", currentLote.currentQuantity)} -> ${String.format("%.2f", nuevaCantidad)}")
                    }
                    if (currentLote.supplierName != (proveedorFinal?.name ?: "")) {
                        updateData["supplierId"] = proveedorFinal?.id
                        updateData["supplierName"] = proveedorFinal?.name
                        changes.add("Prov: ${currentLote.supplierName ?: "N/A"} -> ${proveedorFinal?.name ?: "N/A"}")
                    }
                    if (dateFormat.format(currentLote.receivedAt!!) != dateFormat.format(nuevaFecha)) {
                        updateData["receivedAt"] = nuevaFecha
                        changes.add("Fecha: ${dateFormat.format(currentLote.receivedAt!!)} -> ${dateFormat.format(nuevaFecha)}")
                    }

                    if (changes.isEmpty()) {
                        throw FirebaseFirestoreException("No se detectaron cambios para guardar.", FirebaseFirestoreException.Code.ABORTED)
                    }

                    transaction.update(loteRef, updateData)

                    if (kotlin.math.abs(cantidadDiferencia) > stockEpsilon) {
                        val stockField = if (currentLote.location == Location.MATRIZ) "stockMatriz" else "stockCongelador04"
                        transaction.update(productRef, stockField, FieldValue.increment(cantidadDiferencia))
                        transaction.update(productRef, "totalStock", FieldValue.increment(cantidadDiferencia))
                    }

                    val newMovementRef = firestore.collection("stockMovements").document()
                    val movement = StockMovement(
                        id = newMovementRef.id, userId = currentUser.uid, userName = currentUserName,
                        productId = currentProduct.id, productName = currentProduct.name, type = MovementType.AJUSTE_MANUAL,
                        quantity = kotlin.math.abs(cantidadDiferencia), locationFrom = Location.AJUSTE, locationTo = Location.AJUSTE,
                        reason = "Editó Lote: ${changes.joinToString(" | ")}. Motivo: $motivo",
                        affectedLotIds = listOf(loteAActualizar.id), timestamp = Date()
                    )
                    transaction.set(newMovementRef, movement)
                }.await()
                Toast.makeText(context, "Lote actualizado con éxito", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()

            } catch (e: Exception) {
                if (isAdded) Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (isAdded) showLoading(false)
            }
        }
    }

    private suspend fun findOrCreateSupplier(supplierName: String): Supplier? {
        if (supplierName.isBlank()) return null
        val existingSupplier = allSuppliers.find { it.name.equals(supplierName, ignoreCase = true) }
        if (existingSupplier != null) return existingSupplier

        val newSupplierData = Supplier(name = supplierName, isActive = true, createdAt = Date(), updatedAt = Date())
        val newDocRef = firestore.collection("suppliers").add(newSupplierData).await()
        val newSupplier = newSupplierData.copy(id = newDocRef.id)
        allSuppliers = allSuppliers + newSupplier
        return newSupplier
    }

    private fun formatLoteForDisplay(lote: StockLot): String {
        val recDate = lote.receivedAt?.let { d -> dateFormat.format(d) } ?: "N/A"
        val prov = lote.supplierName?.takeIf { it.isNotBlank() } ?: "S/P"
        val qty = String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)
        return "$recDate | $prov | $qty ${lote.unit}"
    }

    private fun clearLoteSelection() {
        selectedStockLot = null
        allLotesInLocation = emptyList()
        binding.autoCompleteTextViewAjusteLote.setText("", false)
        binding.autoCompleteTextViewAjusteLote.setAdapter(null)
        // Limpiar campos de edición
        binding.editTextAjusteNewQuantity.text?.clear()
        binding.autoCompleteTextViewAjusteProveedor.setText("", false)
        binding.editTextAjusteReason.text?.clear()
        binding.buttonAjusteFecha.text = "Cambiar Fecha de Recepción"
        updateFieldStates()
    }

    private fun updateFieldStates() {
        if (_binding == null) return
        val productSelected = selectedProduct != null
        val locationSelected = binding.radioGroupAjusteLocation.checkedRadioButtonId != -1
        val loteSelected = selectedStockLot != null

        binding.radioGroupAjusteLocation.isEnabled = productSelected
        binding.radioButtonMatriz.isEnabled = productSelected
        binding.radioButtonC04.isEnabled = productSelected

        binding.textFieldLayoutAjusteLote.isEnabled = locationSelected && allLotesInLocation.isNotEmpty()
        binding.editFieldsContainer.visibility = if (loteSelected) View.VISIBLE else View.GONE
        binding.buttonPerformAjuste.isEnabled = loteSelected
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarAjuste.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonPerformAjuste.isEnabled = !isLoading && selectedStockLot != null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onPrepareMenu(menu: Menu) {}
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title
            title = "Ajuste y Edición de Lote"
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = originalActivityTitle
            setDisplayHomeAsUpEnabled(false)
        }
    }

    companion object {
        private const val TAG = "AjustesFragment"
    }
}