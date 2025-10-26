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
        // Usar childFragmentManager ya que el diálogo es hijo de este fragment
        childFragmentManager.setFragmentResultListener(SeleccionarLotesDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val productId = bundle.getString(SeleccionarLotesDialogFragment.PRODUCT_ID_KEY) ?: return@setFragmentResultListener
            Log.d("PlanificarFrag", "Resultado recibido del diálogo para producto: $productId")

            // Intentar obtener ambos tipos de resultado
            val desgloseManualList = bundle.getParcelableArrayList<DesgloseManualResult>(SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY)
            val lotesSeleccionadosIds = bundle.getStringArrayList(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY) // Esperamos IDs

            when {
                // Prioridad al desglose manual si existe y no está vacío
                desgloseManualList != null && desgloseManualList.isNotEmpty() -> {
                    Log.d("PlanificarFrag", "Procesando resultado como Desglose Manual: ${desgloseManualList.size} items")
                    viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)
                }
                // Si no hay desglose, verificar si hay IDs de checkboxes
                lotesSeleccionadosIds != null -> {
                    Log.d("PlanificarFrag", "Procesando resultado como Checkbox: ${lotesSeleccionadosIds.size} IDs")
                    // Pasar la lista de IDs al ViewModel
                    viewModel.actualizarLotesManualmentePorIds(productId, lotesSeleccionadosIds)
                }
                else -> {
                    // Caso donde no se devuelve nada útil (ej. canceló sin seleccionar o error)
                    Log.w("PlanificarFrag", "Resultado del diálogo no contenía ni desglose ni IDs para producto: $productId")
                    // Opcional: Podrías resetear la selección manual si el usuario cancela sin datos válidos
                    // viewModel.resetearSeleccionManual(productId)
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
            // Usar collectLatest para cancelar recolecciones anteriores si el estado cambia rápidamente
            viewModel.uiState.collectLatest { state ->
                // Actualizar UI según el estado
                binding.progressBarPlan.isVisible = state.isLoading || state.isSaving
                binding.fabCreatePlan.isEnabled = !state.isSaving // Deshabilitar FAB si está cargando o guardando

                // Actualizar el adapter con la nueva lista de sugerencias
                adapter.submitList(state.sugerencias)

                // Mostrar diálogo de caché si es necesario
                if (state.preguntaCache) {
                    mostrarDialogoDeCache()
                    viewModel.onDialogoMostrado() // Notificar al VM que el diálogo se mostró
                }

                // Mostrar errores
                state.error?.let {
                    Snackbar.make(binding.root, "Error: $it", Snackbar.LENGTH_LONG).show()
                    // Podrías querer limpiar el error en el VM después de mostrarlo
                    // viewModel.clearError()
                }
                // Mostrar mensajes informativos
                state.snackbarMessage?.let {
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                    viewModel.onSnackbarShown() // Notificar al VM que se mostró
                }

                // Manejar evento de guardado exitoso
                if (state.planGuardadoExitoso) {
                    Snackbar.make(binding.root, "Plan creado. Ya puedes ir a 'Confirmar Traspaso'.", Snackbar.LENGTH_LONG)
                        .setAction("IR") {
                            // Navegar a la pestaña de Confirmar
                            val tabLayout = activity?.findViewById<TabLayout>(R.id.tab_layout_traspasos)
                            tabLayout?.getTabAt(1)?.select()
                        }
                        .show()
                    viewModel.onPlanGuardadoNavegado() // Notificar al VM
                    // Recargar plan desde cero después de guardar exitosamente
                    viewModel.cargarPlanDeTraspaso(descartarCache = true)
                }
            }
        }
    }

    private fun mostrarDialogoDeCache() {
        // Asegurarse de que el contexto no sea nulo
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
                .setCancelable(false) // Evitar que se cierre tocando fuera
                .show()
        } ?: Log.e("PlanificarFrag", "Contexto nulo al intentar mostrar diálogo de caché")
    }


    // --- CORRECCIÓN EN LA LLAMADA A newInstance ---
    private fun setupRecyclerView() {
        adapter = PlanTraspasoAdapter(viewModel) { item ->
            Log.d("PlanificarFrag", "Click en 'Seleccionar Lotes' para: ${item.product.name}")
            // **CORREGIDO**: Pasar el flag 'requiresPackaging' del producto
            val isBulk = item.product.requiresPackaging // <-- Obtenido de item.product
            Log.d("PlanificarFrag", " -> Es Granel (isBulk): $isBulk")

            // Determinar qué pasar como selección inicial (IDs o Desglose)
            val seleccionInicial: java.io.Serializable = if (item.lotesSeleccionadosManualmente != null) {
                Log.d("PlanificarFrag", " -> Pasando Selección Manual existente.")
                // Si ya hay selección manual (checkbox o desglose previo)
                if(item.lotesParaTraspaso.isNotEmpty()) {
                    // Pasar la lista de DesgloseManualResult si ya existe un desglose
                    Log.d("PlanificarFrag", "    -> Como DesgloseManualResult (${item.lotesParaTraspaso.size} lotes)")
                    ArrayList(item.lotesParaTraspaso.mapNotNull { desglose ->
                        // Asegurarse de que el lote no sea null
                        if (desglose.lote != null) {
                            val cantidad = if (isBulk) {
                                desglose.cantidadATomarKg
                            } else {
                                desglose.cantidadATomarUnidades ?: 0.0 // Unidades para fijos
                            }
                            // Solo incluir si la cantidad es > 0
                            if (cantidad > 0) DesgloseManualResult(desglose.loteId, cantidad) else null
                        } else {
                            Log.w("PlanificarFrag", "    -> Lote null encontrado en desglose para ${desglose.loteId}")
                            null
                        }
                    })
                } else {
                    // Si es selección manual por checkbox pero sin desglose aún (lista lotesParaTraspaso vacía)
                    Log.d("PlanificarFrag", "    -> Como Lista de IDs (checkbox sin desglose) (${item.lotesSeleccionadosManualmente?.size ?: 0} IDs)")
                    ArrayList(item.lotesSeleccionadosManualmente?.map { it.id } ?: emptyList<String>())
                }
            } else {
                Log.d("PlanificarFrag", " -> Pasando IDs de sugerencia FIFO: ${item.lotesParaTraspaso.size} IDs")
                // Si es la sugerencia inicial FIFO, pasamos los IDs
                ArrayList(item.lotesParaTraspaso.map { it.loteId })
            }

            // **CORREGIDO**: Llamada a newInstance ahora incluye isBulkProduct
            SeleccionarLotesDialogFragment.newInstance(
                productId = item.product.id,
                productName = item.product.name,
                isBulkProduct = isBulk, // <-- Pasar el flag aquí
                selectedIdsOrDesglose = seleccionInicial,
                planId = null // No aplica planId en planificación, solo en confirmación
            ).show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
        }

        binding.recyclerViewPlanTraspaso.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlanificarTraspasoFragment.adapter // Usar el adapter de la clase
            // Optimización: Evitar parpadeos al actualizar items
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }
    // --- FIN CORRECCIÓN ---

    private fun setupListeners() {
        binding.buttonTraspasoDate.setOnClickListener { showDatePicker() }
        binding.fabCreatePlan.setOnClickListener {
            // Validar si hay algo que guardar antes de llamar al ViewModel
            if (viewModel.uiState.value.sugerencias.any { it.incluidoEnPdf && (it.sugerenciaKg > 0.01 || it.product.name == "FILA_VACIA") }) {
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
        // Usar UTC para el picker, luego convertir a local
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Fecha del Traspaso")
            // Preseleccionar la fecha actual o la ya seleccionada
            .setSelection(selectedDate.time + TimeZone.getDefault().getOffset(selectedDate.time)) // Ajustar a UTC para el picker
            .build()

        datePicker.addOnPositiveButtonClickListener { selectionUtc ->
            // El picker devuelve medianoche UTC del día seleccionado
            val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utcCalendar.timeInMillis = selectionUtc
            // Convertir a la zona horaria local manteniendo el día, mes y año
            val localCalendar = Calendar.getInstance() // Usa la zona horaria local por defecto
            localCalendar.set(
                utcCalendar.get(Calendar.YEAR),
                utcCalendar.get(Calendar.MONTH),
                utcCalendar.get(Calendar.DAY_OF_MONTH),
                0, 0, 0 // Poner hora a 00:00:00 local para consistencia
            )
            localCalendar.set(Calendar.MILLISECOND, 0)

            selectedDate = localCalendar.time // Guardar la fecha local
            updateDateButtonText() // Actualizar el texto del botón
        }
        // Usar childFragmentManager si este fragmento está contenido en otro
        datePicker.show(parentFragmentManager, "DATE_PICKER_TRASPASO")
    }


    private fun updateDateButtonText() {
        // Usar el formato de display definido en la clase
        val dateText = dateFormatDisplay.format(selectedDate).uppercase(Locale.getDefault())
        binding.buttonTraspasoDate.text = "Traspaso para: $dateText"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Limpiar binding para evitar memory leaks
    }
}

