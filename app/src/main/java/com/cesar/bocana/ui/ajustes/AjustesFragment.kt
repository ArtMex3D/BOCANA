package com.cesar.bocana.ui.ajustes

import android.os.Bundle
import android.util.Log
import android.view.*
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
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.cesar.bocana.helpers.NotificationTriggerHelper // Importar el helper


class AjustesFragment : Fragment(), MenuProvider {

    private var _binding: FragmentAjustesBinding? = null
    private val binding get() = _binding!!

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    private var allProducts: List<Product> = emptyList()
    private var productNames: List<String> = emptyList()
    private var selectedProduct: Product? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjustesBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth
        setupToolbar()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        loadProductsForSelection()
        setupProductSelectionListener()
        setupPerformAjusteButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreToolbar()
        _binding = null
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Ajustes Manuales"; subtitle = null
            setDisplayHomeAsUpEnabled(true); setDisplayShowHomeEnabled(true)
        }
    }
    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false); setDisplayShowHomeEnabled(false)
            title = getString(R.string.app_name); subtitle = null // Let MainActivity restore actual subtitle
        }
    }

    private fun loadProductsForSelection() {
        showLoading(true)
        firestore.collection("products").orderBy("name").get()
            .addOnSuccessListener { result ->
                if (_binding == null) return@addOnSuccessListener
                allProducts = result.toObjects(Product::class.java)
                productNames = allProducts.mapNotNull { it.name }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productNames)
                (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.setAdapter(adapter)
                showLoading(false)
            }
            .addOnFailureListener { e ->
                if (_binding == null) return@addOnFailureListener
                Log.e(TAG, "Error cargando productos", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                showLoading(false)
                parentFragmentManager.popBackStack()
            }
    }

    private fun setupProductSelectionListener() {
        (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.setOnItemClickListener { adapterView, _, position, _ ->
            val selectedName = adapterView.getItemAtPosition(position) as? String
            selectedProduct = allProducts.find { it.name == selectedName }
            updateCurrentStockDisplay()
            binding.textFieldLayoutAjusteProduct.error = null
        }
        (binding.textFieldLayoutAjusteProduct.editText as? AutoCompleteTextView)?.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (selectedProduct != null && s.toString() != selectedProduct?.name) {
                    selectedProduct = null; binding.textViewAjusteCurrentStocks.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun updateCurrentStockDisplay() {
        binding.textViewAjusteCurrentStocks.text = selectedProduct?.let {
            "Stock Actual: Matriz=${it.stockMatriz}, C04=${it.stockCongelador04}, Total=${it.totalStock}"
        } ?: ""
        binding.textViewAjusteCurrentStocks.visibility = if (selectedProduct != null) View.VISIBLE else View.GONE
    }

    private fun setupPerformAjusteButton() {
        binding.buttonPerformAjuste.setOnClickListener { performAjusteManual() }
    }

    private fun showLoading(isLoading: Boolean) {
        if(_binding != null) {
            binding.progressBarAjuste.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonPerformAjuste.isEnabled = !isLoading
            binding.textFieldLayoutAjusteProduct.isEnabled = !isLoading
            view?.findViewById<RadioButton>(R.id.radioButtonMatriz)?.isEnabled = !isLoading
            view?.findViewById<RadioButton>(R.id.radioButtonC04)?.isEnabled = !isLoading
            binding.textFieldLayoutAjusteNewQuantity.isEnabled = !isLoading
            binding.textFieldLayoutAjusteReason.isEnabled = !isLoading
        }
    }

    private fun validateInputs(): Triple<Product, String, Long>? {
        var isValid = true
        val product = selectedProduct
        if (product == null) { binding.textFieldLayoutAjusteProduct.error = "Selecciona"; isValid = false } else { binding.textFieldLayoutAjusteProduct.error = null }
        val selectedLocationId = binding.radioGroupAjusteLocation.checkedRadioButtonId
        val location: String? = when (selectedLocationId) { R.id.radioButtonMatriz -> Location.MATRIZ; R.id.radioButtonC04 -> Location.CONGELADOR_04; else -> null }
        if (location == null) { Toast.makeText(context, "Selecciona ubicación", Toast.LENGTH_SHORT).show(); isValid = false }
        val quantityString = binding.editTextAjusteNewQuantity.text.toString().trim()
        var newQuantity: Long? = null
        if (quantityString.isEmpty()) { binding.textFieldLayoutAjusteNewQuantity.error = "Introduce cantidad"; isValid = false }
        else {
            newQuantity = quantityString.toLongOrNull()
            if (newQuantity == null) { binding.textFieldLayoutAjusteNewQuantity.error = "Inválido"; isValid = false }
            else if (newQuantity < 0) { binding.textFieldLayoutAjusteNewQuantity.error = "No negativo"; isValid = false }
            else { binding.textFieldLayoutAjusteNewQuantity.error = null }
        }
        val reason = binding.editTextAjusteReason.text.toString().trim()
        if (reason.isEmpty()) { binding.textFieldLayoutAjusteReason.error = "Obligatorio"; isValid = false } else { binding.textFieldLayoutAjusteReason.error = null }

        return if (isValid && product != null && location != null && newQuantity != null) {
            Triple(product, location, newQuantity)
        } else {
            null
        }
    }

    private fun performAjusteManual() {
        val validationResult = validateInputs() ?: return
        val (product, location, newQuantity) = validationResult
        val productId = product.id
        if (productId.isEmpty()) { Log.e(TAG,"Error ID"); Toast.makeText(context, "Error ID", Toast.LENGTH_LONG).show(); return }
        showLoading(true)

        val currentUser = auth.currentUser; if (currentUser == null) { Toast.makeText(context,"Error user", Toast.LENGTH_SHORT).show(); showLoading(false); return }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        val productRef = firestore.collection("products").document(productId)
        val newMovementRef = firestore.collection("stockMovements").document()
        val reason = binding.editTextAjusteReason.text.toString().trim()

        var productAfterUpdate: Product? = null

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(productRef)
            val currentProduct = snapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

            var currentStockLocation: Long; var quantityDifference: Long
            var newStockMatriz: Long; var newStockCongelador04: Long

            if (location == Location.MATRIZ) {
                currentStockLocation = currentProduct.stockMatriz
                quantityDifference = newQuantity - currentStockLocation
                newStockMatriz = newQuantity
                newStockCongelador04 = currentProduct.stockCongelador04
            } else {
                currentStockLocation = currentProduct.stockCongelador04
                quantityDifference = newQuantity - currentStockLocation
                newStockMatriz = currentProduct.stockMatriz
                newStockCongelador04 = newQuantity
            }
            val newTotalStock = newStockMatriz + newStockCongelador04

            val movement = StockMovement(
                userId = currentUser.uid, userName = currentUserName,
                productId = productId, productName = currentProduct.name,
                type = MovementType.AJUSTE_MANUAL, quantity = kotlin.math.abs(quantityDifference),
                locationFrom = if (quantityDifference < 0) location else Location.AJUSTE,
                locationTo = if (quantityDifference > 0) location else Location.AJUSTE,
                reason = reason, stockAfterMatriz = newStockMatriz,
                stockAfterCongelador04 = newStockCongelador04, stockAfterTotal = newTotalStock
            )
            transaction.update(productRef, mapOf(
                "stockMatriz" to newStockMatriz, "stockCongelador04" to newStockCongelador04,
                "totalStock" to newTotalStock, "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ))
            transaction.set(newMovementRef, movement)

            productAfterUpdate = currentProduct.copy(
                stockMatriz = newStockMatriz, stockCongelador04 = newStockCongelador04, totalStock = newTotalStock
            )
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Ajuste realizado", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
            if(_binding != null) showLoading(false); parentFragmentManager.popBackStack()

        }.addOnFailureListener { e ->
            if(_binding != null) showLoading(false);
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) { e.message } else { "Error: ${e.message}" }
            Toast.makeText(context, msg ?: "Error al ajustar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        // No action needed here, menu is inflated by MainActivity
    }

    override fun onPrepareMenu(menu: Menu) {
        // Hide items not relevant for this screen
        Log.d(TAG, "onPrepareMenu (AjustesFragment): Ocultando items...")
        menu.findItem(R.id.action_ajustes)?.isVisible = false
        menu.findItem(R.id.action_devoluciones)?.isVisible = false
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // Let MainActivity handle Up navigation (android.R.id.home) and other items (Logout)
        return false
    }

    // --- Companion Object (for TAG) ---
    companion object {
        private const val TAG = "AjustesFragment" // TAG defined here
    }

} // ---> Fin de la clase AjustesFragment <---