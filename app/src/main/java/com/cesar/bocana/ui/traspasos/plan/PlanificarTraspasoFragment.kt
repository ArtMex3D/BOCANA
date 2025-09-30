package com.cesar.bocana.ui.traspasos.plan

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.FragmentPlanificarTraspasoBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class PlanificarTraspasoFragment : Fragment() {

    private var _binding: FragmentPlanificarTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlanificarTraspasoViewModel by viewModels()
    private lateinit var adapter: PlanTraspasoAdapter
    private val dateFormat = SimpleDateFormat("dd / MMMM / yyyy", Locale("es", "ES"))
    private var selectedDate: Date = Date()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // <-- CORRECCIÓN CRÍTICA: El listener se configura aquí, pero usando el FragmentManager correcto.
        // Se usará childFragmentManager.setFragmentResultListener en onViewCreated para mayor seguridad del ciclo de vida.
        // Por ahora, dejamos este método para demostrar la lógica, pero la implementación final estará en onViewCreated.
        Log.d("PlanificarTraspaso", "onCreate: Preparando la configuración del listener.")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanificarTraspasoBinding.inflate(inflater, container, false)
        Log.d("PlanificarTraspaso", "onCreateView")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("PlanificarTraspaso", "onViewCreated")

        // <-- SOLUCIÓN DEFINITIVA: Se configura el listener en el childFragmentManager y ligado al ciclo de vida de la vista.
        // Este es el "puente" reparado que ahora escuchará correctamente los resultados del diálogo.
        childFragmentManager.setFragmentResultListener(SeleccionarLotesDialogFragment.REQUEST_KEY, viewLifecycleOwner) { requestKey, bundle ->
            Log.d("PlanificarTraspaso", "¡FragmentResult RECIBIDO CORRECTAMENTE! RequestKey: $requestKey")

            val productId = bundle.getString(SeleccionarLotesDialogFragment.PRODUCT_ID_KEY)
            if (productId == null) {
                Log.e("PlanificarTraspaso", "Error Crítico: ProductId es nulo en el resultado del diálogo.")
                return@setFragmentResultListener
            }

            val desgloseManualList = bundle.getParcelableArrayList<DesgloseManualResult>(SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY)
            val lotesSeleccionados = bundle.getParcelableArrayList<StockLot>(SeleccionarLotesDialogFragment.RESULT_LOTES_KEY)

            when {
                desgloseManualList != null && desgloseManualList.isNotEmpty() -> {
                    Log.d("PlanificarTraspaso", "MODO MANUAL DETECTADO. Llamando a viewModel.actualizarPorDesgloseManual con ${desgloseManualList.size} items.")
                    viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)
                }
                lotesSeleccionados != null -> {
                    Log.d("PlanificarTraspaso", "MODO SELECCIÓN DETECTADO. Llamando a viewModel.actualizarLotesManualmente con ${lotesSeleccionados.size} lotes.")
                    viewModel.actualizarLotesManualmente(productId, lotesSeleccionados)
                }
                else -> {
                    Log.w("PlanificarTraspaso", "Resultado del diálogo recibido pero sin datos procesables.")
                }
            }
        }

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        updateDateButtonText()
    }

    private fun setupRecyclerView() {
        adapter = PlanTraspasoAdapter(viewModel) { item ->
            Log.d("PlanificarTraspaso", "Clic en seleccionar lotes para producto: ${item.product.name}")
            val selectedIds = item.lotesSeleccionadosManualmente?.map { it.id } ?: item.lotesParaTraspaso.map { it.lote.id }
            Log.d("PlanificarTraspaso", "IDs a pasar al diálogo: $selectedIds")
            // Se muestra el diálogo usando el mismo childFragmentManager donde está el listener.
            SeleccionarLotesDialogFragment.newInstance(item.product.id, item.product.name, selectedIds)
                .show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
        }
        binding.recyclerViewPlanTraspaso.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewPlanTraspaso.adapter = adapter
        (binding.recyclerViewPlanTraspaso.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupListeners() {
        binding.buttonTraspasoDate.setOnClickListener {
            Log.d("PlanificarTraspaso", "Clic en selector de fecha")
            showDatePicker()
        }
        binding.fabGeneratePdf.setOnClickListener {
            Log.d("PlanificarTraspaso", "Clic en generar PDF")
            Snackbar.make(binding.root, "Generando PDF (Próximamente...)", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d("PlanificarTraspaso", "UI State actualizado: isLoading=${state.isLoading}, error=${state.error}, sugerencias=${state.sugerencias.size}")
                binding.progressBarPlan.isVisible = state.isLoading
                adapter.submitList(state.sugerencias)
                state.error?.let {
                    Log.e("PlanificarTraspaso", "Error en UI State: $it")
                    Snackbar.make(binding.root, "Error: $it", Snackbar.LENGTH_LONG).show()
                }
                state.snackbarMessage?.let {
                    Log.d("PlanificarTraspaso", "Mostrando Snackbar: $it")
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                    viewModel.onSnackbarShown()
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
            Log.d("PlanificarTraspaso", "Fecha seleccionada: $selection")
            val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utcCalendar.timeInMillis = selection
            val localCalendar = Calendar.getInstance()
            localCalendar.set(utcCalendar.get(Calendar.YEAR), utcCalendar.get(Calendar.MONTH), utcCalendar.get(Calendar.DAY_OF_MONTH))

            selectedDate = localCalendar.time
            Log.d("PlanificarTraspaso", "Fecha válida seleccionada: $selectedDate")
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
        Log.d("PlanificarTraspaso", "onDestroyView")
    }
}

