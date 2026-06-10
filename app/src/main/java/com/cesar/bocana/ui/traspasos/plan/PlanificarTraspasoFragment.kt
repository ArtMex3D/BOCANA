package com.cesar.bocana.ui.traspasos.plan

import android.os.Bundle
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

    // Formato de fecha corto ajustado para tu diseño
    private val dateFormatDisplay = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
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

        // Cambiamos el texto del botón por código para no tener que tocar el XML
        binding.btnGenerarPdfTop.text = "Previsualizar"
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarPlan.isVisible = state.isLoading || state.isSaving
                binding.btnGenerarPdfTop.isEnabled = !state.isSaving

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

                // MAGIA: El Salto automático a la pestaña 2 (Confirmar/Historial)
                if (state.planGuardadoExitoso) {
                    Snackbar.make(binding.root, "Plan enviado a PDFs Recientes", Snackbar.LENGTH_SHORT).show()
                    val tabLayout = activity?.findViewById<TabLayout>(R.id.tab_layout_traspasos)
                    tabLayout?.getTabAt(1)?.select() // Índice 1 es la segunda pestaña
                    viewModel.onPlanGuardadoNavegado()
                    viewModel.cargarPlanDeTraspaso(descartarCache = true) // Limpia la lista para el próximo
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
        }
    }

    private fun setupRecyclerView() {
        adapter = PlanTraspasoAdapter(viewModel) { item ->
            // Si el usuario toca la Fila Vacía, no hacemos nada o abrimos un diálogo distinto (se maneja en el adapter)
            if (item.product.id == "FILA_VACIA") return@PlanTraspasoAdapter

            val selectedIds = item.lotesSeleccionadosManualmente?.map { it.id } ?: item.lotesParaTraspaso.map { it.loteId }
            SeleccionarLotesDialogFragment.newInstance(item.product.id, item.product.name, ArrayList(selectedIds))
                .show(childFragmentManager, SeleccionarLotesDialogFragment.TAG)
        }

        binding.recyclerViewPlanTraspaso.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlanificarTraspasoFragment.adapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun setupListeners() {
        binding.buttonTraspasoDate.setOnClickListener { showDatePicker() }

        // Al darle clic a Previsualizar, guardamos en la nube.
        // El Observer se encargará de hacer el "Salto" cuando termine de guardar.
        binding.btnGenerarPdfTop.setOnClickListener {
            viewModel.guardarPlanEnFirestore(selectedDate)
        }

        // Lógica del botón inferior: Agregar Fila Vacía
        binding.btnAgregarFilaVacia.setOnClickListener {
            val cantidadStr = binding.etCantidadFilas.text.toString()
            val cantidad = cantidadStr.toIntOrNull() ?: 1
            if(cantidad > 0) {
                viewModel.agregarFilaVacia(cantidad)
            }
            binding.etCantidadFilas.clearFocus()
            binding.etCantidadFilas.setText("1")
            binding.recyclerViewPlanTraspaso.smoothScrollToPosition(adapter.itemCount)
        }
    }

    private fun showDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Fecha")
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
        binding.buttonTraspasoDate.text = dateText
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}