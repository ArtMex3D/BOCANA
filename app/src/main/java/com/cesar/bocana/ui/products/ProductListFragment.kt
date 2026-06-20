package com.cesar.bocana.ui.products

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import com.cesar.bocana.ui.adapters.GroupableListItem
import com.cesar.bocana.ui.adapters.SubloteC04SelectionAdapter
import com.cesar.bocana.ui.adapters.SubloteC04SelectionListener
import java.util.TreeMap
import com.cesar.bocana.ui.adapters.SingleLotSelectionListener
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import android.content.DialogInterface
import com.cesar.bocana.data.model.DevolucionPendiente
import com.google.firebase.firestore.WriteBatch
import java.util.Calendar
import android.widget.DatePicker
import android.app.DatePickerDialog
import androidx.lifecycle.lifecycleScope
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import com.cesar.bocana.data.model.PendingPackagingTask
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.PopupMenu
import android.widget.ListView
import java.text.SimpleDateFormat
import android.widget.CheckBox
import com.google.firebase.firestore.DocumentSnapshot
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.firebase.firestore.ktx.toObject
import android.view.*
import kotlinx.coroutines.launch
import androidx.fragment.app.viewModels
import com.cesar.bocana.data.model.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import com.cesar.bocana.data.model.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.cesar.bocana.ui.adapters.LotSelectionAdapter // Importar el nuevo Adapter
import androidx.recyclerview.widget.RecyclerView // Para el RecyclerView en el diálogo
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Date
import android.text.InputType
import java.util.Locale
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.databinding.FragmentProductListBinding
import com.cesar.bocana.ui.adapters.ProductActionListener
import com.cesar.bocana.ui.adapters.ProductAdapter
import com.google.firebase.auth.FirebaseAuth
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.ktx.Firebase
import com.google.android.material.tabs.TabLayout
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import com.cesar.bocana.helpers.NotificationTriggerHelper
import com.cesar.bocana.data.model.UserRole
import com.cesar.bocana.data.model.Supplier
import com.google.android.material.textfield.TextInputLayout
import androidx.core.view.isVisible
import com.cesar.bocana.data.repository.InventoryRepository
import com.cesar.bocana.ui.ViewModelFactory
import com.cesar.bocana.ui.dialogs.AjusteSubloteC04DialogFragment
import com.cesar.bocana.data.local.AppDatabase
import androidx.fragment.app.viewModels
import kotlinx.coroutines.flow.collectLatest
import com.cesar.bocana.ui.dialogs.SalidaConsumoLotesDialogFragment
import com.cesar.bocana.ui.dialogs.SalidaDevolucionLotesDialogFragment
import com.cesar.bocana.ui.dialogs.TraspasoMatrizC04DialogFragment
import com.cesar.bocana.ui.dialogs.AddCompraDialogFragment
import com.cesar.bocana.ui.ajustecompleto.AjusteCompletoFragment
import com.cesar.bocana.ui.dialogs.TraspasoC04MatrizDialogFragment


class ProductListFragment : Fragment(), ProductActionListener, MenuProvider, AjusteSubloteC04DialogFragment.AjusteSubloteC04Listener {

    private val stockEpsilon = 0.1
    private var _binding: FragmentProductListBinding? = null
    private val binding get() = _binding!!
    private var currentLocationContext: String = Location.MATRIZ

    private lateinit var productAdapter: ProductAdapter
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var productsListener: ListenerRegistration? = null
    private var currentUserRole: UserRole? = null
    private var isDialogOpen = false
    private val viewModel: ProductListViewModel by viewModels {
        ViewModelFactory(
            InventoryRepository(
                AppDatabase.getDatabase(requireContext()),
                Firebase.firestore
            )
        )
    }

    override fun onSubloteAjustado(productId: String) {
        Log.d(TAG, "Ajuste de sublote C04 completado para producto ID: $productId.")
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        Log.d(TAG, "onCreateMenu (ProductListFragment)")
    }

    override fun onPrepareMenu(menu: Menu) {
        Log.d(TAG, "onPrepareMenu (ProductListFragment)")
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductListBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth

        fetchCurrentUserRole {
            setupRecyclerView()
            setupFab()
            setupTabLayoutListener()
            observeViewModel()
        }
        return binding.root
    }

    fun onNetworkStatusChanged(isOnline: Boolean) {
        if (::productAdapter.isInitialized) {
            productAdapter.setOnlineStatus(isOnline)
        }
    }

    private fun observeViewModel() {
        showListLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.products.collectLatest { products ->
                showListLoading(false)
                productAdapter.submitList(products)
                if (_binding != null) {
                    binding.textViewEmptyList.isVisible = products.isEmpty()
                    binding.recyclerViewProducts.isVisible = products.isNotEmpty()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        setupAjusteCompletoBannerClick()

        // 🔥 PASO 3 y 4: FORZAR ACTUALIZACIÓN SEGÚN LA PESTAÑA ACTUAL
        binding.tabLayoutLocation.post {
            val currentTabPosition = binding.tabLayoutLocation.selectedTabPosition
            Log.d(TAG, "onViewCreated: pestaña inicial = $currentTabPosition")

            val currentLocation = if (currentTabPosition == 0) Location.MATRIZ else Location.CONGELADOR_04
            currentLocationContext = currentLocation

            // 🛡️ CHALECO ANTIBALAS: Solo le avisamos al adaptador si ya existe
            if (::productAdapter.isInitialized) {
                productAdapter.setCurrentLocationContext(currentLocation)
            }

            if (currentLocation == Location.CONGELADOR_04) {
                showAjusteCompletoBanner(true)
                Log.d(TAG, "✅ Forzada actualización a CONGELADOR_04 en onViewCreated")
            } else {
                showAjusteCompletoBanner(false)
                Log.d(TAG, "✅ Forzada actualización a MATRIZ en onViewCreated")
            }
        }
    }
    override fun onTraspasoC04Clicked(product: Product, anchorView: View) {
        if (isDialogOpen) return
        TraspasoMatrizC04DialogFragment.newInstance(product)
            .show(parentFragmentManager, TraspasoMatrizC04DialogFragment.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        productsListener?.remove(); productsListener = null
        _binding = null
    }

    override fun onEditC04Clicked(product: Product) {
        if (isDialogOpen) return
        val dialogFragment = AjusteSubloteC04DialogFragment.newInstance(product.id)
        dialogFragment.show(parentFragmentManager, AjusteSubloteC04DialogFragment.TAG)
    }

    // 🚀 MAGIA ACORDEÓN: Función para animar el banner
    private fun showAjusteCompletoBanner(show: Boolean) {
        binding.cardAjusteCompletoBanner.apply {
            if (show && visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(300).start()
            } else if (!show && visibility != View.GONE) {
                animate().alpha(0f).setDuration(200).withEndAction {
                    visibility = View.GONE
                }.start()
            }
        }
    }

    // 🚀 MAGIA ACORDEÓN: Función del clic
    private fun setupAjusteCompletoBannerClick() {
        binding.cardAjusteCompletoBanner.setOnClickListener {
            if (isDialogOpen) return@setOnClickListener
            isDialogOpen = true

            val ajusteFragment = AjusteCompletoFragment.newInstance()
            parentFragmentManager.beginTransaction()
                .replace(com.cesar.bocana.R.id.nav_host_fragment_content_main, ajusteFragment)
                .addToBackStack("AjusteCompletoFragment")
                .commit()
            isDialogOpen = false
        }
    }

    private fun setupTabLayoutListener() {
        binding.tabLayoutLocation.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newLocationContext = when (tab?.position) {
                    0 -> Location.MATRIZ
                    1 -> Location.CONGELADOR_04
                    else -> Location.MATRIZ
                }

                if (newLocationContext != currentLocationContext) {
                    currentLocationContext = newLocationContext

                    // 🛡️ CHALECO ANTIBALAS: Solo avisar si el adaptador ya nació
                    if (::productAdapter.isInitialized) {
                        productAdapter.setCurrentLocationContext(currentLocationContext)
                    }

                    // ✨ MAGIA ACORDEÓN: Mostrar/Ocultar dependiendo de la pestaña
                    when (currentLocationContext) {
                        Location.CONGELADOR_04 -> showAjusteCompletoBanner(true)
                        Location.MATRIZ -> showAjusteCompletoBanner(false)
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showDebouncedDialog(builder: AlertDialog.Builder) {
        if (!isAdded || context == null) {
            isDialogOpen = false
            return
        }
        try {
            val dialog = builder.create()
            dialog.setOnDismissListener {
                isDialogOpen = false
            }
            isDialogOpen = true
            dialog.show()
        } catch (e: Exception) {
            isDialogOpen = false
        }
    }




    private fun showEditC04Dialog(product: Product) {
        if (context == null) {
            isDialogOpen = false; return
        }
        val builder = AlertDialog.Builder(requireContext())
        val currentStockFormatted = String.format(Locale.getDefault(), "%.2f", product.stockCongelador04)
        builder.setTitle("Ajustar Stock 04: ${product.name}")
        builder.setMessage("Stock actual en 04: $currentStockFormatted ${product.unit}\nNOTA: Esto registra una SALIDA por la diferencia.")
        val container = FrameLayout(requireContext());
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = resources.getDimensionPixelSize(R.dimen.dialog_margin); params.leftMargin = margin; params.rightMargin = margin
        val inputNewQuantity = EditText(requireContext()); inputNewQuantity.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        inputNewQuantity.hint = "Nueva cantidad en C04"; inputNewQuantity.layoutParams = params; container.addView(inputNewQuantity)
        builder.setView(container)
        builder.setPositiveButton("Ajustar Stock") { _, _ ->
            val quantityString = inputNewQuantity.text.toString()
            try {
                val newQuantity = quantityString.toDoubleOrNull()
                if (newQuantity == null || newQuantity < 0.0) {
                    view?.let { Snackbar.make(it, "Cantidad inválida (debe ser >= 0.0)", Snackbar.LENGTH_SHORT).show() }; isDialogOpen = false; return@setPositiveButton
                }
                if (newQuantity > product.stockCongelador04) {
                    view?.let { Snackbar.make(it, "Error: Nueva cantidad > actual (${String.format("%.2f", product.stockCongelador04)})", Snackbar.LENGTH_LONG).show() }; isDialogOpen = false; return@setPositiveButton
                }
                val quantityDifference = product.stockCongelador04 - newQuantity
                if (quantityDifference <= 0.1) {
                    view?.let { Snackbar.make(it, "No se requiere ajuste.", Snackbar.LENGTH_SHORT).show() }; isDialogOpen = false; return@setPositiveButton
                }
                val limit = (product.stockCongelador04 * 0.40)
                if (quantityDifference > limit && product.stockCongelador04 > 0.0) {
                    isDialogOpen = false
                    AlertDialog.Builder(requireContext()).setTitle("Confirmar Ajuste Grande").setMessage("Salida de ${String.format("%.2f", quantityDifference)} ${product.unit} (a ${String.format("%.2f", newQuantity)}). ¿Continuar?")
                        .setPositiveButton("Sí") { _, _ -> performEditC04(product, newQuantity, quantityDifference) }
                        .setNegativeButton("No", null)
                        .setOnDismissListener { if (!isDialogOpen) isDialogOpen = false }
                        .show()
                } else {
                    performEditC04(product, newQuantity, quantityDifference)
                }
            } catch (e: NumberFormatException) {
                view?.let { Snackbar.make(it, "Número inválido.", Snackbar.LENGTH_SHORT).show() }; isDialogOpen = false;
            }
        }
        builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        showDebouncedDialogWithCustomView(dialog)
    }

    private fun performEditC04(product: Product, newQuantityC04: Double, quantityDifference: Double) {
        if (product.id.isEmpty()) {
            view?.let { Snackbar.make(it, "Error: ID de producto inválido.", Snackbar.LENGTH_LONG).show() }; return
        }
        val currentUser = auth.currentUser; if (currentUser == null) {
            view?.let { Snackbar.make(it, "Error: Usuario no autenticado.", Snackbar.LENGTH_SHORT).show() }; return
        }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        val productRef = firestore.collection("products").document(product.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        var productAfterUpdate: Product? = null

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(productRef)
            val currentProduct = snapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado.", FirebaseFirestoreException.Code.ABORTED)

            if (newQuantityC04 < 0.0 || newQuantityC04 > currentProduct.stockCongelador04) {
                throw FirebaseFirestoreException("Ajuste inválido. Stock C04: ${String.format(Locale.getDefault(), "%.2f", currentProduct.stockCongelador04)}, ajuste a: ${String.format(Locale.getDefault(), "%.2f", newQuantityC04)}", FirebaseFirestoreException.Code.ABORTED)
            }
            val actualDifference = currentProduct.stockCongelador04 - newQuantityC04
            if (actualDifference <= 0.0) {
                throw FirebaseFirestoreException("No se requiere ajuste.", FirebaseFirestoreException.Code.CANCELLED)
            }

            val newTotalStock = currentProduct.stockMatriz + newQuantityC04

            val movement = StockMovement(
                userId = currentUser.uid, userName = currentUserName,
                productId = product.id, productName = currentProduct.name,
                type = MovementType.AJUSTE_STOCK_C04,
                quantity = actualDifference,
                locationFrom = Location.CONGELADOR_04,
                locationTo = Location.EXTERNO,
                reason = "Ajuste manual stock C04",
                stockAfterMatriz = currentProduct.stockMatriz,
                stockAfterCongelador04 = newQuantityC04,
                stockAfterTotal = newTotalStock
            )

            transaction.update(productRef, mapOf(
                "stockCongelador04" to newQuantityC04,
                "totalStock" to newTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))
            transaction.set(newMovementRef, movement)

            productAfterUpdate = currentProduct.copy(stockCongelador04 = newQuantityC04, totalStock = newTotalStock)
            null
        }.addOnSuccessListener {
            val msg = "Stock C04 ajustado a ${String.format("%.2f", newQuantityC04)} (-${String.format("%.2f", quantityDifference)} ${product.unit})"
            view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() }
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && (e.code == FirebaseFirestoreException.Code.ABORTED || e.code == FirebaseFirestoreException.Code.CANCELLED)) {
                e.message
            } else {
                "Error al ajustar stock C04: ${e.message}"
            }
            view?.let { Snackbar.make(it, msg ?: "Error desconocido", Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun fetchCurrentUserRole(callback: (Boolean) -> Unit) {
        val userId = auth.currentUser?.uid
        if (userId == null) { callback(false); return }
        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) { callback(false); return@addOnSuccessListener }
                if (doc != null && doc.exists()) {
                    val user = doc.toObject(User::class.java)
                    currentUserRole = if(user?.role == UserRole.ADMIN && user.isAccountActive) UserRole.ADMIN else null
                    if (::productAdapter.isInitialized) {
                        productAdapter.setCurrentUserRole(currentUserRole)
                    }
                    updateFabVisibility()
                    callback(currentUserRole == UserRole.ADMIN)
                } else {
                    currentUserRole = null
                    updateFabVisibility()
                    callback(false)
                }
            }
            .addOnFailureListener { e ->
                if (_binding == null) { callback(false); return@addOnFailureListener }
                currentUserRole = null
                updateFabVisibility()
                callback(false)
            }
    }

    private fun setupRecyclerView() {
        productAdapter = ProductAdapter(this, currentUserRole)
        binding.recyclerViewProducts.apply {
            adapter = productAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupFab() {
        binding.fabAddProduct.setOnClickListener {
            if (isDialogOpen) return@setOnClickListener
            isDialogOpen = true

            val addFragment = AddEditProductFragment.newInstance(null)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, addFragment)
                .addToBackStack("AddProductFragment").commit()
            isDialogOpen = false
        }
        updateFabVisibility()
    }

    private fun updateFabVisibility() {
        val canAdd = currentUserRole == UserRole.ADMIN
        if (_binding != null) {
            binding.fabAddProduct.visibility = if (canAdd) View.VISIBLE else View.GONE
        }
    }

    private fun showListLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarList.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onAddCompraClicked(product: Product) {
        if (isDialogOpen) return
        AddCompraDialogFragment.newInstance(product)
            .show(parentFragmentManager, AddCompraDialogFragment.TAG)
    }

    private fun loadSuppliersForDialog(callback: (List<Supplier>) -> Unit) {
        firestore.collection("suppliers")
            .orderBy("name")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot != null) {
                    try {
                        val suppliers = snapshot.documents.mapNotNull { doc ->
                            try {
                                doc.toObject(Supplier::class.java)?.copy(id = doc.id)
                            } catch (e: Exception) { null }
                        }
                        callback(suppliers)
                    } catch (e: Exception) {
                        callback(emptyList())
                    }
                } else {
                    callback(emptyList())
                }
            }
            .addOnFailureListener { e ->
                activity?.runOnUiThread {
                    view?.let { Snackbar.make(it, "Error cargando proveedores.", Snackbar.LENGTH_SHORT).show() }
                }
                callback(emptyList())
            }
    }

    override fun onSalidaClicked(product: Product, anchorView: View) {
        if (isDialogOpen) return
        isDialogOpen = true

        val popupContext = context ?: run {
            isDialogOpen = false
            return
        }
        val popup = androidx.appcompat.widget.PopupMenu(popupContext, anchorView)
        try {
            popup.menuInflater.inflate(R.menu.popup_salida_menu, popup.menu)
        } catch (e: Exception) {
            view?.let { Snackbar.make(it, "Error al mostrar opciones", Snackbar.LENGTH_SHORT).show() }
            isDialogOpen = false
            return
        }

        popup.setOnDismissListener {
            isDialogOpen = false
        }

        popup.setOnMenuItemClickListener { menuItem: MenuItem ->
            isDialogOpen = false
            when (menuItem.itemId) {
                R.id.action_salida_consumo -> {
                    SalidaConsumoLotesDialogFragment.newInstance(product)
                        .show(parentFragmentManager, SalidaConsumoLotesDialogFragment.TAG)
                    true
                }
                R.id.action_salida_devolucion -> {
                    SalidaDevolucionLotesDialogFragment.newInstance(product)
                        .show(parentFragmentManager, SalidaDevolucionLotesDialogFragment.TAG)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onTraspasoC04MClicked(product: Product) {
        if (isDialogOpen) return
        isDialogOpen = true
        TraspasoC04MatrizDialogFragment.newInstance(product)
            .show(parentFragmentManager, TraspasoC04MatrizDialogFragment.TAG)
    }

    override fun onItemClicked(product: Product) {
        val editFragment = AddEditProductFragment.newInstance(product.id)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, editFragment)
            .addToBackStack("EditProductFragment").commit()
    }

    private fun canUserModify(): Boolean {
        val allowed = currentUserRole == UserRole.ADMIN
        if (!allowed) {
            view?.let { Snackbar.make(it, "Permiso denegado.", Snackbar.LENGTH_SHORT).show() }
        }
        return allowed
    }


    private fun showDebouncedDialogWithCustomView(dialog: AlertDialog) {
        if (!isAdded || context == null) {
            isDialogOpen = false
            return
        }
        try {
            dialog.setOnDismissListener {
                isDialogOpen = false
            }
            isDialogOpen = true
            dialog.show()
        } catch (e: Exception) {
            isDialogOpen = false
        }
    }
    fun onDialogClosed() {
        isDialogOpen = false
    }

    override fun onResume() {
        super.onResume()
        // Forzar reset del flag cuando el fragmento vuelve a ser visible
        if (isDialogOpen) {
            Log.w(TAG, "⚠️ FORZANDO RESET en onResume - isDialogOpen estaba: $isDialogOpen")
            isDialogOpen = false
        }
    }
    companion object {
        private const val TAG = "ProductListFragment"
    }
}