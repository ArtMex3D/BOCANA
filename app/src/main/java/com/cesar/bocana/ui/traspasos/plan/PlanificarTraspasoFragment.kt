package com.cesar.bocana.ui.traspasos.plan

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.FragmentPlanificarTraspasoBinding
import com.cesar.bocana.ui.printing.PdfViewerFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PlanificarTraspasoFragment : Fragment() {

    private var _binding: FragmentPlanificarTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlanificarTraspasoViewModel by viewModels()
    private lateinit var adapter: PlanTraspasoAdapter
    private val dateFormat = SimpleDateFormat("dd / MMMM / yyyy", Locale("es", "ES"))
    private var selectedDate: Date = Date()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanificarTraspasoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        childFragmentManager.setFragmentResultListener(SeleccionarLotesDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val productId = bundle.getString(SeleccionarLotesDialogFragment.PRODUCT_ID_KEY) ?: return@setFragmentResultListener
            val desgloseManualList = bundle.getParcelableArrayList<DesgloseManualResult>(SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY)
            val lotesSeleccionados = bundle.getParcelableArrayList<StockLot>(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY)

            when {
                desgloseManualList != null && desgloseManualList.isNotEmpty() -> viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)
                lotesSeleccionados != null -> viewModel.actualizarLotesManualmente(productId, lotesSeleccionados)
            }
        }

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        updateDateButtonText()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarPlan.isVisible = state.isLoading || state.isSaving
                binding.fabCreatePlan.isEnabled = !state.isSaving
                binding.fabGeneratePdf.isEnabled = !state.isSaving

                adapter.submitList(state.sugerencias)

                if (state.preguntaCache) {
                    mostrarDialogoDeCache()
                    viewModel.onDialogoMostrado()
                }

                state.error?.let {
                    Snackbar.make(binding.root, "Error: $it", Snackbar.LENGTH_LONG).show()
                }
                state.snackbarMessage?.let {
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                    viewModel.onSnackbarShown()
                }

                if (state.planGuardadoExitoso) {
                    Snackbar.make(binding.root, "Plan creado. Ya puedes ir a 'Confirmar Traspaso'.", Snackbar.LENGTH_LONG)
                        .setAction("IR") {
                            val tabLayout = activity?.findViewById<TabLayout>(R.id.tab_layout_traspasos)
                            tabLayout?.getTabAt(1)?.select()
                        }
                        .show()
                    viewModel.onPlanGuardadoNavegado()
                    viewModel.cargarPlanDeTraspaso(descartarCache = true) // Recargar para empezar de cero
                }
            }
        }
    }

    private fun mostrarDialogoDeCache() {
        AlertDialog.Builder(requireContext())
            .setTitle("Continuar Planificación")
            .setMessage("Se encontró un plan sin terminar del día de hoy. ¿Deseas continuar con él?")
            .setPositiveButton("Sí, continuar") { _, _ ->
                viewModel.cargarPlanDesdeCache()
            }
            .setNegativeButton("No, empezar de cero") { _, _ ->
                viewModel.cargarPlanDeTraspaso(descartarCache = true)
            }
            .setCancelable(false)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = PlanTraspasoAdapter(viewModel) { item ->
            val selectedIds = item.lotesSeleccionadosManualmente?.map { it.id } ?: item.lotesParaTraspaso.map { it.loteId }
            SeleccionarLotesDialogFragment.newInstance(item.product.id, item.product.name, selectedIds)
                .show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
        }
        binding.recyclerViewPlanTraspaso.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewPlanTraspaso.adapter = adapter
        (binding.recyclerViewPlanTraspaso.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupListeners() {
        binding.buttonTraspasoDate.setOnClickListener { showDatePicker() }
        binding.fabGeneratePdf.setOnClickListener { generarYVisualizarPdf() }
        binding.fabCreatePlan.setOnClickListener {
            viewModel.guardarPlanEnFirestore(selectedDate)
        }
    }

    private fun generarYVisualizarPdf() {
        binding.progressBarPlan.isVisible = true
        lifecycleScope.launch {
            try {
                val plan = viewModel.uiState.value.sugerencias.filter { it.incluidoEnPdf }
                if (plan.isEmpty()) {
                    Snackbar.make(binding.root, "No hay productos seleccionados para incluir en el PDF.", Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                val pdfFile = TraspasoPdfGenerator.createTraspasoPdf(requireContext(), plan, selectedDate)
                val pdfViewerFragment = PdfViewerFragment.newInstance(pdfFile.absolutePath)

                // Usamos parentFragmentManager para reemplazar el contenido del container principal
                parentFragmentManager.beginTransaction()
                    .replace(R.id.traspasos_fragment_container, pdfViewerFragment)
                    .addToBackStack(null)
                    .commit()

            } catch (e: Exception) {
                Log.e("PlanificarTraspaso", "Error al generar PDF", e)
                Snackbar.make(binding.root, "Error al generar el PDF: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                if (isAdded) {
                    binding.progressBarPlan.isVisible = false
                }
            }
        }
    }

    private fun showDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Fecha del Traspaso")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utcCalendar.timeInMillis = selection
            val localCalendar = Calendar.getInstance()
            localCalendar.set(utcCalendar.get(Calendar.YEAR), utcCalendar.get(Calendar.MONTH), utcCalendar.get(Calendar.DAY_OF_MONTH))
            selectedDate = localCalendar.time
            updateDateButtonText()
        }
        datePicker.show(parentFragmentManager, "DATE_PICKER_TRASPASO")
    }

    private fun updateDateButtonText() {
        val dateText = dateFormat.format(selectedDate).uppercase()
        binding.buttonTraspasoDate.text = "Traspaso para: $dateText"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
