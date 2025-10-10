package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.*
import com.cesar.bocana.databinding.DialogAddCompraBinding
import com.cesar.bocana.helpers.NotificationTriggerHelper
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward

class AddCompraDialogFragment : DialogFragment() {

    private var _binding: DialogAddCompraBinding? = null
    private val binding get() = _binding!!

    private var product: Product? = null
    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var selectedDate = Date()
    private var allSuppliers: List<Supplier> = emptyList()

    companion object {
        const val TAG = "AddCompraDialog"
        private const val ARG_PRODUCT = "product_arg"

        fun newInstance(product: Product): AddCompraDialogFragment {
            return AddCompraDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_PRODUCT, product)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firestore = Firebase.firestore
        product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_PRODUCT, Product::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_PRODUCT)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAddCompraBinding.inflate(LayoutInflater.from(context))
        val currentProduct = product ?: run {
            Toast.makeText(context, "Error: Producto no encontrado.", Toast.LENGTH_SHORT).show()
            return super.onCreateDialog(savedInstanceState)
        }

        setupUI(currentProduct)
        setupUnitSelector() // <- NUEVO
        loadSuppliers()
        setupListeners()

        val builder = AlertDialog.Builder(requireActivity()).setView(binding.root)
        val dialog = builder.create()
        dialog.setOnShowListener {
            binding.buttonDialogAceptar.setOnClickListener {
                validateAndPerformCompra(currentProduct)
            }
        }
        return dialog
    }

    private fun setupUI(product: Product) {
        binding.textViewDialogTitle.text = "Compra: ${product.name}"
        binding.buttonSelectDate.text = dateFormat.format(selectedDate)
        // Muestra sugerencias de proveedor desde el primer carácter
        binding.autoCompleteProveedor.threshold = 3
    }

    // NUEVA FUNCIÓN: Configura el menú desplegable de unidades
    private fun setupUnitSelector() {
        val units = listOf("Cajas", "Costales", "Bolsas", "Piezas", "Kg")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, units)
        binding.autoCompleteUnidadEmpaque.setAdapter(adapter)
    }

    private fun setupListeners() {
        binding.buttonDialogCancelar.setOnClickListener { dismiss() }
        binding.buttonSelectDate.setOnClickListener { showDatePicker() }

        binding.radioGroupReceptionType.setOnCheckedChangeListener { _, checkedId ->
            binding.layoutRecepcionUnidades.isVisible = checkedId == binding.radioButtonRecepcionUnidades.id
            binding.textFieldLayoutCantidadGranel.isVisible = checkedId == binding.radioButtonRecepcionGranel.id
            updateNetoInfoVisibility()
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateNetoInfoVisibility()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.editTextPesoPorUnidad.addTextChangedListener(textWatcher)
        binding.editTextCantidadUnidades.addTextChangedListener(textWatcher)
    }

    private fun updateNetoInfoVisibility() {
        val isUnidades = binding.radioButtonRecepcionUnidades.isChecked
        val cantidad = binding.editTextCantidadUnidades.text.toString().toDoubleOrNull() ?: 0.0
        val peso = binding.editTextPesoPorUnidad.text.toString().toDoubleOrNull() ?: 0.0

        if (isUnidades && cantidad > 0 && peso > 0) {
            val neto = cantidad * peso
            binding.textViewPesoNetoInfo.text = String.format(Locale.getDefault(), "= %.2f Kg Neto", neto)
            binding.textViewPesoNetoInfo.isVisible = true
        } else {
            binding.textViewPesoNetoInfo.isVisible = false
        }
    }

    private fun showDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Fecha de Recepción")
            .setSelection(selectedDate.time)
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
           val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utcCalendar.timeInMillis = selection

            val localCalendar = Calendar.getInstance()
            localCalendar.set(
                utcCalendar.get(Calendar.YEAR),
                utcCalendar.get(Calendar.MONTH),
                utcCalendar.get(Calendar.DAY_OF_MONTH)
            )

            selectedDate = localCalendar.time

            binding.buttonSelectDate.text = dateFormat.format(selectedDate)
        }
        datePicker.show(parentFragmentManager, "DATE_PICKER_COMPRA")
    }
    private fun loadSuppliers() {
        lifecycleScope.launch {
            try {
                val snapshot = firestore.collection("suppliers")
                    .whereEqualTo("isActive", true)
                    .orderBy("name").get().await()
                allSuppliers = snapshot.toObjects(Supplier::class.java)
                if (isAdded) {
                    val supplierNames = allSuppliers.map { it.name }
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, supplierNames)
                    binding.autoCompleteProveedor.setAdapter(adapter)
                }
            } catch (e: Exception) {
                if(isAdded) Toast.makeText(context, "Error al cargar proveedores", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validateAndPerformCompra(product: Product) {
        val supplierNameInput = binding.autoCompleteProveedor.text.toString().trim()
        var isValid = true

        var cantidadNetaKg = 0.0
        var unidadDeEmpaque: String? = null
        var pesoPorUnidad: Double? = null
        var cantidadInicialUnidades: Double? = null
        var isBulkReception = false

        when (binding.radioGroupReceptionType.checkedRadioButtonId) {
            binding.radioButtonRecepcionUnidades.id -> {
                isBulkReception = false
                // CORREGIDO: Lee desde el AutoCompleteTextView
                unidadDeEmpaque = binding.autoCompleteUnidadEmpaque.text.toString().trim()
                pesoPorUnidad = binding.editTextPesoPorUnidad.text.toString().toDoubleOrNull()
                cantidadInicialUnidades = binding.editTextCantidadUnidades.text.toString().toDoubleOrNull()

                if (unidadDeEmpaque.isNullOrEmpty()) {
                    binding.textFieldLayoutUnidadEmpaque.error = "Define la unidad"
                    isValid = false
                } else {
                    binding.textFieldLayoutUnidadEmpaque.error = null
                }
                if (pesoPorUnidad == null || pesoPorUnidad <= 0.0) {
                    binding.textFieldLayoutPesoPorUnidad.error = "Peso debe ser > 0"
                    isValid = false
                } else {
                    binding.textFieldLayoutPesoPorUnidad.error = null
                }
                if (cantidadInicialUnidades == null || cantidadInicialUnidades <= 0.0) {
                    binding.textFieldLayoutCantidadUnidades.error = "Cantidad debe ser > 0"
                    isValid = false
                } else {
                    binding.textFieldLayoutCantidadUnidades.error = null
                }

                if (isValid) {
                    cantidadNetaKg = pesoPorUnidad!! * cantidadInicialUnidades!!
                }
            }
            binding.radioButtonRecepcionGranel.id -> {
                isBulkReception = true
                val cantidadInput = binding.editTextCantidadGranel.text.toString().toDoubleOrNull()
                if (cantidadInput == null || cantidadInput <= 0.0) {
                    binding.textFieldLayoutCantidadGranel.error = "La cantidad debe ser mayor a 0"
                    isValid = false
                } else {
                    binding.textFieldLayoutCantidadGranel.error = null
                    cantidadNetaKg = cantidadInput
                }
            }
            else -> {
                Toast.makeText(context, "Selecciona un tipo de recepción", Toast.LENGTH_SHORT).show()
                isValid = false
            }
        }

        if (isValid) {
            lifecycleScope.launch {
                val supplier = findOrCreateSupplier(supplierNameInput)
                performCompra(product, cantidadNetaKg, supplier, isBulkReception, unidadDeEmpaque, pesoPorUnidad, cantidadInicialUnidades)
            }
        }
    }

    // NUEVA FUNCIÓN MEJORADA
    private suspend fun findOrCreateSupplier(supplierName: String): Supplier? {
        if (supplierName.isBlank()) return null
        val existingSupplier = allSuppliers.find { it.name.equals(supplierName, ignoreCase = true) }
        if (existingSupplier != null) {
            return existingSupplier
        }

        return try {
            val newSupplierData = Supplier(name = supplierName, isActive = true, createdAt = Date(), updatedAt = Date())
            val newDocRef = firestore.collection("suppliers").add(newSupplierData).await()
            val newSupplier = newSupplierData.copy(id = newDocRef.id)
            allSuppliers = allSuppliers + newSupplier
            newSupplier
        } catch (e: Exception) {
            Log.e(TAG, "Error al crear nuevo proveedor", e)
            null
        }
    }

    private fun performCompra(
        productArgument: Product,
        quantityValue: Double,
        supplier: Supplier?,
        isBulkReception: Boolean,
        unidadDeEmpaque: String?,
        pesoPorUnidad: Double?,
        cantidadInicialUnidades: Double?
    ) {
        val currentUser = auth.currentUser ?: return
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        binding.buttonDialogAceptar.isEnabled = false
        binding.buttonDialogCancelar.isEnabled = false

        lifecycleScope.launch {
            try {
                firestore.runTransaction { transaction ->
                    val productRef = firestore.collection("products").document(productArgument.id)
                    val newMovementRef = firestore.collection("stockMovements").document()
                    val newStockLotRef = firestore.collection("inventoryLots").document()

                    val productSnapshot = transaction.get(productRef)
                    val currentProduct = productSnapshot.toObject(Product::class.java)
                        ?: throw FirebaseFirestoreException("Producto no encontrado", FirebaseFirestoreException.Code.ABORTED)

                    val newStockMatriz = currentProduct.stockMatriz + quantityValue
                    val newTotalStock = newStockMatriz + currentProduct.stockCongelador04

                    val movement = StockMovement(
                        id = newMovementRef.id,
                        timestamp = selectedDate,
                        userId = currentUser.uid,
                        userName = currentUserName,
                        productId = currentProduct.id,
                        productName = currentProduct.name,
                        type = MovementType.COMPRA,
                        quantity = quantityValue,
                        locationFrom = Location.PROVEEDOR,
                        locationTo = Location.MATRIZ,
                        reason = if (supplier != null) "Compra a ${supplier.name}" else "Compra sin proveedor",
                        stockAfterMatriz = newStockMatriz,
                        stockAfterCongelador04 = currentProduct.stockCongelador04,
                        stockAfterTotal = newTotalStock,
                        affectedLotIds = listOf(newStockLotRef.id),
                        supplierId = supplier?.id
                    )
                    transaction.set(newMovementRef, movement)

                    val newLot = StockLot(
                        id = newStockLotRef.id,
                        productId = currentProduct.id,
                        productName = currentProduct.name,
                        unit = currentProduct.unit,
                        location = Location.MATRIZ,
                        supplierId = supplier?.id,
                        supplierName = supplier?.name,
                        receivedAt = selectedDate,
                        movementIdIn = newMovementRef.id,
                        initialQuantity = quantityValue,
                        currentQuantity = quantityValue,
                        isDepleted = false,
                        isPackaged = !isBulkReception,
                        unidadDeEmpaque = unidadDeEmpaque,
                        pesoPorUnidad = pesoPorUnidad,
                        cantidadInicialUnidades = cantidadInicialUnidades
                    )
                    transaction.set(newStockLotRef, newLot)

                    val productUpdateData = hashMapOf<String, Any>(
                        "stockMatriz" to newStockMatriz,
                        "totalStock" to newTotalStock,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "lastUpdatedByName" to currentUserName
                    )
                    transaction.update(productRef, productUpdateData)

                    if (isBulkReception && currentProduct.requiresPackaging) {
                        val newPackagingTaskRef = firestore.collection("pendingPackaging").document()
                        val packagingTask = PendingPackagingTask(
                            id = newPackagingTaskRef.id,
                            productId = currentProduct.id,
                            productName = currentProduct.name,
                            quantityReceived = quantityValue,
                            unit = currentProduct.unit,
                            purchaseMovementId = newMovementRef.id,
                            receivedAt = selectedDate,
                            supplierId = supplier?.id,
                            supplierName = supplier?.name
                        )
                        transaction.set(newPackagingTaskRef, packagingTask)
                    }
                }.await()

                if(isAdded) {
                    val msg = "Compra registrada: +${String.format("%.2f", quantityValue)} Kg"
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()

                    val updatedProdDoc = firestore.collection("products").document(productArgument.id).get().await()
                    updatedProdDoc.toObject(Product::class.java)?.let {
                        NotificationTriggerHelper.triggerLowStockNotification(it)
                    }
                    dismiss()
                }

            } catch (e: Exception) {
                if(isAdded) {
                    val errorMsg = (e as? FirebaseFirestoreException)?.message ?: "Error inesperado: ${e.message}"
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), errorMsg, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                if (isAdded) {
                    binding.buttonDialogAceptar.isEnabled = true
                    binding.buttonDialogCancelar.isEnabled = true
                    // Cerramos el diálogo también si hay un error para que el usuario pueda reintentar.
                    dismiss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}