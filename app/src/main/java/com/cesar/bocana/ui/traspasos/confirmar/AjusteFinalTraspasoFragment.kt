package com.cesar.bocana.ui.traspasos.confirmar

import android.os.Bundle
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
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
            val lotesSeleccionados = bundle.getParcelableArrayList<StockLot>(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY)

            if (lotesSeleccionados != null) {
                viewModel.actualizarLotesManualmente(productId, lotesSeleccionados)
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

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            android.R.id.home -> {
                parentFragmentManager.popBackStack()
                true
            }
            else -> false
        }
    }

    private fun setupRecyclerView() {
        adapter = AjusteFinalTraspasoAdapter(this)
        binding.recyclerViewAjusteFinal.adapter = adapter
        binding.recyclerViewAjusteFinal.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarAjusteFinal.isVisible = state.isLoading

                state.plan?.fechaPlan?.let {
                    val dateFormat = SimpleDateFormat("dd / MMMM / yyyy", Locale("es", "ES"))
                    binding.textViewAjusteFinalTitle.text = "Ajuste Final - Plan ${dateFormat.format(it)}"
                }

                adapter.submitList(state.itemsParaAjustar)

                if (state.isExecuting) {
                    showLoadingDialog("Ejecutando traspaso...")
                } else {
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

    // --- Implementación de la Interfaz del Adaptador ---
    override fun onCantidadConfirmadaChanged(detalleId: String, nuevaCantidad: Double) {
        viewModel.updateConfirmedQuantity(detalleId, nuevaCantidad)
    }

    override fun onEditarLotesClicked(item: AjusteFinalItem) {
        val selectedIds = item.lotesConfirmados.map { it.loteId }
        SeleccionarLotesDialogFragment.newInstance(
            item.detalleOriginal.productId,
            item.detalleOriginal.productName,
            selectedIds
        ).show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
    }
    // --- Fin de la Implementación ---

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null) {
            loadingDialog = LoadingDialogFragment.newInstance()
            loadingDialog?.isCancelable = false
        }
        if (loadingDialog?.isAdded == false) {
            loadingDialog?.show(childFragmentManager, LoadingDialogFragment.TAG)
        }
        loadingDialog?.setMessage(message)
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        _binding = null
    }
}
