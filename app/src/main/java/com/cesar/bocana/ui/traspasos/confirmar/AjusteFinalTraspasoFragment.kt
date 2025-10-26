package com.cesar.bocana.ui.traspasos.confirmar

import android.os.Bundle
import android.util.Log // Asegúrate de importar Log
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.FragmentAjusteFinalTraspasoBinding
import com.cesar.bocana.ui.dialogs.LoadingDialogFragment
import com.cesar.bocana.ui.traspasos.plan.DesgloseManualResult
import com.cesar.bocana.ui.traspasos.plan.SeleccionarLotesDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AjusteFinalTraspasoFragment : Fragment(), MenuProvider, AjusteFinalAdapterListener {

    private var _binding: FragmentAjusteFinalTraspasoBinding? = null
    private val binding get() = _binding!!

    private var planId: String? = null
    private val viewModel: AjusteFinalTraspasoViewModel by viewModels()
    private lateinit var adapter: AjusteFinalTraspasoAdapter
    private var loadingDialog: LoadingDialogFragment? = null

    companion object {
        private const val ARG_PLAN_ID = "plan_id"
        fun newInstance(planId: String): AjusteFinalTraspasoFragment {
            return AjusteFinalTraspasoFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLAN_ID, planId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            planId = it.getString(ARG_PLAN_ID)
        }

        // Listener para recibir los lotes seleccionados desde el diálogo
        setFragmentResultListener(SeleccionarLotesDialogFragment.REQUEST_KEY) { _, bundle ->
            val productId = bundle.getString(SeleccionarLotesDialogFragment.PRODUCT_ID_KEY) ?: return@setFragmentResultListener
            Log.d("AjusteFinalFrag", "Resultado recibido del diálogo para producto: $productId")

            // Intentar obtener ambos tipos de resultado
            val desgloseManualList = bundle.getParcelableArrayList<DesgloseManualResult>(SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY)
            val lotesSeleccionadosIds = bundle.getStringArrayList(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY) // Cambiado a IDs

            when {
                // Prioridad al desglose manual si existe y no está vacío
                desgloseManualList != null && desgloseManualList.isNotEmpty() -> {
                    Log.d("AjusteFinalFrag", "Procesando resultado como Desglose Manual: ${desgloseManualList.size} items")
                    viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)
                }
                // Si no hay desglose, verificar si hay IDs de checkboxes
                lotesSeleccionadosIds != null -> {
                    Log.d("AjusteFinalFrag", "Procesando resultado como Checkbox: ${lotesSeleccionadosIds.size} IDs")
                    // Pasar la lista de IDs al ViewModel para que cargue los StockLot completos si es necesario
                    viewModel.actualizarLotesManualmentePorIds(productId, lotesSeleccionadosIds)
                }
                else -> {
                    // Caso donde no se devuelve nada o el bundle está mal formado
                    Log.w("AjusteFinalFrag", "Resultado del diálogo no contenía ni desglose ni IDs para producto: $productId")
                    // Opcionalmente, podrías querer resetear la selección o mostrar un mensaje
                    // viewModel.resetearSeleccionManual(productId)
                }
            }
        }

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjusteFinalTraspasoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        setupToolbar()
        setupRecyclerView()
        observeViewModel()

        planId?.let { viewModel.loadPlanDetails(it) }

        binding.fabExecuteTraspaso.setOnClickListener {
            viewModel.ejecutarTraspaso()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Verificar si hay cambios sin guardar antes de simplemente hacer popBackStack
                // (Lógica de confirmación omitida por brevedad, se podría añadir aquí)
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Ajuste y Confirmación"
            setDisplayHomeAsUpEnabled(true) // Mostrar flecha de regreso
            // setDisplayShowHomeEnabled(true) // Opcional, depende del estilo deseado
        }
    }

    // --- Manejo Menú Toolbar ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        // Limpiar menú existente si es necesario (depende de cómo manejes los menús en MainActivity)
        // menu.clear()
        // No inflar menú específico aquí si solo usamos la flecha de regreso
    }

    override fun onPrepareMenu(menu: Menu) {
        // Ocultar ítems irrelevantes si fuera necesario
    }


    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // Manejar clic en la flecha de regreso (home)
        return when (menuItem.itemId) {
            android.R.id.home -> {
                // Simular botón de regreso del sistema
                activity?.onBackPressedDispatcher?.onBackPressed()
                true
            }
            else -> false // Dejar que otros fragmentos o la actividad manejen otros ítems
        }
    }
    // --- Fin Manejo Menú Toolbar ---


    private fun setupRecyclerView() {
        adapter = AjusteFinalTraspasoAdapter(this)
        binding.recyclerViewAjusteFinal.apply {
            this.adapter = this@AjusteFinalTraspasoFragment.adapter // Usar el adapter de la clase
            layoutManager = LinearLayoutManager(requireContext())
            // Optimización: Evitar parpadeos al actualizar items
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }


    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarAjusteFinal.isVisible = state.isLoading

                state.plan?.fechaPlan?.let {
                    val dateFormat = SimpleDateFormat("dd / MMMM / yyyy", Locale("es", "ES"))
                    binding.textViewAjusteFinalTitle.text = "Ajuste Final - Plan ${dateFormat.format(it)}"
                } ?: run {
                    binding.textViewAjusteFinalTitle.text = "Ajuste Final - Cargando Plan..."
                }

                // Usar submitList para eficiencia del RecyclerView
                adapter.submitList(state.itemsParaAjustar)

                // Gestionar diálogo de carga
                if (state.isExecuting && loadingDialog == null) {
                    showLoadingDialog("Ejecutando traspaso...")
                } else if (!state.isExecuting && loadingDialog != null) {
                    hideLoadingDialog()
                }

                // Mostrar mensajes al usuario (Snackbar)
                state.userMessage?.let {
                    Snackbar.make(binding.root, it.message, Snackbar.LENGTH_LONG).show()
                    viewModel.onUserMessageShown() // Notificar al ViewModel que el mensaje se mostró
                }

                // Manejar eventos de navegación
                if (state.navigationEvent is NavigationEvent.GoBack) {
                    parentFragmentManager.popBackStack()
                    viewModel.onNavigationHandled() // Notificar al ViewModel que la navegación se manejó
                }
            }
        }
    }

    override fun onCantidadConfirmadaChanged(detalleId: String, nuevaCantidad: Double) {
        // Validar que la cantidad no sea negativa antes de enviarla al ViewModel
        if (nuevaCantidad >= 0) {
            viewModel.updateConfirmedQuantity(detalleId, nuevaCantidad)
        } else {
            // Opcional: Mostrar un mensaje al usuario si intenta poner cantidad negativa
            Snackbar.make(binding.root, "La cantidad no puede ser negativa.", Snackbar.LENGTH_SHORT).show()
            // Podrías forzar la reversión visual aquí si el adapter no lo hace automáticamente
            // adapter.notifyItemChanged(...)
        }
    }

    // --- CORRECCIÓN EN LA LLAMADA A newInstance ---
    override fun onEditarLotesClicked(item: AjusteFinalItem) {
        Log.d("AjusteFinalFrag", "onEditarLotesClicked para: ${item.product.name}")
        val productId = item.detalleOriginal.productId
        val productName = item.detalleOriginal.productName
        val currentPlanId = viewModel.uiState.value.plan?.id
        // **CORREGIDO**: Obtener el flag 'requiresPackaging' del objeto Product dentro del item
        val isBulk = item.product.requiresPackaging // <-- Obtenido de item.product
        Log.d("AjusteFinalFrag", " -> Es Granel (isBulk): $isBulk")


        // Determinar qué pasar como selección inicial (IDs o Desglose)
        val seleccionInicial: java.io.Serializable = if (item.lotesSeleccionadosManualmente) {
            Log.d("AjusteFinalFrag", " -> Pasando Desglose Manual existente: ${item.lotesConfirmados.size} lotes")
            // Si ya hay desglose manual, pasamos esa lista de DesgloseManualResult
            ArrayList(item.lotesConfirmados.mapNotNull { desglose ->
                // Asegurarse de que el lote no sea null (aunque no debería pasar aquí)
                if (desglose.lote != null) {
                    // La cantidad en DesgloseManualResult es siempre Double
                    val cantidad = if (isBulk) {
                        desglose.cantidadATomarKg
                    } else {
                        // Para fijos, necesitamos las unidades
                        desglose.cantidadATomarUnidades ?: 0.0 // Usar 0 si es null
                    }
                    // Solo incluir si la cantidad es mayor a cero para evitar entradas vacías preseleccionadas
                    if (cantidad > 0) DesgloseManualResult(desglose.loteId, cantidad) else null
                } else {
                    Log.w("AjusteFinalFrag", " -> Lote null encontrado en desglose manual para ${desglose.loteId}")
                    null
                }
            })
        } else {
            Log.d("AjusteFinalFrag", " -> Pasando IDs de lotes sugeridos/checkbox: ${item.lotesConfirmados.size} IDs")
            // Si no, pasamos la lista de IDs seleccionados (modo checkbox inicial o sugerencia FIFO)
            ArrayList(item.lotesConfirmados.map { it.loteId })
        }

        // **CORREGIDO**: Llamada a newInstance ahora incluye isBulkProduct
        SeleccionarLotesDialogFragment.newInstance(
            productId = productId,
            productName = productName,
            isBulkProduct = isBulk, // <-- Pasar el flag aquí
            selectedIdsOrDesglose = seleccionInicial,
            planId = currentPlanId // Pasar el planId para que el diálogo cargue lotes reservados por este plan
        ).show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
    }
    // --- FIN CORRECCIÓN ---


    private fun showLoadingDialog(message: String) {
        // Evitar crear múltiples diálogos si ya existe uno
        if (loadingDialog == null || loadingDialog?.isAdded == false) {
            loadingDialog = LoadingDialogFragment.newInstance()
            loadingDialog?.isCancelable = false // El usuario no debería poder cancelar la ejecución
            // Usar childFragmentManager para diálogos dentro de fragments
            loadingDialog?.show(childFragmentManager, LoadingDialogFragment.TAG)
        }
        // Actualizar mensaje si el diálogo ya está visible
        loadingDialog?.setMessage(message)
    }


    private fun hideLoadingDialog() {
        // Verificar si el diálogo existe y está añadido antes de intentar cerrarlo
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss() // Usar dismissAllowingStateLoss por seguridad en casos de lifecycle complejos
        }
        loadingDialog = null // Limpiar la referencia
    }


    override fun onDestroyView() {
        super.onDestroyView()
        // Asegurarse de quitar la flecha de regreso al salir del fragment
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        hideLoadingDialog() // Asegurarse de cerrar el diálogo si el fragment se destruye
        _binding = null // Limpiar binding para evitar memory leaks
    }
}

