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
import com.cesar.bocana.helpers.NotificationTriggerHelper
import kotlinx.coroutines.launch
import java.util.Locale

class AjustesFragment : Fragment(), MenuProvider {

    private var _binding: FragmentAjustesBinding? = null
    private val binding get() = _binding!!

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    private var allProducts: List<Product> = emptyList()
    private var productNames: List<String> = emptyList()
    private var selectedProduct: Product? = null
    private var originalActivityTitle: CharSequence? = null // Guardar titulo original

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjustesBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth
        // No configurar toolbar aquí, sino en onViewCreated
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar() // Configurar toolbar aquí
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        loadProductsForSelection()
        setupProductSelectionListener()
        setupPerformAjusteButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreToolbar() // Restaurar toolbar aquí
        _binding = null
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title // Guardar el título que puso MainActivity
            // MainActivity ya debería haber puesto "Ajustes Manuales" basado en BottomNav
            // title = "Ajustes Manuales" // Opcional: re-asegurar título
            subtitle = null
            setDisplayHomeAsUpEnabled(true) // Mostrar flecha atrás
            setDisplayShowHomeEnabled(true)
        }
    }
    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            // Al salir, MainActivity (o el fragment al que volvamos) pondrá su título/subtítulo
            setDisplayHomeAsUpEnabled(false) // Ocultar flecha atrás
            setDisplayShowHomeEnabled(false)
            // No restauramos título/subtítulo aquí, dejamos que el fragment destino lo haga
        }
        originalActivityTitle = null
    }


    private fun loadProductsForSelection() {
        showLoading(true)
        firestore.collection("products")
            .whereEqualTo("isActive", true) // Filtro añadido en Fase 1
            .orderBy("name").get()
            .addOnSuccessListener { result ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                allProducts = result.toObjects(Product::class.java)
                productNames = allProducts.mapNotNull { it.name }
                context?.let { ctx -> // Usar context seguro
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
            val format = Locale.getDefault()
            val matriz = String.format(format, "%.2f ${it.unit}", it.stockMatriz)
            val c04 = String.format(format, "%.2f ${it.unit}", it.stockCongelador04)
            val total = String.format(format, "%.2f ${it.unit}", it.totalStock)
            "Stock Actual: Matriz=$matriz, C04=$c04, Total=$total"
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
            // Usar view?.findViewById es más seguro
            view?.findViewById<RadioButton>(R.id.radioButtonMatriz)?.isEnabled = !isLoading
            view?.findViewById<RadioButton>(R.id.radioButtonC04)?.isEnabled = !isLoading
            binding.textFieldLayoutAjusteNewQuantity.isEnabled = !isLoading
            binding.textFieldLayoutAjusteReason.isEnabled = !isLoading
        }
    }

    private fun validateInputs(): Triple<Product, String, Double>? {
        var isValid = true
        val product = selectedProduct
        if (product == null) { binding.textFieldLayoutAjusteProduct.error = "Selecciona"; isValid = false } else { binding.textFieldLayoutAjusteProduct.error = null }

        val selectedLocationId = binding.radioGroupAjusteLocation.checkedRadioButtonId
        val location: String? = when (selectedLocationId) { R.id.radioButtonMatriz -> Location.MATRIZ; R.id.radioButtonC04 -> Location.CONGELADOR_04; else -> null }
        if (location == null) { Toast.makeText(context, "Selecciona ubicación", Toast.LENGTH_SHORT).show(); isValid = false }

        val quantityString = binding.editTextAjusteNewQuantity.text.toString().trim()
        var newQuantity: Double? = null
        if (quantityString.isEmpty()) { binding.textFieldLayoutAjusteNewQuantity.error = "Introduce cantidad"; isValid = false }
        else {
            newQuantity = quantityString.toDoubleOrNull()
            if (newQuantity == null) { binding.textFieldLayoutAjusteNewQuantity.error = "Inválido"; isValid = false }
            else if (newQuantity < 0.0) { binding.textFieldLayoutAjusteNewQuantity.error = "No negativo"; isValid = false }
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

            var currentStockLocation: Double
            var quantityDifference: Double
            var newStockMatriz: Double
            var newStockCongelador04: Double

            if (location == Location.MATRIZ) {
                currentStockLocation = currentProduct.stockMatriz
                quantityDifference = newQuantity - currentStockLocation
                newStockMatriz = newQuantity
                newStockCongelador04 = currentProduct.stockCongelador04
            } else { // Location.CONGELADOR_04
                currentStockLocation = currentProduct.stockCongelador04
                quantityDifference = newQuantity - currentStockLocation
                newStockMatriz = currentProduct.stockMatriz
                newStockCongelador04 = newQuantity
            }
            val newTotalStock = newStockMatriz + newStockCongelador04

            val movement = StockMovement(
                userId = currentUser.uid, userName = currentUserName,
                productId = productId, productName = currentProduct.name,
                type = MovementType.AJUSTE_MANUAL,
                quantity = kotlin.math.abs(quantityDifference),
                locationFrom = if (quantityDifference < 0) location else Location.AJUSTE,
                locationTo = if (quantityDifference > 0) location else Location.AJUSTE,
                reason = reason,
                stockAfterMatriz = newStockMatriz,
                stockAfterCongelador04 = newStockCongelador04,
                stockAfterTotal = newTotalStock
            )

            transaction.update(productRef, mapOf(
                "stockMatriz" to newStockMatriz,
                "stockCongelador04" to newStockCongelador04,
                "totalStock" to newTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
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
            // Regresar a la pantalla anterior
            if(isAdded) { parentFragmentManager.popBackStack() }
            if(_binding != null) showLoading(false) // Ocultar loading después de volver

        }.addOnFailureListener { e ->
            if(_binding != null) showLoading(false)
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) { e.message } else { "Error al ajustar: ${e.message}" }
            Toast.makeText(context, msg ?: "Error al ajustar", Toast.LENGTH_LONG).show()
        }
    }

    // --- Implementación MenuProvider ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        // No añadir nada al menú superior desde aquí
    }

    override fun onPrepareMenu(menu: Menu) {
        // Ocultar el único item del menú superior (Logout) si se desea
        // o dejarlo visible siempre. Por ahora, lo dejamos visible.
        Log.d(TAG, "onPrepareMenu (AjustesFragment)")
        // Las líneas que buscaban action_ajustes, action_devoluciones, etc. SE HAN ELIMINADO
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // MainActivity maneja R.id.home (flecha atrás) y R.id.action_logout
        return false
    }
    // --- Fin MenuProvider ---

    companion object {
        private const val TAG = "AjustesFragment"
    }
}