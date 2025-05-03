package com.cesar.bocana.ui.products


import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import com.cesar.bocana.data.model.PendingPackagingTask // El nuevo modelo
import android.os.Bundle
import android.text.InputType
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.cesar.bocana.helpers.NotificationTriggerHelper // Importar el helper
import com.cesar.bocana.data.model.UserRole

class ProductListFragment : Fragment(), ProductActionListener, MenuProvider {


    private var _binding: FragmentProductListBinding? = null
    private val binding get() = _binding!!
    private var currentLocationContext: String = Location.MATRIZ

    private lateinit var productAdapter: ProductAdapter
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var productsListener: ListenerRegistration? = null
    private var currentUserRole: UserRole? = null


    // --- Implementación de MenuProvider ---

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        // No necesitamos añadir items nuevos desde este fragmento.
        // MainActivity ya infla R.menu.main_menu
        Log.d(TAG, "onCreateMenu (ProductListFragment)")
        // Si quisiéramos un menú específico SÓLO para esta pantalla, haríamos:
        // menuInflater.inflate(R.menu.product_list_menu, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        Log.d(TAG, "onPrepareMenu (ProductListFragment), Rol: $currentUserRole")
        val isAdmin = (currentUserRole == UserRole.ADMIN)
        menu.findItem(R.id.action_ajustes)?.isVisible = isAdmin
        menu.findItem(R.id.action_devoluciones)?.isVisible = isAdmin
        Log.d(TAG, "Visibilidad menú ajustada: Ajustes=${isAdmin}, Devoluciones=${isAdmin}, Usuarios=${isAdmin}")
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // Este fragmento NO maneja directamente los clics en los items del menú de opciones
        // principal (como Ajustes, Devoluciones, Logout). Eso lo hace MainActivity.
        // Devolvemos 'false' para indicar que NO hemos manejado el clic aquí.
        Log.d(TAG, "onMenuItemSelected (ProductListFragment) para ${menuItem.title}")
        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductListBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        auth = Firebase.auth
        fetchCurrentUserRole { roleIsFetched ->
            if (_binding != null && roleIsFetched) {
                setupRecyclerView()
                setupFab()
                setupTabLayoutListener()
                observeProducts()
            } else if (_binding != null) {
                Log.e(TAG, "No se pudo obtener el rol ADMIN en onCreateView.")
                // Considerar mostrar un mensaje o impedir la carga de datos
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        setupTabLayoutListener()
    }

    override fun onTraspasoC04MClicked(product: Product) {
        if (!canUserModify()) return
        Log.d(TAG, "Action: Traspaso C04->M ${product.name}")
        showTraspasoC04MDialog(product)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: Deteniendo listener.")
        productsListener?.remove(); productsListener = null
        _binding = null
    }

    // Dentro de ProductListFragment.kt, junto a las otras implementaciones de ProductActionListener

    override fun onEditC04Clicked(product: Product) {
        if (!canUserModify()) return
        Log.d(TAG, "Action: EditC04 ${product.name}")
        showEditC04Dialog(product)
    }


    // Función NUEVA para configurar el listener de las pestañas
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

    private fun showTraspasoC04MDialog(product: Product) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Traspaso ===> Matriz: ${product.name}")
        builder.setMessage("Stock actual en 04: ${product.stockCongelador04} ${product.unit}")

        val container = FrameLayout(requireContext())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = resources.getDimensionPixelSize(R.dimen.dialog_margin)
        params.leftMargin = margin; params.rightMargin = margin

        val inputQuantity = EditText(requireContext())
        inputQuantity.inputType = InputType.TYPE_CLASS_NUMBER
        inputQuantity.hint = "Cantidad a regresar a Matriz (${product.unit})"
        inputQuantity.layoutParams = params
        container.addView(inputQuantity)

        builder.setView(container)

        builder.setPositiveButton("Regresar a Matriz") { _, _ ->
            val quantityString = inputQuantity.text.toString()
            try {
                val quantity = quantityString.toLongOrNull()

                if (quantity == null || quantity <= 0) {
                    Toast.makeText(context, "Cantidad inválida (> 0)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Validar stock suficiente en C04
                if (quantity > product.stockCongelador04) {
                    Toast.makeText(context, "Error: Stock insuficiente en C04 (${product.stockCongelador04})", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                // Confirmación cantidad grande (respecto a C04)
                val limiteGrande = (product.stockCongelador04 * 0.40).toLong()
                if (quantity > limiteGrande && product.stockCongelador04 > 0) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Confirmar Traspaso")
                        .setMessage("Cantidad grande (${quantity} ${product.unit}). ¿Regresar a Matriz?")
                        .setPositiveButton("Sí") { _, _ -> performTraspasoC04ToMatriz(product, quantity) }
                        .setNegativeButton("No", null)
                        .show()
                } else {
                    performTraspasoC04ToMatriz(product, quantity)
                }

            } catch (e: NumberFormatException) {
                Toast.makeText(context, "Número inválido.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }
    // Dentro de ProductListFragment.kt

    private fun performTraspasoC04ToMatriz(product: Product, quantity: Long) {
        if (product.id.isEmpty()) { Log.e(TAG,"Error ID"); Toast.makeText(context, "Error ID", Toast.LENGTH_LONG).show(); return }
        val currentUser = auth.currentUser; if (currentUser == null) { Toast.makeText(context,"Error user", Toast.LENGTH_SHORT).show(); return }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        val productRef = firestore.collection("products").document(product.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        var productAfterUpdate: Product? = null // Para pasar al helper

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(productRef)
            val currentProduct = snapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado.", FirebaseFirestoreException.Code.ABORTED)

            val newStockCongelador04 = currentProduct.stockCongelador04 - quantity
            val newStockMatriz = currentProduct.stockMatriz + quantity
            val newTotalStock = currentProduct.totalStock // Total no cambia

            if (newStockCongelador04 < 0) {
                throw FirebaseFirestoreException("Stock insuficiente en C04 (${currentProduct.stockCongelador04})", FirebaseFirestoreException.Code.ABORTED)
            }

            val movement = StockMovement(
                userId = currentUser.uid, userName = currentUserName,
                productId = product.id, productName = currentProduct.name,
                type = MovementType.TRASPASO_C04_M,
                quantity = quantity,
                locationFrom = Location.CONGELADOR_04,
                locationTo = Location.MATRIZ,
                reason = null,
                stockAfterMatriz = newStockMatriz,
                stockAfterCongelador04 = newStockCongelador04,
                stockAfterTotal = newTotalStock
            )

            transaction.update(productRef, mapOf(
                "stockCongelador04" to newStockCongelador04,
                "stockMatriz" to newStockMatriz,
                // totalStock no cambia
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ))
            transaction.set(newMovementRef, movement)

            productAfterUpdate = currentProduct.copy(stockMatriz = newStockMatriz, stockCongelador04 = newStockCongelador04) // Guardar estado
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Traspaso a Matriz registrado: ${quantity} ${product.unit}", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                e.message ?: "Error datos."
            } else { "Error: ${e.message}" }
            Toast.makeText(context, msg ?: "Error desconocido", Toast.LENGTH_LONG).show()
        }
    }


    private fun showEditC04Dialog(product: Product) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Ajustar Stock 04: ${product.name}")
        builder.setMessage("Stock actual en 04: ${product.stockCongelador04} ${product.unit}\n.")

        val container = FrameLayout(requireContext())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = resources.getDimensionPixelSize(R.dimen.dialog_margin)
        params.leftMargin = margin; params.rightMargin = margin

        val inputNewQuantity = EditText(requireContext())
        inputNewQuantity.inputType = InputType.TYPE_CLASS_NUMBER // Asumimos entero
        inputNewQuantity.hint = "Nueva cantidad en C04"
        inputNewQuantity.layoutParams = params
        container.addView(inputNewQuantity)

        builder.setView(container)

        builder.setPositiveButton("Ajustar Stock") { _, _ ->
            val quantityString = inputNewQuantity.text.toString()
            try {
                val newQuantity = quantityString.toLongOrNull()

                // --- Validaciones ---
                if (newQuantity == null || newQuantity < 0) {
                    Toast.makeText(context, "Cantidad inválida. Debe ser 0 o mayor.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // No permitir ajustar a MÁS de lo que hay
                if (newQuantity > product.stockCongelador04) {
                    Toast.makeText(context, "Error: La nueva cantidad (${newQuantity}) no puede ser mayor al stock actual (${product.stockCongelador04}).", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                // Calcular la diferencia (lo que salió)
                val quantityDifference = product.stockCongelador04 - newQuantity
                Log.d(TAG, "Ajuste C04: Stock actual=${product.stockCongelador04}, Nuevo=${newQuantity}, Diferencia=${quantityDifference}")

                // Si la diferencia es 0, no hacer nada
                if (quantityDifference <= 0) {
                    Toast.makeText(context, "No se realizó ningún ajuste (cantidad igual o mayor).", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // --- Confirmación si la diferencia es grande (40% del stock C04) ---
                val limiteGrande = (product.stockCongelador04 * 0.40).toLong()
                if (quantityDifference > limiteGrande && product.stockCongelador04 > 0) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Confirmar Ajuste Grande")
                        .setMessage("Esto registrará una salida de ${quantityDifference} ${product.unit} de C04 (ajuste a ${newQuantity}). ¿Continuar?")
                        .setPositiveButton("Sí") { _, _ -> performEditC04(product, newQuantity, quantityDifference) }
                        .setNegativeButton("No", null)
                        .show()
                } else {
                    performEditC04(product, newQuantity, quantityDifference)
                }

            } catch (e: NumberFormatException) {
                Toast.makeText(context, "Número inválido.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun performEditC04(product: Product, newQuantityC04: Long, quantityDifference: Long) {
        if (product.id.isEmpty()) { Log.e(TAG,"Error ID"); Toast.makeText(context, "Error ID", Toast.LENGTH_LONG).show(); return }
        val currentUser = auth.currentUser; if (currentUser == null) { Toast.makeText(context,"Error user", Toast.LENGTH_SHORT).show(); return }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        val productRef = firestore.collection("products").document(product.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        var productAfterUpdate: Product? = null // Para pasar al helper

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(productRef)
            val currentProduct = snapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado.", FirebaseFirestoreException.Code.ABORTED)

            if (newQuantityC04 < 0 || newQuantityC04 > currentProduct.stockCongelador04) {
                throw FirebaseFirestoreException("Ajuste inválido. Stock C04: ${currentProduct.stockCongelador04}, ajuste a: ${newQuantityC04}", FirebaseFirestoreException.Code.ABORTED)
            }
            val actualDifference = currentProduct.stockCongelador04 - newQuantityC04
            if (actualDifference <= 0) {
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
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ))
            transaction.set(newMovementRef, movement)

            productAfterUpdate = currentProduct.copy(stockCongelador04 = newQuantityC04, totalStock = newTotalStock) // Guardar estado
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Stock C04 ajustado a ${newQuantityC04} (-${quantityDifference})", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && (e.code == FirebaseFirestoreException.Code.ABORTED || e.code == FirebaseFirestoreException.Code.CANCELLED) ) {
                e.message
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
            if (currentUserRole == UserRole.ADMIN) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, AddEditProductFragment.newInstance(null))
                    .addToBackStack("AddProductFragment").commit()
            } else {
                Toast.makeText(context, "Permiso denegado.", Toast.LENGTH_SHORT).show()
            }
        }
        updateFabVisibility()
    }

    private fun updateFabVisibility() {
        val canAdd = currentUserRole == UserRole.ADMIN
        if (_binding != null) {
            binding.fabAddProduct.visibility = if (canAdd) View.VISIBLE else View.GONE
        }
    }

    // Reemplaza esta función en ProductListFragment.kt
    private fun observeProducts() {
        if (productsListener != null) { Log.w(TAG, "Listener de productos ya activo."); return }
        Log.d(TAG, "Iniciando escucha de productos (orderBy name - FILTRADO EN APP)...")
        showLoading(true); binding.textViewEmptyList.visibility = View.GONE

        val query = firestore.collection("products")
            // .whereEqualTo("isActive", true) // SIGUE COMENTADO!
            .orderBy("name", Query.Direction.ASCENDING)

        productsListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null) { Log.w(TAG, "observeProducts: Snapshot recibido pero binding es null"); return@addSnapshotListener }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando productos", error)
                binding.textViewEmptyList.text = "Error al cargar productos."
                binding.textViewEmptyList.visibility = View.VISIBLE
                binding.recyclerViewProducts.visibility = View.GONE
                if (error is FirebaseFirestoreException) {
                    Toast.makeText(context, "Error Firestore: ${error.message}", Toast.LENGTH_LONG).show()
                }
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val allProducts = snapshots.toObjects(Product::class.java)
                Log.d(TAG, "Snapshot recibido. Total productos: ${allProducts.size}") // Log total

                // --- FILTRADO EN KOTLIN ---
                val activeProducts = allProducts.filter { product -> product.isActive }
                Log.d(TAG, "Productos activos (filtrados en app): ${activeProducts.size}") // Log filtrados
                // -------------------------

                if (::productAdapter.isInitialized) {
                    productAdapter.submitList(activeProducts) // Enviar filtrados
                }
                binding.textViewEmptyList.text = "No hay productos activos."
                binding.textViewEmptyList.visibility = if (activeProducts.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewProducts.visibility = if (activeProducts.isEmpty()) View.GONE else View.VISIBLE

            } else {
                Log.w(TAG, "Snapshot recibido pero es NULL")
                binding.textViewEmptyList.text = "Error: No se recibieron datos."
                binding.textViewEmptyList.visibility = View.VISIBLE
                binding.recyclerViewProducts.visibility = View.GONE
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) { binding.progressBarList.visibility = if (isLoading) View.VISIBLE else View.GONE }
    }

    // --- Implementación de ProductActionListener ---
    override fun onAddCompraClicked(product: Product) {
        if (!canUserModify()) return
        Log.d(TAG, "Action: Compra ${product.name}")
        showAddCompraDialog(product)
    }

    override fun onSalidaClicked(product: Product) {
        if (!canUserModify()) return
        Log.d(TAG, "Action: Salida ${product.name}")
        showSalidaDialog(product)
    }

    override fun onTraspasoC04Clicked(product: Product) {
        if (!canUserModify()) return
        Log.d(TAG, "Action: Traspaso M->C04 ${product.name}")
        showTraspasoDialog(product)
    }

    override fun onItemClicked(product: Product) {
        if (currentUserRole == UserRole.ADMIN) {
            val editFragment = AddEditProductFragment.newInstance(product.id)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, editFragment)
                .addToBackStack("EditProductFragment").commit()
        } else {
            Toast.makeText(context, "Solo ADMIN puede editar.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun canUserModify(): Boolean {
        val allowed = currentUserRole == UserRole.ADMIN
        if (!allowed) {
            Toast.makeText(context, "Permiso denegado.", Toast.LENGTH_SHORT).show()
        }
        return allowed
    }

    private fun showAddCompraDialog(product: Product) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Registrar Compra: ${product.name}")

        // Layout vertical para poner Cantidad y Tipo Recepción
        val containerLayout = android.widget.LinearLayout(requireContext())
        containerLayout.orientation = android.widget.LinearLayout.VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_margin)
        containerLayout.setPadding(padding, padding / 2, padding, padding / 2)

        // 1. Campo Cantidad
        val inputQuantity = EditText(requireContext())
        inputQuantity.inputType = InputType.TYPE_CLASS_NUMBER
        inputQuantity.hint = "Cantidad comprada (${product.unit})"
        containerLayout.addView(inputQuantity)

        // 2. Selector Tipo Recepción (A Granel / Empacado) - OBLIGATORIO
        val typeLabel = android.widget.TextView(requireContext())
        typeLabel.text = "Tipo de Recepción: *" // Asterisco indica obligatorio
        typeLabel.setPadding(0, padding, 0, padding / 4) // Añadir espacio arriba
        containerLayout.addView(typeLabel)

        val radioGroupType = android.widget.RadioGroup(requireContext())
        radioGroupType.orientation = android.widget.LinearLayout.HORIZONTAL
        radioGroupType.id = View.generateViewId() // ID necesario para el RadioGroup

        // RadioButton Empacado
        val radioButtonEmpacado = android.widget.RadioButton(requireContext())
        radioButtonEmpacado.id = View.generateViewId() // Usar IDs generados
        radioButtonEmpacado.text = "Empacado"
        // No preseleccionar para forzar elección radioButtonEmpacado.isChecked = true
        radioGroupType.addView(radioButtonEmpacado)

        // RadioButton A Granel
        val radioButtonAGranel = android.widget.RadioButton(requireContext())
        radioButtonAGranel.id = View.generateViewId() // Usar IDs generados
        radioButtonAGranel.text = "A Granel"
        val paramsRadio = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        paramsRadio.marginStart = padding
        radioButtonAGranel.layoutParams = paramsRadio
        radioGroupType.addView(radioButtonAGranel)

        containerLayout.addView(radioGroupType)
        // --- Fin Selector ---

        builder.setView(containerLayout) // Añadir layout al diálogo

        // Botones del diálogo
        builder.setPositiveButton("Aceptar") { _, _ ->
            val quantityString = inputQuantity.text.toString()
            val quantity = quantityString.toLongOrNull()
            // Determinar si se seleccionó "A Granel"
            val selectedRadioId = radioGroupType.checkedRadioButtonId
            var isBulk: Boolean? = null
            if (selectedRadioId == radioButtonAGranel.id) { isBulk = true }
            else if (selectedRadioId == radioButtonEmpacado.id) { isBulk = false }

            Log.d(TAG, "Compra - Cantidad: $quantity, A Granel: $isBulk")

            // Validación de cantidad y selección de tipo
            var validationError = false
            if (quantity == null || quantity <= 0) {
                Toast.makeText(context, "Cantidad inválida (> 0)", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            if (isBulk == null) { // Validar que se seleccionó una opción
                Toast.makeText(context, "Selecciona el Tipo de Recepción", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            if (validationError) return@setPositiveButton

            // Confirmación Cantidad Grande (usamos !! porque ya validamos)
            val limit = if(product.stockMatriz <= 0) quantity!! + 1 else (product.stockMatriz * 0.40).toLong()
            if (quantity!! > limit) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirmar Compra")
                    .setMessage("Cantidad grande (${quantity} ${product.unit}). ¿Continuar?")
                    .setPositiveButton("Sí") { _, _ -> performCompra(product, quantity, isBulk!!) } // Pasar isBulk!!
                    .setNegativeButton("No", null)
                    .show()
            } else {
                performCompra(product, quantity, isBulk!!) // Pasar isBulk!!
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }


    // Dentro de ProductListFragment.kt

    // Firma modificada para aceptar isBulk
    private fun performCompra(product: Product, quantity: Long, isBulk: Boolean) {
        if (product.id.isEmpty()) { Log.e(TAG,"Error ID"); Toast.makeText(context, "Error ID", Toast.LENGTH_LONG).show(); return }
        val currentUser = auth.currentUser; if (currentUser == null) { Toast.makeText(context,"Error user", Toast.LENGTH_SHORT).show(); return }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        val productRef = firestore.collection("products").document(product.id)
        val newMovementRef = firestore.collection("stockMovements").document()
        val newPackagingTaskRef = if (isBulk) firestore.collection("pendingPackaging").document() else null

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(productRef)
            val currentProduct = snapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

            val newStockMatriz = currentProduct.stockMatriz + quantity
            val newTotalStock = newStockMatriz + currentProduct.stockCongelador04

            val movement = StockMovement(
                userId = currentUser.uid, userName = currentUserName,
                productId = product.id, productName = currentProduct.name,
                type = MovementType.COMPRA, quantity = quantity,
                locationFrom = Location.PROVEEDOR, locationTo = Location.MATRIZ,
                stockAfterMatriz = newStockMatriz,
                stockAfterCongelador04 = currentProduct.stockCongelador04,
                stockAfterTotal = newTotalStock
            )
            val movementId = newMovementRef.id

            transaction.update(productRef, mapOf(
                "stockMatriz" to newStockMatriz,
                "totalStock" to newTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ))
            transaction.set(newMovementRef, movement)

            if (isBulk && newPackagingTaskRef != null) {
                val packagingTask = PendingPackagingTask(
                    productId = product.id,
                    productName = currentProduct.name,
                    quantityReceived = quantity,
                    unit = currentProduct.unit,
                    purchaseMovementId = movementId
                )
                transaction.set(newPackagingTaskRef, packagingTask)
            }
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Compra registrada", Toast.LENGTH_SHORT).show()
            // Considerar llamar a NotificationTriggerHelper si es necesario
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) e.message else "Error: ${e.message}"; Toast.makeText(context, msg ?: "Error", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSalidaDialog(product: Product) {
val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Registrar Salida: ${product.name}")
        builder.setMessage("Stock actual en Matriz: ${product.stockMatriz} ${product.unit}")
        val containerLayout = android.widget.LinearLayout(requireContext())
        containerLayout.orientation = android.widget.LinearLayout.VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_margin)
        containerLayout.setPadding(padding, padding / 2, padding, padding / 2)
        val inputQuantity = EditText(requireContext())
        inputQuantity.inputType = InputType.TYPE_CLASS_NUMBER
        inputQuantity.hint = "Cantidad (${product.unit})"
        containerLayout.addView(inputQuantity)
        val inputProvider = EditText(requireContext())
        inputProvider.hint = "Proveedor (Obligatorio para Devolución)"
        containerLayout.addView(inputProvider)
        val inputReason = EditText(requireContext())
        inputReason.hint = "Motivo (Obligatorio para Devolución)"
        containerLayout.addView(inputReason)
        builder.setView(containerLayout)
        builder.setPositiveButton("Consumo") { _, _ ->
            try {
                val quantity = inputQuantity.text.toString().toLongOrNull()
                if (quantity == null || quantity <= 0) { Toast.makeText(context, "Cantidad inválida", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (quantity > product.stockMatriz) { Toast.makeText(context,"Stock insuficiente",Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val limiteGrande = (product.stockMatriz * 0.40).toLong(); if (quantity > limiteGrande && product.stockMatriz > 0) {
                   AlertDialog.Builder(requireContext()).setTitle("Confirmar Consumo").setMessage("Cantidad grande (${quantity} ${product.unit}). ¿Registrar?").setPositiveButton("Sí") { _, _ -> performSalidaConsumo(product, quantity) }.setNegativeButton("No", null).show() } else { performSalidaConsumo(product, quantity) }
            } catch (e: Exception) { Log.e(TAG, "Error procesando consumo", e); Toast.makeText(context, "Error procesando cantidad.", Toast.LENGTH_SHORT).show() }
        }
        builder.setNeutralButton("Devolución") { _, _ ->
            try {
                val quantityString = inputQuantity.text.toString()
                val quantity = quantityString.toLongOrNull()
                val provider = inputProvider.text.toString().trim() // Lee siempre
                val reason = inputReason.text.toString().trim()   // Lee siempre
                var hasError = false
                if (quantity == null || quantity <= 0) {
                    inputQuantity.error = "Cantidad > 0"; hasError = true
                } else {
                    inputQuantity.error = null
                    if (quantity > product.stockMatriz) {
                        inputQuantity.error = "Stock insuficiente (${product.stockMatriz})" ; hasError = true
                    } else {
                        inputQuantity.error = null
                    }
                }
                if (provider.isEmpty()) {
                    inputProvider.error = "Obligatorio"; hasError = true
                } else {
                    inputProvider.error = null
                }
                if (reason.isEmpty()) {
                    inputReason.error = "Obligatorio"; hasError = true
                } else {
                    inputReason.error = null
                }
                if (hasError) {
                    Toast.makeText(context, "Revisa los campos marcados.", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton // No continuar si hay errores
                }
                val limiteGrande = (product.stockMatriz * 0.40).toLong(); if (quantity!! > limiteGrande && product.stockMatriz > 0) { // Usar quantity!! o quantity aquí es seguro
                    AlertDialog.Builder(requireContext()).setTitle("Confirmar Devolución").setMessage("Cantidad grande (${quantity} ${product.unit}). ¿Registrar?").setPositiveButton("Sí") { _, _ -> performSalidaDevolucion(product, quantity, provider, reason) }.setNegativeButton("No", null).show()
                } else {
                    performSalidaDevolucion(product, quantity, provider, reason)
                }
            } catch (e: Exception) { // Captura genérica
                Log.e(TAG, "Error procesando devolución", e)
                Toast.makeText(context, "Error inesperado al procesar devolución.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    } // Fin showSalidaDialog

    private fun performSalidaConsumo(product: Product, quantity: Long) {
        if (product.id.isEmpty()) return;
        val user = auth.currentUser ?: return
        val currentUserName = user.displayName ?: user.email ?: "Unknown"
        val pRef = firestore.collection("products").document(product.id);
        val mRef = firestore.collection("stockMovements").document()

        var productAfterUpdate: Product? = null // Para pasar al helper

        firestore.runTransaction { t ->
            val snap = t.get(pRef)
            val curP = snap.toObject(Product::class.java) ?: throw FirebaseFirestoreException("NF.", FirebaseFirestoreException.Code.ABORTED)
            val nSM = curP.stockMatriz - quantity;
            val nTS = curP.totalStock - quantity
            if (nSM < 0) throw FirebaseFirestoreException("Insuf ${curP.stockMatriz}", FirebaseFirestoreException.Code.ABORTED)

            val mov = StockMovement(userId = user.uid, userName = currentUserName, productId = product.id, productName = curP.name, type = MovementType.SALIDA_CONSUMO, quantity = quantity, locationFrom = Location.MATRIZ, locationTo = Location.EXTERNO, stockAfterMatriz = nSM, stockAfterCongelador04 = curP.stockCongelador04, stockAfterTotal = nTS)

            t.update(pRef, mapOf(
                "stockMatriz" to nSM,
                "totalStock" to nTS,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ));
            t.set(mRef, mov);

            productAfterUpdate = curP.copy(stockMatriz = nSM, totalStock = nTS) // Guardar estado actualizado
            null // Devolver null para éxito
        }.addOnSuccessListener {
            Toast.makeText(context, "Salida: -${quantity}", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) e.message else "Error al registrar salida: ${e.message}"
            Toast.makeText(context, msg ?: "Error desconocido", Toast.LENGTH_LONG).show()
        }
    }

    private fun performSalidaDevolucion(product: Product, quantity: Long, provider: String, reason: String) {
        if (product.id.isEmpty()) return;
        val user = auth.currentUser ?: return
        val currentUserName = user.displayName ?: user.email ?: "Unknown"
        val pRef = firestore.collection("products").document(product.id);
        val mRef = firestore.collection("stockMovements").document();
        val dRef = firestore.collection("pendingDevoluciones").document()

        var productAfterUpdate: Product? = null // Para pasar al helper

        firestore.runTransaction { t ->
            val snap = t.get(pRef)
            val curP = snap.toObject(Product::class.java) ?: throw FirebaseFirestoreException("NF.", FirebaseFirestoreException.Code.ABORTED)
            val nSM = curP.stockMatriz - quantity;
            val nTS = curP.totalStock - quantity
            if (nSM < 0) throw FirebaseFirestoreException("Insuf ${curP.stockMatriz}", FirebaseFirestoreException.Code.ABORTED)

            val mov = StockMovement(userId = user.uid, userName = currentUserName, productId = product.id, productName = curP.name, type = MovementType.SALIDA_DEVOLUCION, quantity = quantity, locationFrom = Location.MATRIZ, locationTo = Location.PROVEEDOR, reason = reason, stockAfterMatriz = nSM, stockAfterCongelador04 = curP.stockCongelador04, stockAfterTotal = nTS)
            val dev = DevolucionPendiente(productId = product.id, productName = curP.name, quantity = quantity, provider = provider, reason = reason, userId = user.uid, unit = curP.unit) // Pasar unit

            t.update(pRef, mapOf(
                "stockMatriz" to nSM,
                "totalStock" to nTS,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ));
            t.set(mRef, mov);
            t.set(dRef, dev);

            productAfterUpdate = curP.copy(stockMatriz = nSM, totalStock = nTS) // Guardar estado actualizado
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Devolución registrada", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) e.message else "Error al registrar devolución: ${e.message}"
            Toast.makeText(context, msg ?: "Error desconocido", Toast.LENGTH_LONG).show()
        }
    }
    private fun showTraspasoDialog(product: Product) {
        val builder = AlertDialog.Builder(requireContext()); builder.setTitle("Traspaso ==> 04: ${product.name}"); builder.setMessage("Stock Matriz: ${product.stockMatriz} ${product.unit}")
        val container = FrameLayout(requireContext()); val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); val margin = resources.getDimensionPixelSize(R.dimen.dialog_margin); params.leftMargin = margin; params.rightMargin = margin; val inputQuantity = EditText(requireContext()); inputQuantity.inputType = InputType.TYPE_CLASS_NUMBER; inputQuantity.hint = "Cantidad (${product.unit})"; inputQuantity.layoutParams = params; container.addView(inputQuantity); builder.setView(container)
        builder.setPositiveButton("Traspasar") { _, _ -> try { val q = inputQuantity.text.toString().toLongOrNull(); if (q != null && q > 0) { if (q > product.stockMatriz) { Toast.makeText(context, "Stock insuficiente", Toast.LENGTH_LONG).show(); return@setPositiveButton }; val limit = (product.stockMatriz * 0.40).toLong(); if (q > limit && product.stockMatriz > 0) { AlertDialog.Builder(requireContext()).setTitle("Confirmar").setMessage("Cantidad grande (${q} ${product.unit}). ¿Traspasar?").setPositiveButton("Sí") { _, _ -> performTraspasoMatrizToC04(product, q) }.setNegativeButton("No", null).show() } else { performTraspasoMatrizToC04(product, q) } } else { Toast.makeText(context, "Cantidad inválida", Toast.LENGTH_SHORT).show() } } catch (e: NumberFormatException) { Toast.makeText(context, "Número inválido", Toast.LENGTH_SHORT).show() } }
        builder.setNegativeButton("Cancelar", null); builder.show()
    }

    private fun performTraspasoMatrizToC04(product: Product, quantity: Long) {
        if (product.id.isEmpty()) return;
        val user = auth.currentUser ?: return
        val currentUserName = user.displayName ?: user.email ?: "Unknown"
        val pRef = firestore.collection("products").document(product.id);
        val mRef = firestore.collection("stockMovements").document()

        var productAfterUpdate: Product? = null // Para pasar al helper

        firestore.runTransaction { t ->
            val snap = t.get(pRef)
            val curP = snap.toObject(Product::class.java) ?: throw FirebaseFirestoreException("NF.", FirebaseFirestoreException.Code.ABORTED)
            val nSM = curP.stockMatriz - quantity;
            val nSC04 = curP.stockCongelador04 + quantity;
            val nTS = curP.totalStock // Total no cambia
            if (nSM < 0) throw FirebaseFirestoreException("Insuf ${curP.stockMatriz}", FirebaseFirestoreException.Code.ABORTED)

            val mov = StockMovement(userId = user.uid, userName = currentUserName, productId = product.id, productName = curP.name, type = MovementType.TRASPASO_M_C04, quantity = quantity, locationFrom = Location.MATRIZ, locationTo = Location.CONGELADOR_04, stockAfterMatriz = nSM, stockAfterCongelador04 = nSC04, stockAfterTotal = nTS)

            t.update(pRef, mapOf(
                "stockMatriz" to nSM,
                "stockCongelador04" to nSC04,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName // <-- AÑADIDO
            ));
            t.set(mRef, mov);

            productAfterUpdate = curP.copy(stockMatriz = nSM, stockCongelador04 = nSC04, totalStock = nTS) // Guardar estado
            null
        }.addOnSuccessListener {
            Toast.makeText(context, "Traspaso registrado", Toast.LENGTH_SHORT).show()
            productAfterUpdate?.let { updatedProd ->
                viewLifecycleOwner.lifecycleScope.launch {
                    NotificationTriggerHelper.triggerLowStockNotification(updatedProd)
                }
            }
        }.addOnFailureListener { e ->
            val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) e.message else "Error al registrar traspaso: ${e.message}"
            Toast.makeText(context, msg ?: "Error desconocido", Toast.LENGTH_LONG).show()
        }
    }
    companion object {
        private const val TAG = "ProductListFragment"
    }
} // Fin de ProductListFragment