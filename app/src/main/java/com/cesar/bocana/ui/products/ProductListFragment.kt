package com.cesar.bocana.ui.products



import kotlinx.coroutines.async // Para ejecutar operaciones en paralelo
import kotlinx.coroutines.awaitAll // Para esperar que todas las operaciones async terminen
import kotlinx.coroutines.tasks.await
import com.cesar.bocana.ui.adapters.GroupableListItem
import com.cesar.bocana.ui.adapters.SubloteC04SelectionAdapter
import com.cesar.bocana.ui.adapters.SubloteC04SelectionListener
import java.util.TreeMap
import com.cesar.bocana.ui.adapters.SingleLotSelectionListener
import com.google.firebase.firestore.QuerySnapshot
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.*
import com.cesar.bocana.data.model.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.cesar.bocana.ui.adapters.LotSelectionAdapter // Importar el nuevo Adapter
import androidx.recyclerview.widget.RecyclerView // Para el RecyclerView en el diálogo
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.PopupMenu
import android.widget.ListView // ¡Nuevo!
import android.widget.ProgressBar // ¡Nuevo!
import android.widget.TextView // ¡Nuevo!
import java.text.SimpleDateFormat
import android.widget.CheckBox // Si usamos CheckBox en diálogo custom (opcional)
import com.google.firebase.firestore.DocumentSnapshot
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.firebase.firestore.ktx.toObject
import java.util.Date
import android.text.InputType
import java.util.Locale
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import com.cesar.bocana.data.model.PendingPackagingTask // El nuevo modelo
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.*
import com.cesar.bocana.databinding.FragmentProductListBinding
import com.cesar.bocana.ui.adapters.ProductActionListener
import com.cesar.bocana.ui.adapters.ProductAdapter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException // Import necesario
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.android.material.tabs.TabLayout
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import com.cesar.bocana.helpers.NotificationTriggerHelper // Importar el helper
import com.cesar.bocana.data.model.UserRole
import android.content.DialogInterface
import com.cesar.bocana.data.model.DevolucionPendiente // Asegúrate de importar
import com.cesar.bocana.data.model.Supplier
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.WriteBatch
import java.util.Calendar
import android.widget.DatePicker
import android.app.DatePickerDialog
import androidx.core.view.isVisible
import com.cesar.bocana.data.repository.InventoryRepository
import com.cesar.bocana.ui.ViewModelFactory
import com.cesar.bocana.ui.dialogs.AjusteSubloteC04DialogFragment
import com.cesar.bocana.data.local.AppDatabase
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.view.*
import androidx.fragment.app.viewModels
import com.cesar.bocana.data.model.*
import com.cesar.bocana.ui.dialogs.SalidaConsumoLotesDialogFragment
import com.cesar.bocana.ui.dialogs.TraspasoMatrizC04DialogFragment
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


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
            Log.d(TAG, "Ajuste de sublote C04 completado para producto ID: $productId y notificado por el diálogo.")
        }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        Log.d(TAG, "onCreateMenu (ProductListFragment)")
    }

    override fun onPrepareMenu(menu: Menu) {
        Log.d(TAG, "onPrepareMenu (ProductListFragment) - Menú superior simplificado.")
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        Log.d(TAG, "onMenuItemSelected (ProductListFragment) para ${menuItem.title} - No manejado aquí.")
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
            observeViewModel() // Llamamos a la nueva función para observar datos locales
        }
        return binding.root
    }

        private fun observeViewModel() {
            // Esta función reemplaza a la antigua `observeProducts`
            showListLoading(true)
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.products.collectLatest { products ->
                    showListLoading(false)
                    productAdapter.submitList(products)
                    if (_binding != null) { // Chequeo de seguridad
                        binding.textViewEmptyList.isVisible = products.isEmpty()
                        binding.recyclerViewProducts.isVisible = products.isNotEmpty()
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        setupTabLayoutListener()
    }
        override fun onTraspasoC04Clicked(product: Product, anchorView: View) {
            if (isDialogOpen) {
                Log.d(TAG, "Dialog or menu already open, ignoring Traspaso M->C04 click.")
                return
            }
            TraspasoMatrizC04DialogFragment.newInstance(product)
                .show(parentFragmentManager, TraspasoMatrizC04DialogFragment.TAG)
        }

    private fun showTraspasoMatrizToC04Dialog(product: Product) {
        val currentContext = context ?: run {
            isDialogOpen = false
            return
        }

        val dialogViewInflated = LayoutInflater.from(currentContext).inflate(R.layout.dialog_traspaso_matriz_c04, null)
        val titleTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoTitleInfo)
        val subtitleTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoSubtitle)

        val recyclerViewLotes = dialogViewInflated.findViewById<RecyclerView>(R.id.recyclerViewLotesTraspasoDialog)
        val progressBarLotes = dialogViewInflated.findViewById<ProgressBar>(R.id.progressBarLotesTraspasoDialog)
        val textViewNoLotes = dialogViewInflated.findViewById<TextView>(R.id.textViewNoLotesTraspasoDialog)
        val inputQuantityNet = dialogViewInflated.findViewById<EditText>(R.id.editTextCantidadTraspaso)
        val buttonAceptar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogTraspasoAceptar)
        val buttonCancelar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogTraspasoCancelar)

        titleTextView.text = "Traspaso: ${product.name}"
        subtitleTextView.text = "Matriz  ----->  C-04"


        val lotAdapter = LotSelectionAdapter() // Reutiliza tu LotSelectionAdapter
        recyclerViewLotes.layoutManager = LinearLayoutManager(currentContext)
        recyclerViewLotes.adapter = lotAdapter

        val builder = AlertDialog.Builder(currentContext)
        // No usamos setCustomTitle para este, el título está en el layout.
        builder.setView(dialogViewInflated)

        val alertDialog = builder.create()
        alertDialog.setOnDismissListener { isDialogOpen = false }

        buttonCancelar.setOnClickListener {
            alertDialog.dismiss()
        }

        buttonAceptar.setOnClickListener {
            val quantityString = inputQuantityNet.text.toString()
            val quantityToTraspasar = quantityString.toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            inputQuantityNet.error = null

            if (quantityToTraspasar == null || quantityToTraspasar <= 0.0) {
                inputQuantityNet.error = "Cantidad > 0.0"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            // Usar un pequeño épsilon para la comparación de doubles
            if (quantityToTraspasar != null && lotAdapter.currentList.isNotEmpty() && quantityToTraspasar > selectedLotsTotalNetQuantity + 0.1) {
                inputQuantityNet.error = "Excede stock lotes selecc. (${String.format("%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToTraspasar != null) {
                if (lotAdapter.currentList.isEmpty() && quantityToTraspasar > 0.0) {
                    Toast.makeText(context, "No hay lotes disponibles en Matriz para traspasar.", Toast.LENGTH_SHORT).show()
                } else if (selectedLotIds.isNotEmpty()){
                    performTraspasoMatrizToC04(product, quantityToTraspasar, selectedLotIds)
                    alertDialog.dismiss()
                } else if (lotAdapter.currentList.isEmpty() && quantityToTraspasar <= 0.0){
                    alertDialog.dismiss() // No hay nada que hacer
                } else {
                    Toast.makeText(context, "Verifica cantidad y selección de lotes.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Cargar lotes de Matriz
        progressBarLotes.visibility = View.VISIBLE
        textViewNoLotes.visibility = View.GONE
        recyclerViewLotes.visibility = View.GONE

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", product.id)
            .whereEqualTo("location", Location.MATRIZ)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        lotsQuery.get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || _binding == null) {
                    isDialogOpen = false // Asegurar reset si el fragmento ya no está
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    return@addOnSuccessListener
                }
                progressBarLotes.visibility = View.GONE
                if (snapshot != null && !snapshot.isEmpty) {
                    val loadedLots = snapshot.documents.mapNotNull { doc ->
                        try { doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
                        catch (e: Exception) { null }
                    }
                    lotAdapter.submitList(loadedLots)
                    textViewNoLotes.visibility = View.GONE
                    recyclerViewLotes.visibility = View.VISIBLE
                } else {
                    textViewNoLotes.text = "No hay lotes disponibles en Matriz."
                    textViewNoLotes.visibility = View.VISIBLE
                    recyclerViewLotes.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || _binding == null) {
                    isDialogOpen = false
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    return@addOnFailureListener
                }
                progressBarLotes.visibility = View.GONE
                textViewNoLotes.text = "Error al cargar lotes."
                textViewNoLotes.visibility = View.VISIBLE
                recyclerViewLotes.visibility = View.GONE
                Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
            }
        alertDialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: Deteniendo listener.")
        productsListener?.remove(); productsListener = null
        _binding = null
    }


        override fun onEditC04Clicked(product: Product) {
            if (isDialogOpen) {
                Log.d(TAG, "onEditC04Clicked: Diálogo o menú ya abierto, ignorando clic en Editar C04.")
                return
            }

            Log.d(TAG, "onEditC04Clicked: Mostrando AjusteSubloteC04DialogFragment para ${product.name}")
            val dialogFragment = AjusteSubloteC04DialogFragment.newInstance(product.id)
            dialogFragment.show(parentFragmentManager, AjusteSubloteC04DialogFragment.TAG)
        }




        private fun setupTabLayoutListener() {
        // Asegúrate que el ID 'tabLayoutLocation' exista en fragment_product_list.xml
        binding.tabLayoutLocation.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newLocationContext = when (tab?.position) {
                    0 -> Location.MATRIZ
                    1 -> Location.CONGELADOR_04
                    else -> Location.MATRIZ
                }
                Log.d(TAG, "Pestaña seleccionada: ${tab?.text}, Contexto ahora: $newLocationContext")

                if (newLocationContext != currentLocationContext) {
                    currentLocationContext = newLocationContext
                    // ---> Notificar al Adaptador (Requerirá modificar ProductAdapter después) <---
                    productAdapter.setCurrentLocationContext(currentLocationContext)
                    // productAdapter.notifyDataSetChanged()
                    Log.d(TAG,"Contexto cambiado a $currentLocationContext. Notificando al adapter...")
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        Log.d(TAG, "TabLayout Listener configurado.")
        // Asegurar que el adapter tenga el contexto inicial al principio
        // Necesitaremos modificar el adapter para esto en el siguiente paso.
        // productAdapter.setCurrentLocationContext(currentLocationContext) // Llamada inicial
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
    private fun showSalidaDevolucionDialog(product: Product, allSuppliers: List<Supplier>) {
        if (isDialogOpen) {
            Log.d(TAG, "Dialog for Devolucion already open or opening.")
            return
        }
        isDialogOpen = true

        val currentContext = context ?: run {
            isDialogOpen = false
            return
        }

        val dialogViewInflated = LayoutInflater.from(currentContext).inflate(R.layout.dialog_salida_devolucion_lotes, null)
        val titleView = TextView(currentContext)
        titleView.text = "Devolución: ${product.name}"
        titleView.setPadding(60, 40, 60, 20) // Ajusta el padding según necesites
        titleView.textSize = 18f // Ajusta el tamaño del texto
        titleView.setTextColor(ContextCompat.getColor(currentContext, android.R.color.black)) // Color del texto

        val recyclerViewLotes = dialogViewInflated.findViewById<RecyclerView>(R.id.recyclerViewLotesDevolucionDialog)
        val progressBarLotes = dialogViewInflated.findViewById<ProgressBar>(R.id.progressBarLotesDevolucionDialog)
        val textViewNoLotes = dialogViewInflated.findViewById<TextView>(R.id.textViewNoLotesDevolucionDialog)
        val inputQuantityNet = dialogViewInflated.findViewById<EditText>(R.id.editTextCantidadDevolucion)
        val inputLayoutProveedor = dialogViewInflated.findViewById<TextInputLayout>(R.id.textFieldLayoutProveedorDevolucion)
        val autoCompleteProveedor = dialogViewInflated.findViewById<AutoCompleteTextView>(R.id.autoCompleteTextViewProveedorDevolucion)
        val inputLayoutMotivo = dialogViewInflated.findViewById<TextInputLayout>(R.id.textFieldLayoutMotivoDevolucion)
        val inputMotivo = dialogViewInflated.findViewById<EditText>(R.id.editTextMotivoDevolucion)
        val buttonAceptar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogDevolucionAceptar)
        val buttonCancelar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogDevolucionCancelar)

        val lotAdapter = LotSelectionAdapter() // Reutiliza tu LotSelectionAdapter
        recyclerViewLotes.layoutManager = LinearLayoutManager(currentContext)
        recyclerViewLotes.adapter = lotAdapter

        val activeSupplierNames = allSuppliers.filter { it.isActive }.map { it.name }
        val supplierSpinnerAdapter = ArrayAdapter(currentContext, android.R.layout.simple_dropdown_item_1line, activeSupplierNames)
        autoCompleteProveedor.setAdapter(supplierSpinnerAdapter)
        autoCompleteProveedor.threshold = 0


        val builder = AlertDialog.Builder(currentContext)
        builder.setCustomTitle(titleView)
        builder.setView(dialogViewInflated)
        // No usar setPositive/NegativeButton aquí, ya que los botones están en el layout.

        val alertDialog = builder.create()
        alertDialog.setOnDismissListener { isDialogOpen = false }


        buttonCancelar.setOnClickListener {
            alertDialog.dismiss()
        }

        buttonAceptar.setOnClickListener {
            val quantityString = inputQuantityNet.text.toString()
            val quantityToDevolver = quantityString.toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()
            val proveedorNameInput = autoCompleteProveedor.text.toString().trim()
            val motivoInput = inputMotivo.text.toString().trim()

            var validationError = false
            inputQuantityNet.error = null
            inputLayoutProveedor.error = null
            inputLayoutMotivo.error = null

            if (quantityToDevolver == null || quantityToDevolver <= 0.0) {
                inputQuantityNet.error = "Cantidad > 0.0"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            if (quantityToDevolver != null && lotAdapter.currentList.isNotEmpty() && quantityToDevolver > selectedLotsTotalNetQuantity + 0.1) { //  epsilon
                inputQuantityNet.error = "Excede stock lotes selecc. (${String.format("%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }
            if (proveedorNameInput.isEmpty()) {
                inputLayoutProveedor.error = "Proveedor obligatorio"
                validationError = true
            }
            if (motivoInput.isEmpty()) {
                inputLayoutMotivo.error = "Motivo obligatorio"
                validationError = true
            }

            if (!validationError && quantityToDevolver != null) {
                if (lotAdapter.currentList.isEmpty() && quantityToDevolver > 0.0) {
                    Toast.makeText(context, "No hay lotes disponibles de donde devolver.", Toast.LENGTH_SHORT).show()
                } else if (selectedLotIds.isNotEmpty()){
                    // Verificar si el proveedor existe o es nuevo
                    val matchedSupplier = allSuppliers.find { it.name.equals(proveedorNameInput, ignoreCase = true) && it.isActive }
                    performSalidaDevolucion(product, quantityToDevolver, selectedLotIds, proveedorNameInput, matchedSupplier, motivoInput, allSuppliers)
                    alertDialog.dismiss()
                } else if (lotAdapter.currentList.isEmpty() && quantityToDevolver <= 0.0){
                    alertDialog.dismiss() // No hay nada que hacer
                } else {
                    Toast.makeText(context, "Verifica cantidad y selección de lotes.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Cargar lotes
        progressBarLotes.visibility = View.VISIBLE
        textViewNoLotes.visibility = View.GONE
        recyclerViewLotes.visibility = View.GONE

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", product.id)
            .whereEqualTo("location", Location.MATRIZ)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        lotsQuery.get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || _binding == null) { // Comprobar también _binding
                    isDialogOpen = false
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    return@addOnSuccessListener
                }
                progressBarLotes.visibility = View.GONE
                if (snapshot != null && !snapshot.isEmpty) {
                    val loadedLots = snapshot.documents.mapNotNull { doc ->
                        try { doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
                        catch (e: Exception) { null }
                    }
                    lotAdapter.submitList(loadedLots)
                    textViewNoLotes.visibility = View.GONE
                    recyclerViewLotes.visibility = View.VISIBLE
                } else {
                    textViewNoLotes.text = "No hay lotes disponibles en Matriz."
                    textViewNoLotes.visibility = View.VISIBLE
                    recyclerViewLotes.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || _binding == null) {
                    isDialogOpen = false
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    return@addOnFailureListener
                }
                progressBarLotes.visibility = View.GONE
                textViewNoLotes.text = "Error al cargar lotes."
                textViewNoLotes.visibility = View.VISIBLE
                recyclerViewLotes.visibility = View.GONE
                Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
                // No cerramos el diálogo aquí, el usuario puede reintentar o cancelar
            }
        alertDialog.show()
    }

    private fun loadSuppliersAndShowSalidaDevolucionDialog(product: Product) {
        if (_binding == null || !isAdded) return

        showListLoading(true) // Muestra un ProgressBar general si es necesario
        Log.d(TAG, "Cargando proveedores para diálogo de devolución...")

        firestore.collection("suppliers")
            .orderBy("name")
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                showListLoading(false)

                val allSuppliers = snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.toObject(Supplier::class.java)?.copy(id = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error convirtiendo proveedor: ${doc.id}", e)
                        null
                    }
                }
                Log.d(TAG, "${allSuppliers.size} proveedores cargados para devolución.")
                showSalidaDevolucionDialog(product, allSuppliers)
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                showListLoading(false)
                Log.e(TAG, "Error cargando proveedores para devolución", e)
                Toast.makeText(context, "Error al cargar proveedores.", Toast.LENGTH_SHORT).show()
                isDialogOpen = false // Asegurar que se resetea si falla la carga
            }
    }

    private fun performTraspasoC04ToMatriz(
        productArgument: Product,
        quantityToTraspasarTotal: Double,
        selectedLotIdsFromC04: List<String>
    ) {
        val currentUser = auth.currentUser ?: run {
            Toast.makeText(context, "Error de autenticación.", Toast.LENGTH_SHORT).show()
            isDialogOpen = false
            return
        }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"
        val traspasoTimestamp = Date()

        showListLoading(true)

        val productRef = firestore.collection("products").document(productArgument.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        firestore.runTransaction { transaction ->
            val productSnapshot = transaction.get(productRef)
            val currentProduct = productSnapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado: ${productArgument.name}", FirebaseFirestoreException.Code.ABORTED)

            val lotesOrigenC04Snapshots = selectedLotIdsFromC04.map { lotId ->
                transaction.get(firestore.collection("inventoryLots").document(lotId))
            }

            val lotesOrigenC04 = lotesOrigenC04Snapshots.mapNotNull { snapshot ->
                if (!snapshot.exists()) throw FirebaseFirestoreException("Lote origen C04 ${snapshot.id} no encontrado.", FirebaseFirestoreException.Code.ABORTED)
                snapshot.toObject(StockLot::class.java)?.copy(id = snapshot.id)
                    ?: throw FirebaseFirestoreException("Error convirtiendo lote origen C04 ${snapshot.id}.", FirebaseFirestoreException.Code.ABORTED)
            }.sortedBy { it.receivedAt ?: Date(0) } // Fecha en que llegaron a C04

            val totalDisponibleEnLotesC04Seleccionados = lotesOrigenC04.sumOf { it.currentQuantity }
            if (quantityToTraspasarTotal - totalDisponibleEnLotesC04Seleccionados > stockEpsilon) {
                throw FirebaseFirestoreException("Stock insuficiente en lotes de C-04 seleccionados (${String.format(Locale.getDefault(), "%.2f", totalDisponibleEnLotesC04Seleccionados)} ${currentProduct.unit})", FirebaseFirestoreException.Code.ABORTED)
            }

            var cantidadRestantePorTraspasarGlobal = quantityToTraspasarTotal
            val idsLotesOrigenAfectadosConCantidad = mutableListOf<String>()
            val idsNuevosLotesDestinoConCantidad = mutableListOf<String>()

            for (loteOrigenC04 in lotesOrigenC04) {
                if (cantidadRestantePorTraspasarGlobal <= stockEpsilon) break

                val cantidadATraspasarDeEsteLote = kotlin.math.min(loteOrigenC04.currentQuantity, cantidadRestantePorTraspasarGlobal)

                if (cantidadATraspasarDeEsteLote > stockEpsilon) {
                    val nuevaCantidadEnLoteOrigenC04 = loteOrigenC04.currentQuantity - cantidadATraspasarDeEsteLote
                    transaction.update(
                        firestore.collection("inventoryLots").document(loteOrigenC04.id),
                        mapOf(
                            "currentQuantity" to nuevaCantidadEnLoteOrigenC04,
                            "isDepleted" to (nuevaCantidadEnLoteOrigenC04 <= stockEpsilon)
                        )
                    )
                    idsLotesOrigenAfectadosConCantidad.add("${loteOrigenC04.id.takeLast(4)}:${String.format(Locale.getDefault(), "%.2f", cantidadATraspasarDeEsteLote)}")

                    val newStockLotMatrizRef = firestore.collection("inventoryLots").document()
                    val nuevoLoteEnMatriz = StockLot(
                        id = newStockLotMatrizRef.id,
                        productId = loteOrigenC04.productId,
                        productName = loteOrigenC04.productName,
                        unit = loteOrigenC04.unit,
                        location = Location.MATRIZ, // Destino es Matriz
                        // Heredar proveedor y lotNumber del sublote de C04, que a su vez los heredó del padre original si existían
                        supplierId = loteOrigenC04.supplierId,
                        supplierName = loteOrigenC04.supplierName,
                        lotNumber = loteOrigenC04.lotNumber,
                        receivedAt = traspasoTimestamp, // Fecha en que llega de regreso a Matriz
                        movementIdIn = newMovementRef.id,
                        initialQuantity = cantidadATraspasarDeEsteLote,
                        currentQuantity = cantidadATraspasarDeEsteLote,
                        isDepleted = false,
                        isPackaged = loteOrigenC04.isPackaged, // Heredar estado de empaque
                        expirationDate = loteOrigenC04.expirationDate,
                        // Los campos original... no se propagan al nuevo lote en Matriz,
                        // ya que este lote en Matriz no proviene directamente de un "lote padre de compra".
                        // Su origen es un traspaso desde C04.
                        originalLotId = null,
                        originalReceivedAt = null,
                        originalSupplierName = null,
                        originalLotNumber = null
                    )
                    transaction.set(newStockLotMatrizRef, nuevoLoteEnMatriz)
                    idsNuevosLotesDestinoConCantidad.add("${newStockLotMatrizRef.id.takeLast(4)}:${String.format(Locale.getDefault(), "%.2f", cantidadATraspasarDeEsteLote)}")

                    cantidadRestantePorTraspasarGlobal -= cantidadATraspasarDeEsteLote
                }
            }

            if (kotlin.math.abs(cantidadRestantePorTraspasarGlobal) > stockEpsilon && quantityToTraspasarTotal > stockEpsilon) {
                throw FirebaseFirestoreException("Discrepancia al procesar cantidades de traspaso C04->M. Restante: $cantidadRestantePorTraspasarGlobal", FirebaseFirestoreException.Code.ABORTED)
            }

            val nuevoStockC04 = currentProduct.stockCongelador04 - quantityToTraspasarTotal
            val nuevoStockMatriz = currentProduct.stockMatriz + quantityToTraspasarTotal

            transaction.update(productRef, mapOf(
                "stockCongelador04" to nuevoStockC04,
                "stockMatriz" to nuevoStockMatriz,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            val movement = StockMovement(
                id = newMovementRef.id,
                userId = currentUser.uid,
                userName = currentUserName,
                productId = currentProduct.id,
                productName = currentProduct.name,
                type = MovementType.TRASPASO_C04_M,
                quantity = quantityToTraspasarTotal,
                locationFrom = Location.CONGELADOR_04,
                locationTo = Location.MATRIZ,
                reason = "Origen(C04): ${idsLotesOrigenAfectadosConCantidad.joinToString()}; Destino(M): ${idsNuevosLotesDestinoConCantidad.joinToString()}",
                stockAfterCongelador04 = nuevoStockC04,
                stockAfterMatriz = nuevoStockMatriz,
                stockAfterTotal = currentProduct.totalStock,
                timestamp = traspasoTimestamp
            )
            transaction.set(newMovementRef, movement)
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Traspaso C-04 -> Matriz realizado: ${String.format(Locale.getDefault(), "%.2f", quantityToTraspasarTotal)} ${productArgument.unit}", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                e.message ?: "Error de datos durante el traspaso C04->M."
            } else {
                "Error registrando traspaso C04->M: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.addOnCompleteListener {
            showListLoading(false)
            isDialogOpen = false
        }
    }

    private fun showEditC04Dialog(product: Product) {
        if (context == null) { Log.e(TAG, "Contexto nulo showEditC04Dialog"); isDialogOpen = false; return }
        val builder = AlertDialog.Builder(requireContext())
        val currentStockFormatted = String.format(Locale.getDefault(), "%.2f", product.stockCongelador04)
        builder.setTitle("Ajustar Stock 04: ${product.name}")
        builder.setMessage("Stock actual en 04: $currentStockFormatted ${product.unit}\nNOTA: Esto registra una SALIDA por la diferencia.")
        val container = FrameLayout(requireContext()); val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = resources.getDimensionPixelSize(R.dimen.dialog_margin); params.leftMargin = margin; params.rightMargin = margin
        val inputNewQuantity = EditText(requireContext()); inputNewQuantity.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        inputNewQuantity.hint = "Nueva cantidad en C04"; inputNewQuantity.layoutParams = params; container.addView(inputNewQuantity)
        builder.setView(container)
        builder.setPositiveButton("Ajustar Stock") { _, _ -> // El dialog se cierra por defecto
            val quantityString = inputNewQuantity.text.toString()
            try {
                val newQuantity = quantityString.toDoubleOrNull()
                if (newQuantity == null || newQuantity < 0.0) { Toast.makeText(context, "Inválido (>= 0.0)", Toast.LENGTH_SHORT).show(); isDialogOpen = false; return@setPositiveButton }
                if (newQuantity > product.stockCongelador04) { Toast.makeText(context, "Error: Nueva cantidad > actual (${String.format("%.2f", product.stockCongelador04)})", Toast.LENGTH_LONG).show(); isDialogOpen = false; return@setPositiveButton }
                val quantityDifference = product.stockCongelador04 - newQuantity
                if (quantityDifference <= 0.1) { Toast.makeText(context, "No se requiere ajuste (igual o mayor)", Toast.LENGTH_SHORT).show(); isDialogOpen = false; return@setPositiveButton } // Tolerancia
                val limit = (product.stockCongelador04 * 0.40)
                if (quantityDifference > limit && product.stockCongelador04 > 0.0) {
                    isDialogOpen = false // Permitir nuevo diálogo
                    AlertDialog.Builder(requireContext()).setTitle("Confirmar Ajuste Grande").setMessage("Salida de ${String.format("%.2f", quantityDifference)} ${product.unit} (a ${String.format("%.2f", newQuantity)}). ¿Continuar?")
                        .setPositiveButton("Sí") { _, _ -> performEditC04(product, newQuantity, quantityDifference) } // Llama a versión con Double
                        .setNegativeButton("No", null)
                        .setOnDismissListener { if(!isDialogOpen) isDialogOpen = false }
                        .show()
                } else { performEditC04(product, newQuantity, quantityDifference) } // Llama a versión con Double
            } catch (e: NumberFormatException) { Toast.makeText(context, "Número inválido.", Toast.LENGTH_SHORT).show(); isDialogOpen = false; }
        }
        builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        showDebouncedDialogWithCustomView(dialog)
    }

    private fun performEditC04(product: Product, newQuantityC04: Double, quantityDifference: Double) {
        if (product.id.isEmpty()) { Log.e(TAG,"Error ID"); Toast.makeText(context, "Error ID", Toast.LENGTH_LONG).show(); return }
        val currentUser = auth.currentUser; if (currentUser == null) { Toast.makeText(context,"Error user", Toast.LENGTH_SHORT).show(); return }
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
                throw FirebaseFirestoreException("No se requiere ajuste.", FirebaseFirestoreException.Code.CANCELLED) // Usar CANCELLED para evitar mensaje de error genérico
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
            Toast.makeText(context, "Stock C04 ajustado a ${String.format("%.2f",newQuantityC04)} (-${String.format("%.2f",quantityDifference)} ${product.unit})", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && (e.code == FirebaseFirestoreException.Code.ABORTED || e.code == FirebaseFirestoreException.Code.CANCELLED) ) {
                e.message // Muestra el mensaje específico de la transacción (ej. "Stock insuficiente", "No se requiere ajuste")
            } else { "Error al ajustar stock C04: ${e.message}" }
            Toast.makeText(context, msg ?: "Error desconocido", Toast.LENGTH_LONG).show()
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
                    Log.d(TAG, "fetchCurrentUserRole: Rol asignado: $currentUserRole")
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
                Log.e(TAG, "fetchCurrentUserRole: Error", e)
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
            if (isDialogOpen) { // Chequeo Debounce FAB
                Log.d(TAG, "Dialog open, ignoring FAB click.")
                return@setOnClickListener
            }
            isDialogOpen = true
            Log.d(TAG, "FAB Add Product Clicked")

            val addFragment = AddEditProductFragment.newInstance(null)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, addFragment)
                .addToBackStack("AddProductFragment").commit()
            isDialogOpen = false // Resetear para permitir volver a clickear rápido si la navegación falla
        }
        updateFabVisibility() // Llama a la función existente
    }




            private fun updateFabVisibility() {
        val canAdd = currentUserRole == UserRole.ADMIN
        if (_binding != null) {
            binding.fabAddProduct.visibility = if (canAdd) View.VISIBLE else View.GONE
        }
    }


    private fun showListLoading(isLoading: Boolean) {
        if (_binding != null) {
            // Asume que tienes un ProgressBar con id progressBarList en fragment_product_list.xml
            binding.progressBarList.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onAddCompraClicked(product: Product) {
        if (isDialogOpen) { Log.d(TAG,"Dialog open, ignoring Compra click."); return }
        isDialogOpen = true
        Log.d(TAG, "Action: Compra ${product.name}")
        loadSuppliersAndShowAddCompraDialog(product) // Esta llamará a showDebouncedDialogWithCustomView indirectamente
    }

    private fun loadSuppliersAndShowAddCompraDialog(product: Product) {
        if (_binding != null) showListLoading(true)
        Log.d(TAG, "loadSuppliersAndShowAddCompraDialog: Cargando TODOS los proveedores...")
        // Llamamos a la versión que carga TODOS
        loadSuppliersForDialog { allSuppliers ->
            if (_binding != null) showListLoading(false)
            if (!isAdded || context == null) {
                Log.w(TAG, "loadSuppliersAndShowAddCompraDialog: Fragmento no añadido o contexto nulo.")
                return@loadSuppliersForDialog
            }
            Log.d(TAG, "loadSuppliersAndShowAddCompraDialog: Proveedores recibidos (${allSuppliers.size}). Mostrando diálogo.")
            // Pasamos TODOS los proveedores al diálogo
            showAddCompraDialog(product, allSuppliers)
        }
    }


    private fun loadSuppliersForDialog(callback: (List<Supplier>) -> Unit) {
        Log.d(TAG, "Iniciando carga de TODOS los proveedores...") // Cambiado log
        firestore.collection("suppliers")
            // QUITAR FILTRO -> .whereEqualTo("isActive", true)
            .orderBy("name")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot != null) {
                    try {
                        val suppliers = snapshot.documents.mapNotNull { doc ->
                            try {
                                doc.toObject(Supplier::class.java)?.copy(id = doc.id)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error convirtiendo proveedor individual: ${doc.id}", e)
                                null
                            }
                        }
                        // Ahora tenemos TODOS los proveedores, activos e inactivos
                        Log.d(TAG, "loadSuppliersForDialog Success: ${suppliers.size} proveedores TOTALES cargados.")
                        callback(suppliers)
                    } catch (e: Exception) {
                        Log.e(TAG, "loadSuppliersForDialog Error: Excepción GENERAL convirtiendo proveedores", e)
                        callback(emptyList())
                    }
                } else {
                    Log.w(TAG, "loadSuppliersForDialog Success: Snapshot es null.")
                    callback(emptyList())
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "loadSuppliersForDialog Failure: Error en query de proveedores", e)
                activity?.runOnUiThread {
                    Toast.makeText(context, "Error cargando proveedores.", Toast.LENGTH_SHORT).show()
                }
                callback(emptyList())
            }
    }

    override fun onSalidaClicked(product: Product, anchorView: View) {
        if (isDialogOpen) {
            Log.d(TAG, "Dialog or menu already open, ignoring Salida click.")
            return
        }
        isDialogOpen = true // Marcar que un menú/diálogo se está abriendo

        val popupContext = context ?: run {
            isDialogOpen = false
            return
        }
        val popup = androidx.appcompat.widget.PopupMenu(popupContext, anchorView)
        try {
            popup.menuInflater.inflate(R.menu.popup_salida_menu, popup.menu)
        } catch (e: Exception) {
            Toast.makeText(popupContext, "Error al mostrar opciones de salida", Toast.LENGTH_SHORT).show()
            isDialogOpen = false
            return
        }

        popup.setOnDismissListener {
            isDialogOpen = false // Resetear cuando el popup se cierra
        }

        popup.setOnMenuItemClickListener { menuItem: MenuItem ->
            // Reset isDialogOpen aquí antes de abrir un nuevo diálogo.
            // La función que abre el diálogo (showSalidaConsumoDialog/showSalidaDevolucionDialog)
            // debe poner isDialogOpen = true de nuevo.
            isDialogOpen = false
            when (menuItem.itemId) {
                R.id.action_salida_consumo -> {
                    showSalidaConsumoDialog(product) // Esta ya existe y maneja su propio isDialogOpen
                    true
                }
                R.id.action_salida_devolucion -> {
                    // Cargar proveedores y luego mostrar diálogo de devolución
                    loadSuppliersAndShowSalidaDevolucionDialog(product)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }


    private fun showSalidaConsumoDialog(product: Product) {
        val currentContext = context ?: run {
            isDialogOpen = false
            return
        }

        val dialogViewInflated = LayoutInflater.from(currentContext).inflate(R.layout.dialog_salida_consumo_lotes, null)
        val inputQuantityNet = dialogViewInflated.findViewById<EditText>(R.id.editTextCantidadConsumo)
        val progressBarLotesDialog = dialogViewInflated.findViewById<ProgressBar>(R.id.progressBarLotesDialog)
        val textViewNoLotesDialog = dialogViewInflated.findViewById<TextView>(R.id.textViewNoLotesDialog)
        val recyclerViewLotes = dialogViewInflated.findViewById<RecyclerView>(R.id.recyclerViewLotesDialog)
        val buttonAceptar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogAceptar)
        val buttonCancelar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogCancelar)

        val lotAdapter = LotSelectionAdapter()
        recyclerViewLotes.layoutManager = LinearLayoutManager(currentContext)
        recyclerViewLotes.adapter = lotAdapter

        val builder = AlertDialog.Builder(currentContext)
        val titleView = TextView(currentContext)
        titleView.text = "Consumo: ${product.name}"
        titleView.setPadding(60, 40, 60, 20)
        titleView.textSize = 18f
        titleView.setTextColor(ContextCompat.getColor(currentContext, android.R.color.black))
        builder.setCustomTitle(titleView)
        builder.setView(dialogViewInflated)

        progressBarLotesDialog.visibility = View.VISIBLE
        textViewNoLotesDialog.visibility = View.GONE
        recyclerViewLotes.visibility = View.GONE
        inputQuantityNet.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        val alertDialog = builder.create()

        buttonCancelar.setOnClickListener {
            alertDialog.dismiss()
        }

        buttonAceptar.setOnClickListener {
            val quantityString = inputQuantityNet.text.toString()
            val quantityToConsume = quantityString.toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            if (quantityToConsume == null || quantityToConsume <= 0.0) {
                inputQuantityNet.error = "Cantidad > 0.0"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
                validationError = true
            } else if (quantityToConsume != null && lotAdapter.currentList.isNotEmpty() && quantityToConsume > selectedLotsTotalNetQuantity + 0.1) {
                inputQuantityNet.error = "Excede stock lotes selecc. (${String.format("%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToConsume != null) {
                if (lotAdapter.currentList.isEmpty() && quantityToConsume > 0.0) {
                    Toast.makeText(context, "No hay lotes disponibles de donde consumir.", Toast.LENGTH_SHORT).show()
                } else if (selectedLotIds.isNotEmpty()){
                    performSalidaConsumo(product, quantityToConsume, selectedLotIds) // Llamada a la lógica de transacción
                    alertDialog.dismiss()
                } else if (lotAdapter.currentList.isEmpty() && quantityToConsume <= 0.0){
                    alertDialog.dismiss()
                } else {
                    Toast.makeText(context, "Verifica cantidad y selección.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", product.id)
            .whereEqualTo("location", Location.MATRIZ)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        lotsQuery.get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || context == null || _binding == null) {
                    isDialogOpen = false
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    return@addOnSuccessListener
                }
                progressBarLotesDialog.visibility = View.GONE

                if (snapshot != null && !snapshot.isEmpty) {
                    val loadedLots = snapshot.documents.mapNotNull { doc ->
                        try { doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
                        catch (e: Exception) { null }
                    }
                    lotAdapter.submitList(loadedLots)
                    textViewNoLotesDialog.visibility = View.GONE
                    recyclerViewLotes.visibility = View.VISIBLE
                } else {
                    textViewNoLotesDialog.text = "No hay lotes disponibles en Matriz."
                    textViewNoLotesDialog.visibility = View.VISIBLE
                    recyclerViewLotes.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || context == null) {
                    isDialogOpen = false
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    return@addOnFailureListener
                }
                progressBarLotesDialog.visibility = View.GONE
                textViewNoLotesDialog.text = "Error al cargar lotes."
                textViewNoLotesDialog.visibility = View.VISIBLE
                recyclerViewLotes.visibility = View.GONE
                Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
                isDialogOpen = false
                if(alertDialog.isShowing) alertDialog.dismiss()
            }
        showDebouncedDialogWithCustomView(alertDialog)
    }

    override fun onTraspasoC04MClicked(product: Product) { // SIN anchorView
        if (isDialogOpen) { Log.d(TAG,"Dialog open, ignoring Traspaso C04->M click."); return }
        isDialogOpen = true
        Log.d(TAG, "Action: Traspaso C04->M ${product.name}")
        showTraspasoC04MDialog(product) // Esta debe llamar a showDebouncedDialogWithCustomView internamente
    }

    override fun onItemClicked(product: Product) {
        // Navegar a editar producto (no necesita debounce de diálogo)
        Log.d(TAG,"onItemClicked: Navegando a editar ${product.name}")
        val editFragment = AddEditProductFragment.newInstance(product.id)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, editFragment)
            .addToBackStack("EditProductFragment").commit()
    }


    private fun canUserModify(): Boolean {
        val allowed = currentUserRole == UserRole.ADMIN
        if (!allowed) {
            Toast.makeText(context, "Permiso denegado.", Toast.LENGTH_SHORT).show()
        }
        return allowed
    }

    private fun showAddCompraDialog(product: Product, allSuppliers: List<Supplier>) {
        if (context == null) {
            isDialogOpen = false
            return
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Registrar Compra: ${product.name}")

        val containerLayout = LinearLayout(requireContext())
        containerLayout.orientation = LinearLayout.VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_margin)
        containerLayout.setPadding(padding, padding / 2, padding, padding / 2)

        val inputQuantity = EditText(requireContext())
        inputQuantity.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        inputQuantity.hint = "Cantidad NETA comprada (${product.unit})"
        containerLayout.addView(inputQuantity)

        val activeSupplierNames = allSuppliers.filter { it.isActive }.map { it.name }
        val supplierAdapterSpinner = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, activeSupplierNames)
        val inputSupplierLayout = TextInputLayout(requireContext()) // Usar TextInputLayout de Material
        inputSupplierLayout.hint = "Proveedor (Opcional, recomendado)"
        inputSupplierLayout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        inputSupplierLayout.setPadding(0, padding / 2, 0, 0)
        val inputSupplier = AutoCompleteTextView(requireContext())
        inputSupplier.setAdapter(supplierAdapterSpinner)
        inputSupplier.threshold = 0
        val density = resources.displayMetrics.density
        // El padding del AutoCompleteTextView dentro de TextInputLayout se maneja mejor por el propio TextInputLayout
        // inputSupplier.setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        inputSupplierLayout.addView(inputSupplier)
        containerLayout.addView(inputSupplierLayout)

        val typeLabel = TextView(requireContext())
        typeLabel.text = "Tipo de Recepción: *"
        typeLabel.setPadding(0, padding, 0, padding / 4)
        containerLayout.addView(typeLabel)
        val radioGroupType = RadioGroup(requireContext())
        radioGroupType.orientation = LinearLayout.HORIZONTAL
        val radioButtonEmpacado = RadioButton(requireContext())
        radioButtonEmpacado.id = View.generateViewId()
        radioButtonEmpacado.text = "Empacado"
        radioGroupType.addView(radioButtonEmpacado)
        val radioButtonAGranel = RadioButton(requireContext())
        radioButtonAGranel.id = View.generateViewId()
        radioButtonAGranel.text = "A Granel"
        val paramsRadio = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        paramsRadio.marginStart = padding
        radioButtonAGranel.layoutParams = paramsRadio
        radioGroupType.addView(radioButtonAGranel)
        containerLayout.addView(radioGroupType)

        val dateLabel = TextView(requireContext())
        dateLabel.text = "Fecha de Recepción:"
        dateLabel.setPadding(0, padding, 0, padding / 4)
        containerLayout.addView(dateLabel)

        val dateButton = Button(requireContext(), null, android.R.attr.borderlessButtonStyle)
        val selectedDateCalendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("dd / MM / yy", Locale.getDefault()) // "yy" para año de 2 dígitos
        dateButton.text = dateFormat.format(selectedDateCalendar.time)
        dateButton.setOnClickListener {
            // Definir el listener con los tipos correctos
            val dateSetListener = DatePickerDialog.OnDateSetListener { _: DatePicker?, year: Int, monthOfYear: Int, dayOfMonth: Int ->
                selectedDateCalendar.set(year, monthOfYear, dayOfMonth)
                // Mantener la hora actual al seleccionar la fecha
                val nowCalendar = Calendar.getInstance()
                selectedDateCalendar.set(Calendar.HOUR_OF_DAY, nowCalendar.get(Calendar.HOUR_OF_DAY))
                selectedDateCalendar.set(Calendar.MINUTE, nowCalendar.get(Calendar.MINUTE))
                selectedDateCalendar.set(Calendar.SECOND, nowCalendar.get(Calendar.SECOND))
                dateButton.text = dateFormat.format(selectedDateCalendar.time)
            }
            // Crear el DatePickerDialog correctamente
            val datePickerDialog = android.app.DatePickerDialog( // Especificar android.app.DatePickerDialog
                requireContext(),
                dateSetListener,
                selectedDateCalendar.get(Calendar.YEAR),
                selectedDateCalendar.get(Calendar.MONTH),
                selectedDateCalendar.get(Calendar.DAY_OF_MONTH)
            )
            datePickerDialog.datePicker.maxDate = System.currentTimeMillis() // No permitir fechas futuras
            datePickerDialog.show()
        }
        containerLayout.addView(dateButton)
        builder.setView(containerLayout)

        builder.setPositiveButton("Aceptar") { _, _ ->
            val quantityString = inputQuantity.text.toString()
            val quantityValue = quantityString.toDoubleOrNull()
            val supplierNameInput = inputSupplier.text.toString().trim()
            val selectedRadioId = radioGroupType.checkedRadioButtonId
            var isBulkReception: Boolean? = null // Renombrado para claridad
            if (selectedRadioId == radioButtonAGranel.id) { isBulkReception = true }
            else if (selectedRadioId == radioButtonEmpacado.id) { isBulkReception = false }
            val receptionDate = selectedDateCalendar.time

            var validationError = false
            // Usar stockEpsilon en la validación
            if (quantityValue == null || quantityValue <= stockEpsilon) { // CAMBIO AQUÍ
                inputQuantity.error = "Cantidad debe ser > ${String.format(Locale.getDefault(), "%.2f", stockEpsilon)} ${product.unit}"
                validationError = true
            } else {
                inputQuantity.error = null
            }
            inputSupplierLayout.error = null // Resetear error si lo hubo antes
            if (isBulkReception == null) {
                Toast.makeText(context, "Selecciona Tipo Recepción", Toast.LENGTH_SHORT).show()
                validationError = true
            }

            if (validationError) {
                isDialogOpen = false // Permitir reabrir el diálogo si la validación falla
                return@setPositiveButton
            }

            val currentProductStockMatriz = product.stockMatriz
            // Usar stockEpsilon para la comparación de si el stock es "cero"
            val limit = if (currentProductStockMatriz <= stockEpsilon) quantityValue!! + 1.0 else (currentProductStockMatriz * 0.40)

            if (quantityValue!! > limit) {
                isDialogOpen = false
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirmar Compra")
                    .setMessage("Cantidad grande (${String.format(Locale.getDefault(), "%.2f", quantityValue)} ${product.unit}). ¿Continuar?")
                    .setPositiveButton("Sí") { _, _ ->
                        val potentialMatch = if (supplierNameInput.isNotEmpty()) allSuppliers.find { it.name.equals(supplierNameInput, ignoreCase = true) } else null
                        // Pasar isBulkReception a checkAndPerformCompra
                        checkAndPerformCompra(product, quantityValue, supplierNameInput, potentialMatch, allSuppliers, isBulkReception!!, receptionDate)
                    }
                    .setNegativeButton("No", null)
                    .setOnDismissListener { if(!isDialogOpen) isDialogOpen = false }
                    .show()
            } else {
                val potentialMatch = if (supplierNameInput.isNotEmpty()) allSuppliers.find { it.name.equals(supplierNameInput, ignoreCase = true) } else null
                // Pasar isBulkReception a checkAndPerformCompra
                checkAndPerformCompra(product, quantityValue, supplierNameInput, potentialMatch, allSuppliers, isBulkReception!!, receptionDate)
            }
        }
        builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        showDebouncedDialogWithCustomView(dialog) // Asumo que esta función maneja isDialogOpen
    }

    private fun showDebouncedDialogWithCustomView(dialog: AlertDialog) {
        if (!isAdded || context == null) {
            Log.w(TAG, "showDebouncedDialogWithCustomView: Fragmento no listo, cancelando muestra.")
            isDialogOpen = false
            return
        }
        try {
            // Listener para resetear la bandera CUANDO el diálogo se cierre
            dialog.setOnDismissListener {
                Log.d(TAG, "Dialog dismissed via Listener. Setting isDialogOpen = false")
                isDialogOpen = false
            }
            // Asegurar que el flag está true ANTES de mostrar
            isDialogOpen = true
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando AlertDialog custom", e)
            isDialogOpen = false // Resetear si hubo error al mostrar
        }
    }

    private fun checkAndPerformCompra(
        product: Product,
        quantity: Double,
        supplierNameInput: String,
        potentialMatch: Supplier?,
        allSuppliers: List<Supplier>,
        isBulk: Boolean,
        receptionDate: Date
    ) {
        when {
            potentialMatch != null -> {
                if (potentialMatch.isActive) {
                    performCompra(product, quantity, potentialMatch.id, potentialMatch.name, isBulk, receptionDate)
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Reactivar Proveedor")
                        .setMessage("El proveedor '${potentialMatch.name}' existe pero está inactivo. ¿Deseas reactivarlo y usarlo para esta compra?")
                        .setPositiveButton("Sí, Reactivar y Usar") { _, _ ->
                            reactivateSupplierAndPerformCompra(potentialMatch.id, potentialMatch.name, product, quantity, isBulk, receptionDate)
                        }
                        .setNegativeButton("No, Registrar Sin Proveedor") { _, _ ->
                            performCompra(product, quantity, null, null, isBulk, receptionDate)
                        }
                        .setCancelable(false)
                        .show()
                }
            }
            supplierNameInput.isNotEmpty() -> {
                val newSupplierData = Supplier(name = supplierNameInput, isActive = true)
                if (_binding != null) showListLoading(true)
                firestore.collection("suppliers").add(newSupplierData)
                    .addOnSuccessListener { docRef ->
                        performCompra(product, quantity, docRef.id, supplierNameInput, isBulk, receptionDate)
                    }
                    .addOnFailureListener { e ->
                        if (_binding != null) showListLoading(false)
                        Toast.makeText(context, "Error al crear nuevo proveedor.", Toast.LENGTH_LONG).show()
                        isDialogOpen = false
                    }
            }
            else -> {
                performCompra(product, quantity, null, null, isBulk, receptionDate)
            }
        }
    }

    private fun reactivateSupplierAndPerformCompra(
        supplierId: String,
        supplierName: String,
        product: Product,
        quantity: Double,
        isBulk: Boolean,
        receptionDate: Date
    ) {
        if (_binding != null) showListLoading(true)

        val supplierRef = firestore.collection("suppliers").document(supplierId)
        supplierRef.update(mapOf("isActive" to true, "updatedAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener {
                performCompra(product, quantity, supplierId, supplierName, isBulk, receptionDate)
            }
            .addOnFailureListener { e ->
                if (_binding != null) showListLoading(false)
                Toast.makeText(context, "Error al reactivar proveedor.", Toast.LENGTH_LONG).show()
                isDialogOpen = false
            }
    }

        private fun performCompra(
            productArgument: Product,
            quantityValue: Double,
            supplierId: String?,
            supplierName: String?,
            isBulkReception: Boolean,
            receptionDate: Date // Esta es la fecha seleccionada por el usuario
        ) {
            if (productArgument.id.isEmpty()) {
                Toast.makeText(context, "Error: ID de producto no válido.", Toast.LENGTH_LONG).show()
                isDialogOpen = false
                showListLoading(false)
                return
            }
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Toast.makeText(context, "Error: Usuario no autenticado.", Toast.LENGTH_SHORT).show()
                isDialogOpen = false
                showListLoading(false)
                return
            }
            val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

            if (quantityValue <= stockEpsilon) {
                Toast.makeText(context, "La cantidad comprada debe ser mayor a ${String.format(Locale.getDefault(), "%.2f", stockEpsilon)} ${productArgument.unit}.", Toast.LENGTH_SHORT).show()
                isDialogOpen = false
                showListLoading(false)
                return
            }

            showListLoading(true)

            val productRef = firestore.collection("products").document(productArgument.id)
            val newMovementRef = firestore.collection("stockMovements").document()
            val newStockLotRef = firestore.collection("inventoryLots").document()

            firestore.runTransaction { transaction ->
                val productSnapshot = transaction.get(productRef)
                val currentProduct = productSnapshot.toObject(Product::class.java)
                    ?: throw FirebaseFirestoreException("Producto no encontrado: ${productArgument.name}", FirebaseFirestoreException.Code.ABORTED)

                Log.d(TAG, "performCompra (Dentro Transacción) - Producto: ${currentProduct.name}, requiresPackaging: ${currentProduct.requiresPackaging}")

                val newStockMatriz = currentProduct.stockMatriz + quantityValue
                val newTotalStock = newStockMatriz + currentProduct.stockCongelador04

                val movement = StockMovement(
                    id = newMovementRef.id,
                    timestamp = receptionDate, // Usar la fecha de recepción del diálogo
                    userId = currentUser.uid,
                    userName = currentUserName,
                    productId = currentProduct.id,
                    productName = currentProduct.name,
                    type = MovementType.COMPRA,
                    quantity = quantityValue,
                    locationFrom = Location.PROVEEDOR,
                    locationTo = Location.MATRIZ,
                    reason = if (supplierName != null) "Compra a $supplierName" else "Compra sin proveedor",
                    stockAfterMatriz = newStockMatriz,
                    stockAfterCongelador04 = currentProduct.stockCongelador04,
                    stockAfterTotal = newTotalStock,
                    affectedLotIds = listOf(newStockLotRef.id)
                )
                transaction.set(newMovementRef, movement)

                val newLot = StockLot(
                    id = newStockLotRef.id,
                    productId = currentProduct.id,
                    productName = currentProduct.name,
                    unit = currentProduct.unit,
                    location = Location.MATRIZ,
                    supplierId = supplierId,
                    supplierName = supplierName,
                    receivedAt = receptionDate, // Usar la fecha de recepción del diálogo
                    movementIdIn = newMovementRef.id,
                    initialQuantity = quantityValue,
                    currentQuantity = quantityValue,
                    isDepleted = false,
                    isPackaged = !isBulkReception,
                    lotNumber = null,
                    expirationDate = null
                )
                transaction.set(newStockLotRef, newLot)

                val productUpdateData = hashMapOf<String, Any>(
                    "stockMatriz" to newStockMatriz,
                    "totalStock" to newTotalStock,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "lastUpdatedByName" to currentUserName
                )

                val shouldCreatePackagingTaskThisTime = isBulkReception && currentProduct.requiresPackaging
                Log.d(TAG, "performCompra (Dentro Transacción) - isBulk: $isBulkReception, currentProduct.requiresPackaging: ${currentProduct.requiresPackaging}, shouldCreateTask: $shouldCreatePackagingTaskThisTime")

                if (shouldCreatePackagingTaskThisTime) {
                    // No es necesario actualizar productUpdateData["requiresPackaging"] = true aquí,
                    // ya que currentProduct.requiresPackaging ya debería ser true para que esta condición se cumpla.
                    // Si la intención fuera que una compra a granel *active* el requiresPackaging para un producto
                    // que antes no lo tenía, entonces sí se necesitaría. Por ahora, se asume que el flag
                    // del producto ya está correctamente establecido.

                    Log.d(TAG, "performCompra: CREANDO PendingPackagingTask para ${currentProduct.name} con fecha receivedAt: $receptionDate")
                    val newPackagingTaskRef = firestore.collection("pendingPackaging").document()
                    val packagingTask = PendingPackagingTask(
                        id = newPackagingTaskRef.id,
                        productId = currentProduct.id,
                        productName = currentProduct.name,
                        quantityReceived = quantityValue,
                        unit = currentProduct.unit,
                        purchaseMovementId = newMovementRef.id,
                        receivedAt = receptionDate, // Usar la fecha de recepción del diálogo
                        supplierId = supplierId,
                        supplierName = supplierName
                    )
                    transaction.set(newPackagingTaskRef, packagingTask)
                } else {
                    Log.d(TAG, "performCompra: NO SE CREARÁ PendingPackagingTask para ${currentProduct.name}")
                }
                transaction.update(productRef, productUpdateData)
                currentProduct.copy(stockMatriz = newStockMatriz, totalStock = newTotalStock)
            }.addOnSuccessListener { updatedProductForNotification ->
                if (_binding != null) showListLoading(false)
                Toast.makeText(context, "Compra registrada: +${String.format(Locale.getDefault(), "%.2f", quantityValue)} ${productArgument.unit}", Toast.LENGTH_SHORT).show()
                updatedProductForNotification?.let {
                    viewLifecycleOwner.lifecycleScope.launch {
                        NotificationTriggerHelper.triggerLowStockNotification(it)
                    }
                }
                isDialogOpen = false
            }.addOnFailureListener { e ->
                if (_binding != null) showListLoading(false)
                val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                    e.message ?: "Error de datos."
                } else { "Error registrando compra: ${e.message}" }
                Log.e(TAG, "Error al registrar compra para ${productArgument.name}", e)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                isDialogOpen = false
            }
        }


    private fun performSalidaConsumo(product: Product, quantityToConsume: Double, selectedLotIds: List<String>) {
        if (product.id.isEmpty()) {
            isDialogOpen = false
            return
        }
        if (selectedLotIds.isEmpty() && quantityToConsume > 0.0) {
            Toast.makeText(context, "No se seleccionaron lotes.", Toast.LENGTH_SHORT).show()
            isDialogOpen = false
            return
        }
        if (quantityToConsume <= 0.0 && selectedLotIds.isEmpty()) {
            isDialogOpen = false
            return
        }
        if (quantityToConsume <= 0.0) {
            Toast.makeText(context, "La cantidad a consumir debe ser mayor a cero.", Toast.LENGTH_SHORT).show()
            isDialogOpen = false
            return
        }

        val user = auth.currentUser ?: run {
            isDialogOpen = false
            return
        }
        val currentUserName = user.displayName ?: user.email ?: "Unknown"

        if (_binding != null) showListLoading(true)

        val productRef = firestore.collection("products").document(product.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        firestore.runTransaction { transaction ->
            // --- PRIMERO TODAS LAS LECTURAS ---
            // 1. Leer Producto
            val productSnapshot = transaction.get(productRef)
            val currentProduct = productSnapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

            // 2. Leer Lotes Seleccionados
            val lotObjects = mutableListOf<StockLot>()
            for (lotId in selectedLotIds) {
                val lotRef = firestore.collection("inventoryLots").document(lotId)
                val lotSnapshot = transaction.get(lotRef)
                if (!lotSnapshot.exists()) throw FirebaseFirestoreException("Lote seleccionado $lotId no encontrado.", FirebaseFirestoreException.Code.ABORTED)
                val stockLot = lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                    ?: throw FirebaseFirestoreException("Error convirtiendo lote $lotId.", FirebaseFirestoreException.Code.ABORTED)
                lotObjects.add(stockLot)
            }
            val sortedLots = lotObjects.sortedBy { lot -> lot.receivedAt ?: Date(0) }
            // --- FIN LECTURAS ---


            // --- VALIDACIONES Y CÁLCULOS (Usando datos leídos) ---
            val totalSelectedStock = sortedLots.sumOf { lot -> lot.currentQuantity }
            if (quantityToConsume > totalSelectedStock + 0.1) {
                throw FirebaseFirestoreException("Stock neto insuficiente en lotes (${String.format("%.2f", totalSelectedStock)} ${product.unit})", FirebaseFirestoreException.Code.ABORTED)
            }

            var remainingToConsume = quantityToConsume
            val lotsToUpdateData = mutableMapOf<String, Map<String, Any>>()
            val affectedLotDetails = mutableListOf<String>()
            val lotIdsToUpdate = mutableListOf<String>()

            for (lot in sortedLots) {
                if (remainingToConsume <= 0.1) break

                val quantityFromThisLot = kotlin.math.min(remainingToConsume, lot.currentQuantity)
                if (quantityFromThisLot > 0.1) {
                    val newLotQuantity = lot.currentQuantity - quantityFromThisLot
                    val isNowDepleted = newLotQuantity <= 0.1

                    lotsToUpdateData[lot.id] = mapOf(
                        "currentQuantity" to newLotQuantity,
                        "isDepleted" to isNowDepleted
                    )
                    lotIdsToUpdate.add(lot.id)
                    remainingToConsume -= quantityFromThisLot
                    affectedLotDetails.add("${lot.id.takeLast(4)}:${String.format("%.2f", quantityFromThisLot)}")
                }
            }

            if (lotsToUpdateData.isEmpty() && quantityToConsume > 0.1) {
                throw FirebaseFirestoreException("Error al calcular el descuento de lotes.", FirebaseFirestoreException.Code.ABORTED)
            }

            val newStockMatrizCalculated = currentProduct.stockMatriz - quantityToConsume
            val newTotalStockCalculated = currentProduct.totalStock - quantityToConsume

            if (newStockMatrizCalculated < -0.1 || newTotalStockCalculated < -0.1) {
                throw FirebaseFirestoreException("Error de consistencia en stock producto post-cálculo.", FirebaseFirestoreException.Code.ABORTED)
            }
            // --- FIN VALIDACIONES Y CÁLCULOS ---


            // --- AHORA TODAS LAS ESCRITURAS ---
            // 3. Escribir (Actualizar) Lotes
            for ((lotId, updateData) in lotsToUpdateData) {
                val lotRefFS = firestore.collection("inventoryLots").document(lotId)
                transaction.update(lotRefFS, updateData)
            }

            // 4. Escribir (Actualizar) Producto
            transaction.update(productRef, mapOf(
                "stockMatriz" to newStockMatrizCalculated,
                "totalStock" to newTotalStockCalculated,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            // 5. Escribir (Crear) Movimiento
            val movement = StockMovement(
                id = newMovementRef.id,
                userId = user.uid,
                userName = currentUserName,
                productId = product.id,
                productName = currentProduct.name,
                type = MovementType.SALIDA_CONSUMO,
                quantity = quantityToConsume,
                locationFrom = Location.MATRIZ,
                locationTo = Location.EXTERNO,
                reason = "Lotes: ${affectedLotDetails.joinToString()}",
                stockAfterMatriz = newStockMatrizCalculated,
                stockAfterCongelador04 = currentProduct.stockCongelador04,
                stockAfterTotal = newTotalStockCalculated,
                timestamp = Date(),
                affectedLotIds = lotIdsToUpdate.distinct()
            )
            transaction.set(newMovementRef, movement)
            // --- FIN ESCRITURAS ---

            null
        }.addOnSuccessListener {
            if(_binding != null) showListLoading(false)
            Toast.makeText(context, "Salida Consumo registrada: -${String.format("%.2f", quantityToConsume)} ${product.unit}", Toast.LENGTH_SHORT).show()
            productRef.get().addOnSuccessListener { updatedDoc ->
                if(isAdded && context != null) {
                    updatedDoc.toObject(Product::class.java)?.let { updatedProduct ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            NotificationTriggerHelper.triggerLowStockNotification(updatedProduct)
                        }
                    }
                }
            }
            isDialogOpen = false
        }.addOnFailureListener { e ->
            if(_binding != null) showListLoading(false)
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                e.message ?: "Error de datos durante salida por consumo."
            } else {
                "Error registrando salida por consumo: ${e.message}"
            }
            // El mensaje de Firestore ahora debería ser más útil, ej. "Stock neto insuficiente..."
            context?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
            isDialogOpen = false
        }
    }


    private fun performSalidaDevolucion(
        product: Product,
        quantityToDevolver: Double,
        selectedLotIds: List<String>,
        proveedorNameInput: String,
        matchedSupplier: Supplier?,
        motivo: String,
        allSuppliers: List<Supplier> // Para crear nuevo proveedor si es necesario
    ) {
        val user = auth.currentUser ?: run {
            Toast.makeText(context, "Error de autenticación.", Toast.LENGTH_SHORT).show()
            isDialogOpen = false
            return
        }
        val currentUserName = user.displayName ?: user.email ?: "Unknown"

        if (_binding != null) showListLoading(true)

        firestore.runTransaction { transaction ->
            // --- LECTURAS PRIMERO ---
            val productSnapshot = transaction.get(firestore.collection("products").document(product.id))
            val currentProduct = productSnapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

            val lotObjects = mutableListOf<StockLot>()
            for (lotId in selectedLotIds) {
                val lotRef = firestore.collection("inventoryLots").document(lotId)
                val lotSnapshot = transaction.get(lotRef)
                if (!lotSnapshot.exists()) throw FirebaseFirestoreException("Lote $lotId no encontrado.", FirebaseFirestoreException.Code.ABORTED)
                val stockLot = lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                    ?: throw FirebaseFirestoreException("Error convirtiendo lote $lotId.", FirebaseFirestoreException.Code.ABORTED)
                lotObjects.add(stockLot)
            }
            val sortedLots = lotObjects.sortedBy { it.receivedAt ?: Date(0) }
            // --- FIN LECTURAS ---

            // --- VALIDACIONES Y CÁLCULOS ---
            val totalSelectedStockNet = sortedLots.sumOf { it.currentQuantity }
            if (quantityToDevolver > totalSelectedStockNet + 0.1) {
                throw FirebaseFirestoreException("Stock neto insuficiente en lotes (${String.format("%.2f", totalSelectedStockNet)} ${product.unit})", FirebaseFirestoreException.Code.ABORTED)
            }

            var supplierIdToUse: String? = matchedSupplier?.id
            var supplierNameToUse: String = proveedorNameInput

            // Si no hay match exacto con un proveedor activo, y el nombre no está vacío, se crea uno nuevo
            if (matchedSupplier == null && proveedorNameInput.isNotBlank()) {
                val existingAnyCase = allSuppliers.find { it.name.equals(proveedorNameInput, ignoreCase = true) }
                if (existingAnyCase != null) { // Existe pero quizás inactivo o caso diferente
                    if (!existingAnyCase.isActive) {
                        // Si existe inactivo, se podría decidir reactivarlo aquí o manejarlo como error/advertencia
                        // Por ahora, para simplificar, si no es activo, crearemos uno nuevo con el nombre exacto ingresado
                        // O se puede lanzar una excepción para que el usuario lo active primero.
                        // Optamos por crear uno nuevo si el activo no coincide exactamente.
                        Log.w(TAG, "Proveedor '${existingAnyCase.name}' encontrado pero inactivo. Se creará uno nuevo si no hay match activo.")
                        // Si la lógica es crear nuevo si no hay activo con ese nombre:
                        val newSupplierRef = firestore.collection("suppliers").document()
                        val newSupplier = Supplier(id = newSupplierRef.id, name = proveedorNameInput, isActive = true, createdAt = Date(), updatedAt = Date())
                        transaction.set(newSupplierRef, newSupplier)
                        supplierIdToUse = newSupplierRef.id
                        supplierNameToUse = proveedorNameInput // Ya es el nombre ingresado
                    } else { // Existe y está activo, pero el case no coincidió, usamos el de la BD
                        supplierIdToUse = existingAnyCase.id
                        supplierNameToUse = existingAnyCase.name // Usar el nombre canónico de la BD
                    }

                } else { // No existe en absoluto
                    val newSupplierRef = firestore.collection("suppliers").document()
                    val newSupplier = Supplier(id = newSupplierRef.id, name = proveedorNameInput, isActive = true, createdAt = Date(), updatedAt = Date())
                    transaction.set(newSupplierRef, newSupplier)
                    supplierIdToUse = newSupplierRef.id
                }
            } else if (matchedSupplier != null) { // Hubo match con un proveedor activo
                supplierNameToUse = matchedSupplier.name // Usar nombre canónico
            }


            var remainingToDevolver = quantityToDevolver
            val lotsToUpdateData = mutableMapOf<String, Map<String, Any>>()
            val affectedLotDetails = mutableListOf<String>()
            val lotIdsToUpdate = mutableListOf<String>()

            for (lot in sortedLots) {
                if (remainingToDevolver <= 0.1) break

                val quantityFromThisLot = kotlin.math.min(remainingToDevolver, lot.currentQuantity)
                if (quantityFromThisLot > 0.1) {
                    val newLotQuantity = lot.currentQuantity - quantityFromThisLot
                    val isNowDepleted = newLotQuantity <= 0.1 // Epsilon

                    lotsToUpdateData[lot.id] = mapOf(
                        "currentQuantity" to newLotQuantity,
                        "isDepleted" to isNowDepleted
                    )
                    lotIdsToUpdate.add(lot.id)
                    remainingToDevolver -= quantityFromThisLot
                    affectedLotDetails.add("${lot.id.takeLast(4)}:${String.format("%.2f", quantityFromThisLot)}")
                }
            }
            if (lotsToUpdateData.isEmpty() && quantityToDevolver > 0.1) {
                throw FirebaseFirestoreException("Error al calcular descuento de lotes para devolución.", FirebaseFirestoreException.Code.ABORTED)
            }


            val newStockMatrizCalculated = currentProduct.stockMatriz - quantityToDevolver
            val newTotalStockCalculated = currentProduct.totalStock - quantityToDevolver

            if (newStockMatrizCalculated < -0.1 || newTotalStockCalculated < -0.1) { // Epsilon
                throw FirebaseFirestoreException("Error de consistencia en stock producto post-cálculo.", FirebaseFirestoreException.Code.ABORTED)
            }
            // --- FIN VALIDACIONES Y CÁLCULOS ---

            // --- ESCRITURAS ---
            for ((lotId, updateData) in lotsToUpdateData) {
                transaction.update(firestore.collection("inventoryLots").document(lotId), updateData)
            }

            transaction.update(firestore.collection("products").document(product.id), mapOf(
                "stockMatriz" to newStockMatrizCalculated,
                "totalStock" to newTotalStockCalculated,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            val newMovementRef = firestore.collection("stockMovements").document()
            val movement = StockMovement(
                id = newMovementRef.id,
                userId = user.uid,
                userName = currentUserName,
                productId = product.id,
                productName = currentProduct.name,
                type = MovementType.SALIDA_DEVOLUCION,
                quantity = quantityToDevolver,
                locationFrom = Location.MATRIZ,
                locationTo = Location.PROVEEDOR, // O el ID del proveedor si lo tienes
                reason = "Lotes: ${affectedLotDetails.joinToString()} | Motivo: $motivo",
                stockAfterMatriz = newStockMatrizCalculated,
                stockAfterCongelador04 = currentProduct.stockCongelador04,
                stockAfterTotal = newTotalStockCalculated,
                timestamp = Date(),
                affectedLotIds = lotIdsToUpdate.distinct()
            )
            transaction.set(newMovementRef, movement)

            val newDevolucionPendienteRef = firestore.collection("pendingDevoluciones").document()
            val devolucionPendiente = DevolucionPendiente(
                id = newDevolucionPendienteRef.id,
                productId = product.id,
                productName = currentProduct.name,
                quantity = quantityToDevolver,
                unit = currentProduct.unit, // Añadir unidad
                provider = supplierNameToUse, // Nombre del proveedor
                reason = motivo,
                userId = user.uid,
                registeredAt = Date(), // Usar fecha actual para el registro de la devolución
                status = DevolucionStatus.PENDIENTE
                // completedAt se establece cuando se completa
            )
            transaction.set(newDevolucionPendienteRef, devolucionPendiente)
            // --- FIN ESCRITURAS ---

            null // La transacción retorna null en éxito
        }.addOnSuccessListener {
            if (_binding != null) showListLoading(false)
            Toast.makeText(context, "Devolución registrada: -${String.format("%.2f", quantityToDevolver)} ${product.unit}", Toast.LENGTH_SHORT).show()
            firestore.collection("products").document(product.id).get().addOnSuccessListener { updatedDoc ->
                if (isAdded && context != null) {
                    updatedDoc.toObject(Product::class.java)?.let { updatedProduct ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            NotificationTriggerHelper.triggerLowStockNotification(updatedProduct)
                        }
                    }
                }
            }
            isDialogOpen = false
        }.addOnFailureListener { e ->
            if (_binding != null) showListLoading(false)
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                e.message ?: "Error de datos durante la devolución."
            } else {
                "Error registrando devolución: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            isDialogOpen = false
        }
    }

    private fun showTraspasoC04ToMatrizDialog(product: Product) {
        val currentContext = context ?: run {
            isDialogOpen = false
            return
        }

        // Reutilizar R.layout.dialog_traspaso_lotes (o el que uses para traspasos)
        val dialogViewInflated = LayoutInflater.from(currentContext).inflate(R.layout.dialog_traspaso_lotes, null)

        val titleProductTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoTitleProduct)
        val directionTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoDirection)
        val lotSelectionLabelTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoLotSelectionLabel)
        val recyclerViewLotes = dialogViewInflated.findViewById<RecyclerView>(R.id.recyclerViewLotesTraspasoDialog)
        val progressBarLotes = dialogViewInflated.findViewById<ProgressBar>(R.id.progressBarLotesTraspasoDialog)
        val textViewNoLotes = dialogViewInflated.findViewById<TextView>(R.id.textViewNoLotesTraspasoDialog)
        val inputLayoutQuantity = dialogViewInflated.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textFieldLayoutCantidadTraspaso)
        val inputQuantityNet = dialogViewInflated.findViewById<EditText>(R.id.editTextCantidadTraspaso)
        val buttonAceptar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogTraspasoAceptar)
        val buttonCancelar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogTraspasoCancelar)

        titleProductTextView.text = "Traspaso: ${product.name}"
        directionTextView.text = "Origen: C-04  --->  Destino: MATRIZ"
        lotSelectionLabelTextView.text = "Selecciona Lote(s) Origen (Congelador 04):"
        inputLayoutQuantity.hint = "Cantidad NETA Total a Regresar a Matriz"
        buttonAceptar.text = "Regresar a Matriz"

        val lotAdapter = LotSelectionAdapter()
        recyclerViewLotes.layoutManager = LinearLayoutManager(currentContext)
        recyclerViewLotes.adapter = lotAdapter

        val builder = AlertDialog.Builder(currentContext)
        builder.setView(dialogViewInflated)

        val alertDialog = builder.create()
        alertDialog.setOnDismissListener { isDialogOpen = false }

        buttonCancelar.setOnClickListener {
            alertDialog.dismiss()
        }

        buttonAceptar.setOnClickListener {
            val quantityString = inputQuantityNet.text.toString()
            val quantityToTraspasar = quantityString.toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            inputQuantityNet.error = null

            if (quantityToTraspasar == null || quantityToTraspasar <= stockEpsilon) {
                inputQuantityNet.error = "Cantidad debe ser > ${String.format(Locale.getDefault(), "%.2f", stockEpsilon)}"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen de C-04", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            if (quantityToTraspasar != null && lotAdapter.currentList.isNotEmpty() && (quantityToTraspasar - selectedLotsTotalNetQuantity > stockEpsilon)) {
                inputQuantityNet.error = "Excede stock de lotes seleccionados en C-04 (${String.format(Locale.getDefault(), "%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToTraspasar != null) {
                if (lotAdapter.currentList.isEmpty() && quantityToTraspasar > stockEpsilon) {
                    Toast.makeText(context, "No hay lotes disponibles en C-04 para traspasar.", Toast.LENGTH_SHORT).show()
                } else if (selectedLotIds.isNotEmpty()){
                    performTraspasoC04ToMatriz(product, quantityToTraspasar, selectedLotIds)
                    alertDialog.dismiss()
                } else if (lotAdapter.currentList.isEmpty() && quantityToTraspasar <= stockEpsilon){
                    alertDialog.dismiss()
                } else {
                    Toast.makeText(context, "Verifica cantidad y selección de lotes.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        progressBarLotes.visibility = View.VISIBLE
        textViewNoLotes.visibility = View.GONE
        recyclerViewLotes.visibility = View.GONE

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", product.id)
            .whereEqualTo("location", Location.CONGELADOR_04) // Cambiado a C04
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING) // Fecha en que llegó a C04

        lotsQuery.get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || _binding == null) {
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    isDialogOpen = false
                    return@addOnSuccessListener
                }
                progressBarLotes.visibility = View.GONE
                if (snapshot != null && !snapshot.isEmpty) {
                    val loadedLots = snapshot.documents.mapNotNull { doc ->
                        try { doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
                        catch (e: Exception) { null }
                    }
                    lotAdapter.submitList(loadedLots)
                    textViewNoLotes.visibility = View.GONE
                    recyclerViewLotes.visibility = View.VISIBLE
                } else {
                    textViewNoLotes.text = "No hay lotes disponibles en C-04 para este producto."
                    textViewNoLotes.visibility = View.VISIBLE
                    recyclerViewLotes.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || _binding == null) {
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    isDialogOpen = false
                    return@addOnFailureListener
                }
                progressBarLotes.visibility = View.GONE
                textViewNoLotes.text = "Error al cargar lotes de C-04."
                textViewNoLotes.visibility = View.VISIBLE
                recyclerViewLotes.visibility = View.GONE
                Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
            }

        showDebouncedDialogWithCustomView(alertDialog)
    }


    private fun showTraspasoC04MDialog(product: Product) {
        val currentContext = context ?: run {
            isDialogOpen = false
            Log.e(TAG, "showTraspasoC04MDialog: Contexto nulo.")
            return
        }

        // Reutilizar R.layout.dialog_traspaso_lotes. Asegúrate de que los IDs sean correctos.
        val dialogViewInflated = LayoutInflater.from(currentContext).inflate(R.layout.dialog_traspaso_lotes, null)

        val titleProductTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoTitleProduct)
        val directionTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoDirection)
        val lotSelectionLabelTextView = dialogViewInflated.findViewById<TextView>(R.id.textViewDialogTraspasoLotSelectionLabel)
        val recyclerViewLotes = dialogViewInflated.findViewById<RecyclerView>(R.id.recyclerViewLotesTraspasoDialog)
        val progressBarLotes = dialogViewInflated.findViewById<ProgressBar>(R.id.progressBarLotesTraspasoDialog)
        val textViewNoLotes = dialogViewInflated.findViewById<TextView>(R.id.textViewNoLotesTraspasoDialog)
        val inputLayoutQuantity = dialogViewInflated.findViewById<TextInputLayout>(R.id.textFieldLayoutCantidadTraspaso)
        val inputQuantityNet = dialogViewInflated.findViewById<EditText>(R.id.editTextCantidadTraspaso)
        val buttonAceptar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogTraspasoAceptar)
        val buttonCancelar = dialogViewInflated.findViewById<Button>(R.id.buttonDialogTraspasoCancelar)

        titleProductTextView.text = "Traspaso: ${product.name}"
        directionTextView.text = "Origen: C-04  --->  Destino: MATRIZ"
        lotSelectionLabelTextView.text = "Selecciona Lote(s) Origen (Congelador 04):"
        inputLayoutQuantity.hint = "Cantidad NETA Total a Regresar a Matriz"
        buttonAceptar.text = "Regresar a Matriz"

        val lotAdapter = LotSelectionAdapter()
        recyclerViewLotes.layoutManager = LinearLayoutManager(currentContext)
        recyclerViewLotes.adapter = lotAdapter

        val builder = AlertDialog.Builder(currentContext)
        builder.setView(dialogViewInflated)

        val alertDialog = builder.create()
        alertDialog.setOnDismissListener {
            isDialogOpen = false
            Log.d(TAG, "Dialogo Traspaso C04->M cerrado.")
        }

        buttonCancelar.setOnClickListener {
            alertDialog.dismiss()
        }

        buttonAceptar.setOnClickListener {
            val quantityString = inputQuantityNet.text.toString()
            val quantityToTraspasar = quantityString.toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds() // Obtener los IDs de los lotes seleccionados
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            inputQuantityNet.error = null

            if (quantityToTraspasar == null || quantityToTraspasar <= stockEpsilon) {
                inputQuantityNet.error = "Cantidad debe ser > ${String.format(Locale.getDefault(), "%.2f", stockEpsilon)}"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen de C-04", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            if (quantityToTraspasar != null && lotAdapter.currentList.isNotEmpty() && (quantityToTraspasar - selectedLotsTotalNetQuantity > stockEpsilon)) {
                inputQuantityNet.error = "Excede stock de lotes seleccionados en C-04 (${String.format(Locale.getDefault(), "%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToTraspasar != null) {
                if (lotAdapter.currentList.isEmpty() && quantityToTraspasar > stockEpsilon) {
                    Toast.makeText(context, "No hay lotes disponibles en C-04 para traspasar.", Toast.LENGTH_SHORT).show()
                } else if (selectedLotIds.isNotEmpty()){
                    // LLAMADA CORRECTA A performTraspasoC04ToMatriz con los tres argumentos
                    performTraspasoC04ToMatriz(product, quantityToTraspasar, selectedLotIds)
                    alertDialog.dismiss()
                } else if (lotAdapter.currentList.isEmpty() && quantityToTraspasar <= stockEpsilon){
                    Log.d(TAG, "Traspaso C04->M: No hay lotes y cantidad es cero/insignificante.")
                    alertDialog.dismiss()
                } else {
                    Toast.makeText(context, "Verifica cantidad y selección de lotes.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        progressBarLotes.visibility = View.VISIBLE
        textViewNoLotes.visibility = View.GONE
        recyclerViewLotes.visibility = View.GONE

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", product.id)
            .whereEqualTo("location", Location.CONGELADOR_04) // Lotes de C04
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING) // Fecha en que llegaron a C04

        lotsQuery.get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || _binding == null) {
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    isDialogOpen = false
                    return@addOnSuccessListener
                }
                progressBarLotes.visibility = View.GONE
                if (snapshot != null && !snapshot.isEmpty) {
                    val loadedLots = snapshot.documents.mapNotNull { doc ->
                        try { doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
                        catch (e: Exception) {
                            Log.e(TAG, "Error convirtiendo lote C04: ${doc.id}", e)
                            null
                        }
                    }
                    lotAdapter.submitList(loadedLots)
                    textViewNoLotes.visibility = View.GONE
                    recyclerViewLotes.visibility = View.VISIBLE
                } else {
                    textViewNoLotes.text = "No hay lotes disponibles en C-04 para este producto."
                    textViewNoLotes.visibility = View.VISIBLE
                    recyclerViewLotes.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || _binding == null) {
                    if(alertDialog.isShowing) alertDialog.dismiss()
                    isDialogOpen = false
                    return@addOnFailureListener
                }
                progressBarLotes.visibility = View.GONE
                textViewNoLotes.text = "Error al cargar lotes de C-04."
                textViewNoLotes.visibility = View.VISIBLE
                recyclerViewLotes.visibility = View.GONE
                Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
            }

        showDebouncedDialogWithCustomView(alertDialog) // Usa tu función para manejar isDialogOpen
    }

        private fun performTraspasoMatrizToC04(
            product: Product,
            quantityToTraspasarTotal: Double,
            selectedLotIdsFromMatriz: List<String>
        ) {
            val user = auth.currentUser ?: run {
                Toast.makeText(context, "Error de autenticación.", Toast.LENGTH_SHORT).show()
                isDialogOpen = false
                return
            }
            val currentUserName = user.displayName ?: user.email ?: "Unknown"
            val traspasoTimestamp = Date()

            if (_binding != null) showListLoading(true)
            isDialogOpen = true

            lifecycleScope.launch {
                try {
                    val productRef = firestore.collection("products").document(product.id)

                    // Leer lotes origen de Matriz fuera de la transacción principal
                    val lotesOrigenMatriz = selectedLotIdsFromMatriz.map { lotId ->
                        async {
                            val lotSnapshot = firestore.collection("inventoryLots").document(lotId).get().await()
                            val stockLot = lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                            if (stockLot == null || stockLot.location != Location.MATRIZ || stockLot.isDepleted) {
                                throw FirebaseFirestoreException("Lote ${lotSnapshot.id} no es válido para traspaso.", FirebaseFirestoreException.Code.ABORTED)
                            }
                            stockLot
                        }
                    }.awaitAll().sortedBy { it.receivedAt ?: Date(0) }


                    val totalDisponibleEnLotesSeleccionados = lotesOrigenMatriz.sumOf { it.currentQuantity }
                    if (quantityToTraspasarTotal > totalDisponibleEnLotesSeleccionados + stockEpsilon) {
                        throw FirebaseFirestoreException("Stock insuficiente en lotes de Matriz seleccionados (${String.format(Locale.getDefault(), "%.2f", totalDisponibleEnLotesSeleccionados)} ${product.unit})", FirebaseFirestoreException.Code.ABORTED)
                    }

                    val sublotesExistentesData = lotesOrigenMatriz.map { loteOrigen ->
                        async {
                            val queryForExistingSublote: com.google.firebase.firestore.Query = firestore.collection("inventoryLots")
                                .whereEqualTo("productId", loteOrigen.productId)
                                .whereEqualTo("location", Location.CONGELADOR_04)
                                .whereEqualTo("isDepleted", false)
                                .whereEqualTo("originalSupplierId", loteOrigen.supplierId)
                                .whereEqualTo("originalSupplierName", loteOrigen.supplierName)
                                .whereEqualTo("originalLotNumber", loteOrigen.lotNumber)
                                .whereEqualTo("originalReceivedAt", loteOrigen.receivedAt)
                                .limit(1)
                            val snapshot = queryForExistingSublote.get().await()
                            loteOrigen.id to if (snapshot.documents.isNotEmpty()) snapshot.documents.first() else null
                        }
                    }.awaitAll().toMap()


                    firestore.runTransaction { transaction ->
                        val currentProduct = transaction.get(productRef).toObject(Product::class.java)
                            ?: throw FirebaseFirestoreException("Producto no encontrado en transacción: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

                        var cantidadRestanteATraspasar = quantityToTraspasarTotal
                        val lotesMatrizActualizar = mutableMapOf<String, Map<String, Any>>()
                        val idsLotesOrigenAfectadosConCantidad = mutableListOf<String>()
                        val idsLotesDestinoC04AfectadosConCantidad = mutableListOf<String>()
                        val newMovementRef = firestore.collection("stockMovements").document()
                        val movementId = newMovementRef.id

                        for (loteOrigen in lotesOrigenMatriz) {
                            if (cantidadRestanteATraspasar <= stockEpsilon) break
                            val cantidadATraspasarDeEsteLote = kotlin.math.min(loteOrigen.currentQuantity, cantidadRestanteATraspasar)

                            if (cantidadATraspasarDeEsteLote > stockEpsilon) {
                                idsLotesOrigenAfectadosConCantidad.add("${loteOrigen.id.takeLast(4)}:${String.format(Locale.getDefault(), "%.2f", cantidadATraspasarDeEsteLote)}")
                                val nuevaCantidadEnLoteOrigen = loteOrigen.currentQuantity - cantidadATraspasarDeEsteLote
                                lotesMatrizActualizar[loteOrigen.id] = mapOf(
                                    "currentQuantity" to nuevaCantidadEnLoteOrigen,
                                    "isDepleted" to (nuevaCantidadEnLoteOrigen <= stockEpsilon)
                                )

                                val subloteExistenteSnapshot = sublotesExistentesData[loteOrigen.id]

                                if (subloteExistenteSnapshot != null && subloteExistenteSnapshot.exists()) {
                                    // Re-leer DENTRO de la transacción para asegurar consistencia
                                    val subloteRef = subloteExistenteSnapshot.reference
                                    val subloteActualTrans = transaction.get(subloteRef)
                                    if (subloteActualTrans.exists()) {
                                        val cantidadActual = subloteActualTrans.getDouble("currentQuantity") ?: 0.0
                                        transaction.update(subloteRef, mapOf(
                                            "currentQuantity" to (cantidadActual + cantidadATraspasarDeEsteLote),
                                            "movementIdIn" to movementId
                                        ))
                                        idsLotesDestinoC04AfectadosConCantidad.add("${subloteRef.id.takeLast(4)}:${String.format(Locale.getDefault(), "%.2f", cantidadATraspasarDeEsteLote)} (Exist.)")
                                    } else { // Fue eliminado entre la lectura externa y la transacción
                                        val newLotIdC04 = firestore.collection("inventoryLots").document().id
                                        val nuevoLoteC04 = StockLot(id=newLotIdC04,productId=loteOrigen.productId,productName=loteOrigen.productName,unit=loteOrigen.unit,location=Location.CONGELADOR_04,supplierId=null,supplierName=null,receivedAt=traspasoTimestamp,movementIdIn=movementId,initialQuantity=cantidadATraspasarDeEsteLote,currentQuantity=cantidadATraspasarDeEsteLote,isDepleted=false,isPackaged=loteOrigen.isPackaged,lotNumber=null,expirationDate=loteOrigen.expirationDate,originalLotId=loteOrigen.id,originalReceivedAt=loteOrigen.receivedAt,originalSupplierId=loteOrigen.supplierId,originalSupplierName=loteOrigen.supplierName,originalLotNumber=loteOrigen.lotNumber)
                                        transaction.set(firestore.collection("inventoryLots").document(newLotIdC04), nuevoLoteC04)
                                        idsLotesDestinoC04AfectadosConCantidad.add("${newLotIdC04.takeLast(4)}:${String.format(Locale.getDefault(), "%.2f", cantidadATraspasarDeEsteLote)} (Nuevo-Conflict)")
                                    }
                                } else {
                                    val newLotIdC04 = firestore.collection("inventoryLots").document().id
                                    val nuevoLoteC04 = StockLot(id=newLotIdC04,productId=loteOrigen.productId,productName=loteOrigen.productName,unit=loteOrigen.unit,location=Location.CONGELADOR_04,supplierId=null,supplierName=null,receivedAt=traspasoTimestamp,movementIdIn=movementId,initialQuantity=cantidadATraspasarDeEsteLote,currentQuantity=cantidadATraspasarDeEsteLote,isDepleted=false,isPackaged=loteOrigen.isPackaged,lotNumber=null,expirationDate=loteOrigen.expirationDate,originalLotId=loteOrigen.id,originalReceivedAt=loteOrigen.receivedAt,originalSupplierId=loteOrigen.supplierId,originalSupplierName=loteOrigen.supplierName,originalLotNumber=loteOrigen.lotNumber)
                                    transaction.set(firestore.collection("inventoryLots").document(newLotIdC04), nuevoLoteC04)
                                    idsLotesDestinoC04AfectadosConCantidad.add("${newLotIdC04.takeLast(4)}:${String.format(Locale.getDefault(), "%.2f", cantidadATraspasarDeEsteLote)} (Nuevo)")
                                }
                                cantidadRestanteATraspasar -= cantidadATraspasarDeEsteLote
                            }
                        }

                        if (kotlin.math.abs(cantidadRestanteATraspasar) > stockEpsilon && quantityToTraspasarTotal > stockEpsilon) {
                            throw FirebaseFirestoreException("Discrepancia al calcular cantidades de traspaso M->C04. Restante: $cantidadRestanteATraspasar", FirebaseFirestoreException.Code.ABORTED)
                        }
                        val nuevoStockMatriz = currentProduct.stockMatriz - quantityToTraspasarTotal
                        val nuevoStockC04 = currentProduct.stockCongelador04 + quantityToTraspasarTotal
                        for ((id, data) in lotesMatrizActualizar) {
                            transaction.update(firestore.collection("inventoryLots").document(id), data)
                        }
                        transaction.update(productRef, mapOf("stockMatriz" to nuevoStockMatriz, "stockCongelador04" to nuevoStockC04, "updatedAt" to FieldValue.serverTimestamp(), "lastUpdatedByName" to currentUserName))
                        val movement = StockMovement(
                            id=movementId,
                         userId=user.uid,
                          userName=currentUserName,
                           productId=product.id,
                            productName=currentProduct.name,
                             type=MovementType.TRASPASO_M_C04, 
                             quantity=quantityToTraspasarTotal,
                         locationFrom=Location.MATRIZ,
                         locationTo=Location.CONGELADOR_04, 
                         reason="Origen(M): ${idsLotesOrigenAfectadosConCantidad.joinToString()}; Destino(C04): ${idsLotesDestinoC04AfectadosConCantidad.joinToString()}",
                         stockAfterMatriz=nuevoStockMatriz, stockAfterCongelador04=nuevoStockC04,
                         stockAfterTotal=currentProduct.totalStock,
                          timestamp=traspasoTimestamp,
                              affectedLotIds = lotesOrigenMatriz.map { it.id }
                              )
                        transaction.set(newMovementRef, movement)
                        null
                    }.addOnSuccessListener {
                        if (_binding != null) showListLoading(false)
                        Toast.makeText(context, "Traspaso Matriz -> C04 realizado: ${String.format(Locale.getDefault(), "%.2f", quantityToTraspasarTotal)} ${product.unit}", Toast.LENGTH_SHORT).show()
                        isDialogOpen = false
                    }.addOnFailureListener { e ->
                        if (_binding != null) showListLoading(false)
                        val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                            e.message ?: "Error de datos durante el traspaso M->C04."
                        } else { "Error registrando traspaso M->C04: ${e.message}" }
                        Log.e(TAG, "Error en transacción de traspaso M->C04: ", e)
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        isDialogOpen = false
                    }
                } catch (e: Exception) {
                    if (_binding != null) showListLoading(false)
                    Log.e(TAG, "Error general en traspaso M->C04: ", e)
                    Toast.makeText(context, "Error inesperado durante traspaso: ${e.message}", Toast.LENGTH_LONG).show()
                    isDialogOpen = false
                }
            }
        }


    companion object {
        private const val TAG = "ProductListFragment"
    }
} // Fin de ProductListFragment