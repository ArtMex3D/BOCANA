package com.cesar.bocana.ui.history

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.InventoryRepository
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.databinding.FragmentAdvancedHistoryBinding
import com.cesar.bocana.ui.adapters.LoadStateAdapter
import com.cesar.bocana.ui.adapters.StockMovementAdapter
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AdvancedHistoryFragment : Fragment() {

    private var _binding: FragmentAdvancedHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AdvancedHistoryViewModel by viewModels {
        AdvancedHistoryViewModelFactory(
            InventoryRepository(
                AppDatabase.getDatabase(requireContext()),
                Firebase.firestore
            )
        )
    }

    private lateinit var movementAdapter: StockMovementAdapter
    private lateinit var repository: InventoryRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdvancedHistoryBinding.inflate(inflater, container, false)
        val db = AppDatabase.getDatabase(requireContext())
        repository = InventoryRepository(db, Firebase.firestore)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Búsqueda Avanzada"

        setupRecyclerView()
        setupFilterListeners()
        observeViewModel()
        syncAndLoad()
    }

    private fun syncAndLoad() {
        lifecycleScope.launch {
            try {
                repository.syncNewData()
                Log.d("AdvancedHistory", "Sincronización inteligente completada.")
            } catch (e: Exception) {
                if (isAdded) {
                    Snackbar.make(binding.root, "Error al sincronizar: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        movementAdapter = StockMovementAdapter(repository, viewLifecycleOwner.lifecycleScope)
        binding.recyclerViewAdvancedHistory.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewAdvancedHistory.adapter = movementAdapter.withLoadStateFooter(
            footer = LoadStateAdapter { movementAdapter.retry() }
        )

        movementAdapter.addLoadStateListener { loadState ->
            binding.progressBarAdvancedHistory.isVisible = loadState.refresh is LoadState.Loading
            val isListEmpty = loadState.refresh is LoadState.NotLoading && movementAdapter.itemCount == 0
            binding.textViewEmpty.isVisible = isListEmpty

            val errorState = loadState.refresh as? LoadState.Error
            errorState?.let {
                Snackbar.make(binding.root, "Error: ${it.error.localizedMessage}", Snackbar.LENGTH_INDEFINITE)
                    .setAction("REINTENTAR") { movementAdapter.retry() }
                    .show()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.movements.collectLatest { pagingData ->
                movementAdapter.submitData(pagingData)
            }
        }
    }

    private fun setupFilterListeners() {
        binding.editTextFreeText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setFreeTextFilter(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.buttonDateRange.setOnClickListener { showDateRangePicker() }

        binding.chipGroupMovementType.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                viewModel.setMovementTypesFilter(null)
                viewModel.setLotIdsFilter(null)
                return@setOnCheckedStateChangeListener
            }

            val selectedChipId = checkedIds.first()

            if (selectedChipId != R.id.chipLote) {
                viewModel.setLotIdsFilter(null)
            }

            when (selectedChipId) {
                R.id.chipLote -> {
                    viewModel.setMovementTypesFilter(null)
                    showLotDatePicker()
                }
                R.id.chipCompra -> viewModel.setMovementTypesFilter(listOf(MovementType.COMPRA, MovementType.AJUSTE_POSITIVO))
                R.id.chipConsumo -> viewModel.setMovementTypesFilter(listOf(MovementType.SALIDA_CONSUMO, MovementType.SALIDA_CONSUMO_C04, MovementType.SALIDA_DEVOLUCION, MovementType.AJUSTE_NEGATIVO, MovementType.BAJA_PRODUCTO))
                R.id.chipTraspaso -> viewModel.setMovementTypesFilter(listOf(MovementType.TRASPASO_M_C04, MovementType.TRASPASO_C04_M))
                R.id.chipAjuste -> viewModel.setMovementTypesFilter(listOf(MovementType.AJUSTE_MANUAL, MovementType.AJUSTE_STOCK_C04))
            }
        }
    }

    private fun showDateRangePicker() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Seleccionar Rango de Movimiento")
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            // Usamos Calendar para manejar correctamente la zona horaria local
            val tz = TimeZone.getDefault()
            val startCal = Calendar.getInstance(tz).apply {
                timeInMillis = selection.first
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            val endCal = Calendar.getInstance(tz).apply {
                timeInMillis = selection.second
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
            }

            binding.buttonDateRange.text = "${SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(startCal.time)} - ${SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(endCal.time)}"
            viewModel.setDateRangeFilter(startCal.time, endCal.time)
        }

        datePicker.show(parentFragmentManager, "DATE_PICKER")
    }

    // --- FUNCIÓN DE SELECCIÓN DE FECHA DE LOTE CORREGIDA ---
    private fun showLotDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Fecha del Lote")
            .build()

        datePicker.addOnPositiveButtonClickListener { utcTimestamp ->
            // El picker devuelve un timestamp UTC para el inicio del día (00:00 UTC).
            // Lo convertimos a la zona horaria local para obtener el día correcto.
            val tz = TimeZone.getDefault()
            val cal = Calendar.getInstance(tz).apply {
                timeInMillis = utcTimestamp
            }

            // Calculamos el inicio y fin de ESE DÍA en la zona horaria local
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            val startOfDay = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
            val endOfDay = cal.timeInMillis

            lifecycleScope.launch {
                binding.progressBarAdvancedHistory.isVisible = true
                try {
                    val lotIds = repository.getLotIdsByTimestampRange(startOfDay, endOfDay)

                    if (lotIds.isEmpty()) {
                        Toast.makeText(context, "No se encontraron lotes para esa fecha.", Toast.LENGTH_SHORT).show()
                    }
                    viewModel.setLotIdsFilter(lotIds.ifEmpty { listOf("no_lot_found_placeholder") })
                } finally {
                    if(isAdded) binding.progressBarAdvancedHistory.isVisible = false
                }
            }
        }

        datePicker.addOnDismissListener {
            if (viewModel.getLotIdsFilter() == null) {
                binding.chipLote.isChecked = false
            }
        }
        datePicker.show(parentFragmentManager, "LOT_DATE_PICKER")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}