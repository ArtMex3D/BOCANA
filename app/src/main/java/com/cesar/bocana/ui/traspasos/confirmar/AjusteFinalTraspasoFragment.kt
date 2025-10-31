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

            val desgloseManualList = bundle.getParcelableArrayList<DesgloseManualResult>(SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY)
            val lotesSeleccionadosIds = bundle.getStringArrayList(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY)

            when {
                desgloseManualList != null && desgloseManualList.isNotEmpty() -> {
                    Log.d("AjusteFinalFrag", "Procesando resultado como Desglose Manual: ${desgloseManualList.size} items")
                    viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)
                }
                lotesSeleccionadosIds != null -> {
                    Log.d("AjusteFinalFrag", "Procesando resultado como Checkbox: ${lotesSeleccionadosIds.size} IDs")
                    viewModel.actualizarLotesManualmentePorIds(productId, lotesSeleccionadosIds)
                }
                else -> {
                    Log.w("AjusteFinalFrag", "Resultado del diálogo no contenía ni desglose ni IDs para producto: $productId")
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
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Ajuste y Confirmación"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onPrepareMenu(menu: Menu) {}

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            android.R.id.home -> {
                activity?.onBackPressedDispatcher?.onBackPressed()
                true
            }
            else -> false
        }
    }

    private fun setupRecyclerView() {
        adapter = AjusteFinalTraspasoAdapter(this)
        binding.recyclerViewAjusteFinal.apply {
            this.adapter = this@AjusteFinalTraspasoFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
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

                adapter.submitList(state.itemsParaAjustar)

                if (state.isExecuting && loadingDialog == null) {
                    showLoadingDialog("Ejecutando traspaso...")
                } else if (!state.isExecuting && loadingDialog != null) {
                    hideLoadingDialog()
                }

                state.userMessage?.let {
                    Snackbar.make(binding.root, it.message, Snackbar.LENGTH_LONG).show()
                    viewModel.onUserMessageShown()
                }

                if (state.navigationEvent is NavigationEvent.GoBack) {
                    parentFragmentManager.popBackStack()
                    viewModel.onNavigationHandled()
                }
            }
        }
    }

    override fun onCantidadConfirmadaChanged(detalleId: String, nuevaCantidad: Double) {
        if (nuevaCantidad >= 0) {
            viewModel.updateConfirmedQuantity(detalleId, nuevaCantidad)
        } else {
            Snackbar.make(binding.root, "La cantidad no puede ser negativa.", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onEditarLotesClicked(item: AjusteFinalItem) {
        Log.d("AjusteFinalFrag", "onEditarLotesClicked para: ${item.product.name}")
        val productId = item.detalleOriginal.productId
        val productName = item.detalleOriginal.productName
        val currentPlanId = viewModel.uiState.value.plan?.id

        // **AQUÍ SE DETERMINA EL MODO**:
        // Usamos el flag del producto (que viene en AjusteFinalItem)
        val isBulk = item.product.requiresPackaging
        Log.d("AjusteFinalFrag", " -> Es Granel (isBulk): $isBulk (requiresPackaging=${item.product.requiresPackaging})")

        // Determinar qué pasar como selección inicial (IDs o Desglose)
        val seleccionInicial: java.io.Serializable
        // **INICIO CORRECCIÓN**: Usar el flag booleano
        if (item.lotesSeleccionadosManualmente) {
            // **FIN CORRECCIÓN**
            Log.d("AjusteFinalFrag", " -> Pasando Desglose Manual existente: ${item.lotesConfirmados.size} lotes")
            // Si ya hay desglose manual, pasamos esa lista de DesgloseManualResult
            seleccionInicial = ArrayList(item.lotesConfirmados.mapNotNull { desglose ->
                if (desglose.lote != null) {
                    val cantidad = if (isBulk) {
                        desglose.cantidadATomarKg
                    } else {
                        desglose.cantidadATomarUnidades ?: 0.0
                    }
                    if (cantidad > 0) DesgloseManualResult(desglose.loteId, cantidad) else null
                } else {
                    Log.w("AjusteFinalFrag", " -> Lote null encontrado en desglose manual para ${desglose.loteId}")
                    null
                }
            })
        } else {
            Log.d("AjusteFinalFrag", " -> Pasando IDs de lotes sugeridos/checkbox: ${item.lotesConfirmados.size} IDs")
            // Si no, pasamos la lista de IDs seleccionados (sugerencia FIFO)
            seleccionInicial = ArrayList(item.lotesConfirmados.map { it.loteId })
        }

        // Llamamos a newInstance con el flag correcto
        SeleccionarLotesDialogFragment.newInstance(
            productId = productId,
            productName = productName,
            isBulkProduct = isBulk, // <-- Aquí pasamos el flag
            selectedIdsOrDesglose = seleccionInicial,
            planId = currentPlanId // Pasar el planId para cargar lotes reservados
        ).show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
    }

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null || loadingDialog?.isAdded == false) {
            loadingDialog = LoadingDialogFragment.newInstance()
            loadingDialog?.isCancelable = false
            loadingDialog?.show(childFragmentManager, LoadingDialogFragment.TAG)
        }
        loadingDialog?.setMessage(message)
    }

    private fun hideLoadingDialog() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        hideLoadingDialog()
        _binding = null
    }
}
