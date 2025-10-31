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
// import com.cesar.bocana.ui.printing.PdfViewerFragment // Import no usado, se puede quitar si no previsualizas PDF aquí
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList // Asegurar import explícito para ArrayList

class PlanificarTraspasoFragment : Fragment() {

    private var _binding: FragmentPlanificarTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlanificarTraspasoViewModel by viewModels()
    private lateinit var adapter: PlanTraspasoAdapter
    // Formato de fecha consistente
    private val dateFormatDisplay = SimpleDateFormat("dd / MMMM / yyyy", Locale("es", "ES"))
    private var selectedDate: Date = Date() // Inicializar con fecha actual


    companion object {
    private const val stockEpsilon = 0.01
}
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanificarTraspasoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Listener de resultado del diálogo
        childFragmentManager.setFragmentResultListener(SeleccionarLotesDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val productId = bundle.getString(SeleccionarLotesDialogFragment.PRODUCT_ID_KEY) ?: return@setFragmentResultListener
            Log.d("PlanificarFrag", "Resultado recibido del diálogo para producto: $productId")

            val desgloseManualList = bundle.getParcelableArrayList<DesgloseManualResult>(SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY)
            val lotesSeleccionadosIds = bundle.getStringArrayList(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY)

            when {
                desgloseManualList != null && desgloseManualList.isNotEmpty() -> {
                    Log.d("PlanificarFrag", "Procesando resultado como Desglose Manual: ${desgloseManualList.size} items")
                    viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)
                }
                lotesSeleccionadosIds != null -> {
                    Log.d("PlanificarFrag", "Procesando resultado como Checkbox: ${lotesSeleccionadosIds.size} IDs")
                    viewModel.actualizarLotesManualmentePorIds(productId, lotesSeleccionadosIds)
                }
                else -> {
                    Log.w("PlanificarFrag", "Resultado del diálogo no contenía ni desglose ni IDs para producto: $productId")
                }
            }
        }

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        updateDateButtonText() // Mostrar fecha inicial
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarPlan.isVisible = state.isLoading || state.isSaving
                binding.fabCreatePlan.isEnabled = !state.isSaving

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
                    viewModel.cargarPlanDeTraspaso(descartarCache = true)
                }
            }
        }
    }

    private fun mostrarDialogoDeCache() {
        context?.let { ctx ->
            AlertDialog.Builder(ctx)
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
        } ?: Log.e("PlanificarFrag", "Contexto nulo al intentar mostrar diálogo de caché")
    }

    private fun setupRecyclerView() {
        adapter = PlanTraspasoAdapter(viewModel) { item ->
            Log.d("PlanificarFrag", "Click en 'Seleccionar Lotes' para: ${item.product.name}")
            // **AQUÍ SE DETERMINA EL MODO**:
            // Usamos 'requiresPackaging' para saber si el producto es Granel (true) o Fijo (false)
            val isBulk = item.product.requiresPackaging
            Log.d("PlanificarFrag", " -> Es Granel (isBulk): $isBulk (requiresPackaging=${item.product.requiresPackaging})")

            val seleccionInicial: java.io.Serializable
            if (item.lotesSeleccionadosManualmente != null) {
                Log.d("PlanificarFrag", " -> Pasando Selección Manual existente.")
                // Si ya hay selección manual (checkbox o desglose previo)
                // **INICIO CORRECCIÓN**: Determinar qué tipo de selección manual es
                if (item.lotesParaTraspaso.any { it.cantidadATomarKg > 0 } && item.lotesParaTraspaso.any { it.lote != null }) {
                    // Si hay desglose (lotesParaTraspaso tiene datos), pasamos la lista de DesgloseManualResult
                    Log.d("PlanificarFrag", "    -> Como DesgloseManualResult (${item.lotesParaTraspaso.size} lotes)")
                    seleccionInicial = ArrayList(item.lotesParaTraspaso.mapNotNull { desglose ->
                        if (desglose.lote != null) {
                            val cantidad = if (isBulk) {
                                desglose.cantidadATomarKg
                            } else {
                                desglose.cantidadATomarUnidades ?: 0.0
                            }
                            if (cantidad > 0) DesgloseManualResult(desglose.loteId, cantidad) else null
                        } else {
                            null
                        }
                    })
                } else {
                    // Si es selección manual por checkbox (lotesSeleccionadosManualmente no es null pero lotesParaTraspaso está vacío o sin datos)
                    Log.d("PlanificarFrag", "    -> Como Lista de IDs (checkbox) (${item.lotesSeleccionadosManualmente?.size ?: 0} IDs)")
                    seleccionInicial = ArrayList(item.lotesSeleccionadosManualmente?.map { it.id } ?: emptyList<String>())
                }
                // **FIN CORRECCIÓN**
            } else {
                Log.d("PlanificarFrag", " -> Pasando IDs de sugerencia FIFO: ${item.lotesParaTraspaso.size} IDs")
                // Si es la sugerencia inicial FIFO, pasamos los IDs
                seleccionInicial = ArrayList(item.lotesParaTraspaso.map { it.loteId })
            }

            // Llamamos a newInstance con el flag correcto
            SeleccionarLotesDialogFragment.newInstance(
                productId = item.product.id,
                productName = item.product.name,
                isBulkProduct = isBulk, // <-- Aquí pasamos el flag
                selectedIdsOrDesglose = seleccionInicial,
                planId = null // No aplica planId en planificación
            ).show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
        }

        binding.recyclerViewPlanTraspaso.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlanificarTraspasoFragment.adapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }


    private fun setupListeners() {
        binding.buttonTraspasoDate.setOnClickListener { showDatePicker() }
        binding.fabCreatePlan.setOnClickListener {
            if (viewModel.uiState.value.sugerencias.any { it.incluidoEnPdf && (it.sugerenciaKg > stockEpsilon || it.product.name == "FILA_VACIA") }) {
                viewModel.guardarPlanEnFirestore(selectedDate)
            } else {
                Snackbar.make(binding.root, "No hay productos seleccionados o con cantidad para generar el plan.", Snackbar.LENGTH_SHORT).show()
            }
        }
        binding.buttonAddBlankRow.setOnClickListener {
            viewModel.agregarFilaVacia()
        }
    }

    private fun showDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Fecha del Traspaso")
            .setSelection(selectedDate.time + TimeZone.getDefault().getOffset(selectedDate.time))
            .build()

        datePicker.addOnPositiveButtonClickListener { selectionUtc ->
            val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utcCalendar.timeInMillis = selectionUtc
            val localCalendar = Calendar.getInstance()
            localCalendar.set(
                utcCalendar.get(Calendar.YEAR),
                utcCalendar.get(Calendar.MONTH),
                utcCalendar.get(Calendar.DAY_OF_MONTH),
                0, 0, 0
            )
            localCalendar.set(Calendar.MILLISECOND, 0)

            selectedDate = localCalendar.time
            updateDateButtonText()
        }
        datePicker.show(parentFragmentManager, "DATE_PICKER_TRASPASO")
    }


    private fun updateDateButtonText() {
        val dateText = dateFormatDisplay.format(selectedDate).uppercase(Locale.getDefault())
        binding.buttonTraspasoDate.text = "Traspaso para: $dateText"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
