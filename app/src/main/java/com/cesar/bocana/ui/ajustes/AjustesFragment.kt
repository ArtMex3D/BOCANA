package com.cesar.bocana.ui.ajustes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.cesar.bocana.R
import com.cesar.bocana.data.model.*
import com.cesar.bocana.databinding.FragmentAjustesBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.helpers.NotificationTriggerHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AjustesFragment : Fragment(), MenuProvider {

    private var _binding: FragmentAjustesBinding? = null
    private val binding get() = _binding!!

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    private var allProducts: List<Product> = emptyList()
    private var productNames: List<String> = emptyList()
    private var selectedProduct: Product? = null

    private var allLotesInLocation: List<StockLot> = emptyList()
    private var loteDisplayStrings: List<String> = emptyList()
    private var selectedStockLot: StockLot? = null

    private var originalActivityTitle: CharSequence? = null
    private val stockEpsilon = 0.01

    private var isProductSelectionFromList = false
    private var isLoteSelectionFromList = false
    private var productTextWatcher: TextWatcher? = null
    private var loteTextWatcher: TextWatcher? = null


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
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        loadProductsForSelection()
        setupListeners()
        updateFieldStates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreToolbar()
        (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.removeTextChangedListener(productTextWatcher)
        (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.removeTextChangedListener(loteTextWatcher)
        _binding = null
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
        }
        originalActivityTitle = null
    }

    private fun updateFieldStates() {
        if (_binding == null) return
        val productSelected = selectedProduct != null
        val locationSelected = binding.radioGroupAjusteLocation.checkedRadioButtonId != -1 && productSelected
        val loteSelected = selectedStockLot != null && locationSelected

        binding.radioGroupAjusteLocation.isEnabled = productSelected
        binding.radioButtonMatriz.isEnabled = productSelected
        binding.radioButtonC04.isEnabled = productSelected

        binding.textFieldLayoutAjusteLote.isEnabled = locationSelected
        (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.isEnabled = locationSelected

        binding.textFieldLayoutAjusteNewQuantity.isEnabled = loteSelected
        binding.editTextAjusteNewQuantity.isEnabled = loteSelected

        binding.textFieldLayoutAjusteReason.isEnabled = loteSelected
        binding.editTextAjusteReason.isEnabled = loteSelected

        binding.buttonPerformAjuste.isEnabled = loteSelected
    }

    private fun clearProductSelection(resettingFromTextChange: Boolean = false) {
        val previousProductName = selectedProduct?.name
        selectedProduct = null
        if (_binding != null) {
            if (!resettingFromTextChange || binding.autoCompleteTextViewAjusteProduct.text.toString() != previousProductName) {
                (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.removeTextChangedListener(productTextWatcher)
                binding.autoCompleteTextViewAjusteProduct.setText("", false)
                (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.addTextChangedListener(productTextWatcher)
            }
            binding.textViewAjusteCurrentStocksProduct.visibility = View.GONE
            binding.radioGroupAjusteLocation.clearCheck()
        }
        clearLoteSelection()
    }

    private fun clearLoteSelection(resettingFromTextChange: Boolean = false) {
        val previousLoteDisplay = selectedStockLot?.let { formatLoteForDisplay(it) }
        selectedStockLot = null
        if (_binding != null) {
            if (!resettingFromTextChange || binding.autoCompleteTextViewAjusteLote.text.toString() != previousLoteDisplay) {
                (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.removeTextChangedListener(loteTextWatcher)
                binding.autoCompleteTextViewAjusteLote.setText("", false)
                (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.addTextChangedListener(loteTextWatcher)

            }
            binding.textViewAjusteLoteInfo.visibility = View.GONE
            binding.editTextAjusteNewQuantity.setText("")
            binding.editTextAjusteReason.setText("")
            (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.setAdapter(null)
        }
        allLotesInLocation = emptyList()
        loteDisplayStrings = emptyList()
        updateFieldStates()
    }

    private fun loadProductsForSelection() {
        showLoading(true)
        firestore.collection("products")
            .whereEqualTo("isActive", true)
            .orderBy("name").get()
            .addOnSuccessListener { result ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                allProducts = result.toObjects(Product::class.java)
                productNames = allProducts.mapNotNull { it.name }
                context?.let { ctx ->
                    val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, productNames)
                    (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.setAdapter(adapter)
                }
                showLoading(false)
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                Log.e(TAG, "Error cargando productos activos", e)
                context?.let { ctx ->
                    Toast.makeText(ctx, "Error cargando productos: ${e.message}", Toast.LENGTH_LONG).show()
                }
                showLoading(false)
            }
    }

    private fun setupListeners() {
        val productAutoComplete = (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)
        productAutoComplete?.onItemClickListener = AdapterView.OnItemClickListener { adapterView, _, position, _ ->
            isProductSelectionFromList = true
            val selectedName = adapterView?.getItemAtPosition(position) as? String
            if (selectedName != null && selectedProduct?.name != selectedName) {
                selectedProduct = allProducts.find { it.name == selectedName }
                binding.textViewAjusteCurrentStocksProduct.text = selectedProduct?.let {
                    val format = Locale.getDefault()
                    val matriz = String.format(format, "%.2f ${it.unit}", it.stockMatriz)
                    val c04 = String.format(format, "%.2f ${it.unit}", it.stockCongelador04)
                    val total = String.format(format, "%.2f ${it.unit}", it.totalStock)
                    "Stock Producto: Matriz=$matriz, C04=$c04, Total=$total"
                } ?: ""
                binding.textViewAjusteCurrentStocksProduct.visibility = if (selectedProduct != null) View.VISIBLE else View.GONE
                binding.textFieldLayoutAjusteProduct.error = null
                if (_binding != null) binding.radioGroupAjusteLocation.clearCheck()
                clearLoteSelection()
            }
            view?.post { isProductSelectionFromList = false }
        }

        productTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProductSelectionFromList) return

                if (selectedProduct != null && s.toString() != selectedProduct?.name) {
                    clearProductSelection(resettingFromTextChange = true)
                } else if (s.isNullOrEmpty() && selectedProduct != null) {
                    clearProductSelection(resettingFromTextChange = true)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        productAutoComplete?.addTextChangedListener(productTextWatcher)


        binding.radioGroupAjusteLocation.setOnCheckedChangeListener { _, checkedId ->
            clearLoteSelection()
            if (checkedId != -1) {
                selectedProduct?.let { product ->
                    val location = when (checkedId) {
                        R.id.radioButtonMatriz -> Location.MATRIZ
                        R.id.radioButtonC04 -> Location.CONGELADOR_04
                        else -> null
                    }
                    location?.let { loc ->
                        loadLotesForSelection(product.id, loc)
                    }
                }
            }
            updateFieldStates()
        }

        val loteAutoComplete = (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)
        loteAutoComplete?.onItemClickListener = AdapterView.OnItemClickListener { adapterView, _, position, _ ->
            isLoteSelectionFromList = true
            selectedStockLot = allLotesInLocation.getOrNull(position)
            binding.textViewAjusteLoteInfo.text = selectedStockLot?.let {
                val df = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
                val recDate = it.receivedAt?.let { d -> df.format(d) } ?: "N/A"
                val prov = it.supplierName?.takeIf { s -> s.isNotBlank() } ?: "S/Prov"
                val loteP = it.lotNumber?.takeIf { lp -> lp.isNotBlank() } ?: "S/LoteP"
                val qty = String.format(Locale.getDefault(), "%.2f ${it.unit}", it.currentQuantity)
                "Lote: Rec:$recDate P:$prov LP:$loteP - Actual: $qty"
            } ?: ""
            binding.textViewAjusteLoteInfo.visibility = if (selectedStockLot != null) View.VISIBLE else View.GONE
            binding.textFieldLayoutAjusteLote.error = null
            updateFieldStates()
            view?.post { isLoteSelectionFromList = false }
        }

        loteTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isLoteSelectionFromList) return

                val currentLoteDisplay = selectedStockLot?.let { formatLoteForDisplay(it) } ?: ""
                if (selectedStockLot != null && s.toString() != currentLoteDisplay) {
                    clearLoteSelection(resettingFromTextChange = true)
                } else if (s.isNullOrEmpty() && selectedStockLot != null) {
                    clearLoteSelection(resettingFromTextChange = true)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        loteAutoComplete?.addTextChangedListener(loteTextWatcher)

        binding.buttonPerformAjuste.setOnClickListener { performAjusteLote() }
    }

    private fun formatLoteForDisplay(lote: StockLot): String {
        val df = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val recDate = lote.receivedAt?.let { d -> df.format(d) } ?: "N/A"
        val prov = lote.supplierName?.takeIf { it.isNotBlank() } ?: "S/P"
        val qty = String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)
        return "$recDate $prov $qty${lote.unit}"
    }

    private fun loadLotesForSelection(productId: String, location: String) {
        showLoading(true)
        binding.textFieldLayoutAjusteLote.isEnabled = false

        firestore.collection("inventoryLots")
            .whereEqualTo("productId", productId)
            .whereEqualTo("location", location)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { result ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                allLotesInLocation = result.documents.mapNotNull { doc ->
                    doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                }
                loteDisplayStrings = allLotesInLocation.map { formatLoteForDisplay(it) }
                context?.let { ctx ->
                    val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, loteDisplayStrings)
                    (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.setAdapter(adapter)
                }
                binding.textFieldLayoutAjusteLote.isEnabled = true
                showLoading(false)
                if(allLotesInLocation.isEmpty()){
                    Toast.makeText(context, "No hay lotes activos en $location para este producto.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                Log.e(TAG, "Error cargando lotes para $productId en $location", e)
                Toast.makeText(context, "Error cargando lotes: ${e.message}", Toast.LENGTH_LONG).show()
                binding.textFieldLayoutAjusteLote.isEnabled = true
                showLoading(false)
            }
    }

    private fun validateInputs(): Triple<StockLot, Double, String>? {
        var isValid = true
        if (selectedProduct == null) {
            binding.textFieldLayoutAjusteProduct.error = "Selecciona un producto"
            isValid = false
        } else {
            binding.textFieldLayoutAjusteProduct.error = null
        }

        val selectedLocationId = binding.radioGroupAjusteLocation.checkedRadioButtonId
        if (selectedLocationId == -1 && productNames.isNotEmpty() && selectedProduct != null) {
            Toast.makeText(context, "Selecciona ubicación del lote", Toast.LENGTH_SHORT).show()
            isValid = false
        }

        val lote = selectedStockLot
        if (lote == null && selectedProduct != null && selectedLocationId != -1) {
            if (allLotesInLocation.isEmpty()) {
                binding.textFieldLayoutAjusteLote.error = "No hay lotes para ajustar"
            } else {
                binding.textFieldLayoutAjusteLote.error = "Selecciona un lote específico"
            }
            isValid = false
        } else if (lote != null) {
            binding.textFieldLayoutAjusteLote.error = null
        }

        val quantityString = binding.editTextAjusteNewQuantity.text.toString().trim()
        var newQuantity: Double? = null
        if (lote != null) {
            if (quantityString.isEmpty()) {
                binding.textFieldLayoutAjusteNewQuantity.error = "Introduce la nueva cantidad"
                isValid = false
            } else {
                newQuantity = quantityString.toDoubleOrNull()
                if (newQuantity == null) {
                    binding.textFieldLayoutAjusteNewQuantity.error = "Cantidad inválida"
                    isValid = false
                } else if (newQuantity < 0.0) {
                    binding.textFieldLayoutAjusteNewQuantity.error = "No puede ser negativo"
                    isValid = false
                } else {
                    binding.textFieldLayoutAjusteNewQuantity.error = null
                }
            }
        }

        val reason = binding.editTextAjusteReason.text.toString().trim()
        if (lote != null && reason.isEmpty()) {
            binding.textFieldLayoutAjusteReason.error = "Motivo obligatorio"
            isValid = false
        } else if (lote != null) {
            binding.textFieldLayoutAjusteReason.error = null
        }

        return if (isValid && lote != null && newQuantity != null && reason.isNotEmpty()) {
            Triple(lote, newQuantity, reason)
        } else {
            null
        }
    }

    private fun performAjusteLote() {
        val validationResult = validateInputs() ?: return
        val (loteAActualizar, nuevaCantidadLote, motivo) = validationResult

        showLoading(true)
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(context, "Error de autenticación.", Toast.LENGTH_SHORT).show()
            showLoading(false)
            return
        }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Desconocido"

        val productRef = firestore.collection("products").document(loteAActualizar.productId)
        val loteRef = firestore.collection("inventoryLots").document(loteAActualizar.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        val cantidadDiferencia = nuevaCantidadLote - loteAActualizar.currentQuantity

        if (kotlin.math.abs(cantidadDiferencia) < stockEpsilon) {
            Toast.makeText(context, "No se requiere ajuste, la cantidad es la misma.", Toast.LENGTH_SHORT).show()
            showLoading(false)
            return
        }

        var productoDespuesDelAjuste: Product? = null

        firestore.runTransaction { transaction ->
            val productSnapshot = transaction.get(productRef)
            val currentProduct = productSnapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado", FirebaseFirestoreException.Code.ABORTED)

            val loteSnapshot = transaction.get(loteRef)
            val currentLote = loteSnapshot.toObject(StockLot::class.java)
                ?: throw FirebaseFirestoreException("Lote no encontrado: ${loteAActualizar.id}", FirebaseFirestoreException.Code.ABORTED)

            if (kotlin.math.abs(currentLote.currentQuantity - loteAActualizar.currentQuantity) > stockEpsilon ) {
                throw FirebaseFirestoreException("Conflicto: El lote fue modificado. Reintenta.", FirebaseFirestoreException.Code.ABORTED)
            }

            transaction.update(loteRef, mapOf(
                "currentQuantity" to nuevaCantidadLote,
                "isDepleted" to (nuevaCantidadLote <= stockEpsilon)
            ))

            var nuevoStockMatriz = currentProduct.stockMatriz
            var nuevoStockCongelador04 = currentProduct.stockCongelador04

            if (loteAActualizar.location == Location.MATRIZ) {
                nuevoStockMatriz = currentProduct.stockMatriz + cantidadDiferencia
            } else if (loteAActualizar.location == Location.CONGELADOR_04) {
                nuevoStockCongelador04 = currentProduct.stockCongelador04 + cantidadDiferencia
            }
            val nuevoTotalStock = nuevoStockMatriz + nuevoStockCongelador04

            if (nuevoStockMatriz < -stockEpsilon || nuevoStockCongelador04 < -stockEpsilon || nuevoTotalStock < -stockEpsilon) {
                throw FirebaseFirestoreException("Ajuste resultaría en stock negativo para producto.", FirebaseFirestoreException.Code.ABORTED)
            }

            transaction.update(productRef, mapOf(
                "stockMatriz" to nuevoStockMatriz,
                "stockCongelador04" to nuevoStockCongelador04,
                "totalStock" to nuevoTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            val movement = StockMovement(
                id = newMovementRef.id,
                userId = currentUser.uid,
                userName = currentUserName,
                productId = loteAActualizar.productId,
                productName = loteAActualizar.productName,
                type = MovementType.AJUSTE_MANUAL,
                quantity = kotlin.math.abs(cantidadDiferencia),
                locationFrom = if (cantidadDiferencia < 0) loteAActualizar.location else Location.AJUSTE,
                locationTo = if (cantidadDiferencia > 0) loteAActualizar.location else Location.AJUSTE,
                reason = "Ajuste lote ID: ${loteAActualizar.id.takeLast(6)}. Motivo: $motivo",
                stockAfterMatriz = nuevoStockMatriz,
                stockAfterCongelador04 = nuevoStockCongelador04,
                stockAfterTotal = nuevoTotalStock,
                timestamp = Date()
            )
            transaction.set(newMovementRef, movement)
            productoDespuesDelAjuste = currentProduct.copy(
                stockMatriz = nuevoStockMatriz,
                stockCongelador04 = nuevoStockCongelador04,
                totalStock = nuevoTotalStock,
                updatedAt = Date()
            )
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Ajuste de lote realizado con éxito.", Toast.LENGTH_SHORT).show()
            productoDespuesDelAjuste?.let { prod ->
                if (prod.minStock > 0.0 && prod.totalStock <= prod.minStock) {
                    lifecycleScope.launch { NotificationTriggerHelper.triggerLowStockNotification(prod) }
                }
            }
            clearProductSelection()
            showLoading(false)
            if (isAdded && parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            }
        }.addOnFailureListener { e ->
            showLoading(false)
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                e.message ?: "Error de datos durante el ajuste."
            } else {
                "Error al realizar el ajuste: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error en transacción de ajuste de lote:", e)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if(_binding != null) {
            binding.progressBarAjuste.visibility = if (isLoading) View.VISIBLE else View.GONE
            val enableFields = !isLoading

            val isReasonNotBlank = binding.editTextAjusteReason.text?.isNotBlank() == true
            val isQuantityNotBlank = binding.editTextAjusteNewQuantity.text?.isNotBlank() == true

            binding.buttonPerformAjuste.isEnabled = enableFields && selectedStockLot != null && isReasonNotBlank && isQuantityNotBlank

            binding.textFieldLayoutAjusteProduct.isEnabled = enableFields
            binding.autoCompleteTextViewAjusteProduct.isEnabled = enableFields

            val productIsSelected = selectedProduct != null
            binding.radioButtonMatriz.isEnabled = enableFields && productIsSelected
            binding.radioButtonC04.isEnabled = enableFields && productIsSelected
            binding.radioGroupAjusteLocation.isEnabled = enableFields && productIsSelected

            val locationIsSelected = binding.radioGroupAjusteLocation.checkedRadioButtonId != -1
            binding.textFieldLayoutAjusteLote.isEnabled = enableFields && productIsSelected && locationIsSelected
            (binding.textFieldLayoutAjusteLote.editText as? AutoCompleteTextView)?.isEnabled = enableFields && productIsSelected && locationIsSelected

            val loteIsSelected = selectedStockLot != null
            binding.textFieldLayoutAjusteNewQuantity.isEnabled = enableFields && loteIsSelected
            binding.editTextAjusteNewQuantity.isEnabled = enableFields && loteIsSelected
            binding.textFieldLayoutAjusteReason.isEnabled = enableFields && loteIsSelected
            binding.editTextAjusteReason.isEnabled = enableFields && loteIsSelected
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
    }

    override fun onPrepareMenu(menu: Menu) {
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return false
    }

    companion object {
        private const val TAG = "AjustesFragment"
    }
}
