package com.cesar.bocana.ui.traspasos.plan

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.FragmentPlanificarTraspasoBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PlanificarTraspasoFragment : Fragment() {

    private var _binding: FragmentPlanificarTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlanificarTraspasoViewModel by viewModels()
    private lateinit var adapter: PlanTraspasoAdapter

    private val dateFormatCompact =
        SimpleDateFormat("EEE dd/MM", Locale("es", "MX"))

    private var selectedDate: Date = Date()
    private var focusedAdapterPosition: Int? = null
    private var keyboardVisible = false
    private var bottomNavWasVisible = true
    private var pendingScrollToNotes = false
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanificarTraspasoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        childFragmentManager.setFragmentResultListener(
            SeleccionarLotesDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val productId =
                bundle.getString(SeleccionarLotesDialogFragment.PRODUCT_ID_KEY)
                    ?: return@setFragmentResultListener

            val desgloseManualList =
                bundle.getParcelableArrayList<DesgloseManualResult>(
                    SeleccionarLotesDialogFragment.RESULT_DESGLOSE_KEY
                )

            val lotesSeleccionados =
                bundle.getParcelableArrayList<StockLot>(
                    SeleccionarLotesDialogFragment.RESULT_LOTES_KEY
                )

            when {
                desgloseManualList != null && desgloseManualList.isNotEmpty() ->
                    viewModel.actualizarPorDesgloseManual(productId, desgloseManualList)

                lotesSeleccionados != null ->
                    viewModel.actualizarLotesManualmente(productId, lotesSeleccionados)
            }
        }

        setupRecyclerView()
        setupListeners()
        setupKeyboardBehavior()
        observeViewModel()
        updateDateButtonText()
        updateAddRowButton(0)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                if (_binding == null) return@collectLatest

                binding.progressBarPlan.isVisible = state.isLoading || state.isSaving
                binding.btnGenerarPdfTop.isEnabled = !state.isSaving

                adapter.submitList(state.sugerencias)

                val noteRows = state.sugerencias
                    .firstOrNull { it.product.id == "FILA_VACIA" }
                    ?.cantidadEditadaUnidades
                    ?: 0

                updateAddRowButton(noteRows)

                if (pendingScrollToNotes && noteRows > 0 && state.sugerencias.isNotEmpty()) {
                    pendingScrollToNotes = false
                    binding.recyclerViewPlanTraspaso.post {
                        binding.recyclerViewPlanTraspaso.smoothScrollToPosition(
                            state.sugerencias.lastIndex
                        )
                    }
                }

                if (state.preguntaCache) {
                    mostrarDialogoDeCache()
                    viewModel.onDialogoMostrado()
                }

                state.error?.let {
                    Snackbar.make(binding.root, "Error: $it", Snackbar.LENGTH_LONG).show()
                }

                state.snackbarMessage?.let { raw ->
                    val message = when (raw) {
                        "Filas vacías actualizadas" -> "Fila agregada"
                        else -> raw
                    }
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                    viewModel.onSnackbarShown()
                }

                if (state.planGuardadoExitoso) {
                    Snackbar.make(
                        binding.root,
                        "Plan enviado a PDFs Recientes",
                        Snackbar.LENGTH_SHORT
                    ).show()

                    val tabLayout =
                        activity?.findViewById<TabLayout>(R.id.tab_layout_traspasos)
                    tabLayout?.getTabAt(1)?.select()

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
                .setMessage(
                    "Se encontró un plan sin terminar de hoy. Continuar conserva tus " +
                        "cantidades y vuelve a leer el stock local, por si empacaste " +
                        "mercancía o cambió el inventario."
                )
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
        adapter = PlanTraspasoAdapter(
            viewModel = viewModel,
            onSeleccionarLotesClick = { item ->
                if (item.product.id != "FILA_VACIA") {
                    val isBulk =
                    item.product.requiresPackaging &&
                        (
                            item.unidadDeEmpaqueEditada == "Kg" ||
                                item.unidadDeEmpaqueEditada.isBlank()
                            )

                val seleccionInicial: java.io.Serializable

                if (item.lotesSeleccionadosManualmente != null) {
                    if (
                        item.lotesParaTraspaso.any { it.cantidadATomarKg > 0 } &&
                        item.lotesParaTraspaso.any { it.lote != null }
                    ) {
                        seleccionInicial = ArrayList(
                            item.lotesParaTraspaso.mapNotNull { desglose ->
                                if (desglose.lote != null) {
                                    val cantidad =
                                        if (isBulk) {
                                            desglose.cantidadATomarKg
                                        } else {
                                            desglose.cantidadATomarUnidades ?: 0.0
                                        }

                                    if (cantidad > 0) {
                                        DesgloseManualResult(
                                            desglose.loteId,
                                            cantidad
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }
                        )
                    } else {
                        seleccionInicial = ArrayList(
                            item.lotesSeleccionadosManualmente
                                ?.map { it.id }
                                ?: emptyList<String>()
                        )
                    }
                } else {
                    seleccionInicial = ArrayList(
                        item.lotesParaTraspaso.map { it.loteId }
                    )
                }

                SeleccionarLotesDialogFragment.newInstance(
                    productId = item.product.id,
                    productName = item.product.name,
                    isBulkProduct = isBulk,
                    selectedIdsOrDesglose = seleccionInicial
                ).show(
                    childFragmentManager,
                    SeleccionarLotesDialogFragment.TAG
                )
                }
            },
            onQuantityFocus = { position ->
                focusedAdapterPosition = position
                ensureCardVisible(position)
            }
        )

        binding.recyclerViewPlanTraspaso.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlanificarTraspasoFragment.adapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
                ?.supportsChangeAnimations = false
        }
    }

    private fun setupListeners() {
        binding.buttonTraspasoDate.setOnClickListener {
            showDatePicker()
        }

        binding.btnGenerarPdfTop.setOnClickListener {
            viewModel.guardarPlanEnFirestore(selectedDate)
        }

        binding.btnRegenerarSugerencias.setOnClickListener {
            adapter.showAllReasonsAgain()
            Snackbar.make(
                binding.root,
                "Regenerando sugerencias…",
                Snackbar.LENGTH_SHORT
            ).show()
            viewModel.regenerarSugerencias()
        }

        binding.btnAgregarFilaVacia.setOnClickListener {
            pendingScrollToNotes = true
            viewModel.agregarFilaVacia(1)
        }
    }

    /**
     * Cuando aparece el teclado:
     * - ocultamos temporalmente la navegación inferior;
     * - conservamos la barra compacta superior;
     * - volvemos a llevar la tarjeta activa a la zona visible.
     *
     * Se usa medición real de la ventana para que también funcione bien en MIUI.
     */
    private fun setupKeyboardBehavior() {
        val bottomNav =
            activity?.findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNavWasVisible = bottomNav?.visibility == View.VISIBLE

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (_binding != null) {
                val visibleFrame = Rect()
                binding.root.getWindowVisibleDisplayFrame(visibleFrame)

                val rootHeight = binding.root.rootView.height

                if (rootHeight > 0) {
                    val hiddenHeight = rootHeight - visibleFrame.bottom
                    val isKeyboardVisible =
                        hiddenHeight > rootHeight * 0.18f

                    if (keyboardVisible != isKeyboardVisible) {
                        keyboardVisible = isKeyboardVisible

                        if (bottomNav != null) {
                            bottomNav.isVisible =
                                if (isKeyboardVisible) {
                                    false
                                } else {
                                    bottomNavWasVisible
                                }
                        }

                        if (isKeyboardVisible) {
                            focusedAdapterPosition?.let { position ->
                                ensureCardVisible(position)
                            }
                        }
                    }
                }
            }
        }

        keyboardLayoutListener = listener
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /**
     * Scroll con offset, y una segunda pasada cuando el IME ya terminó de ocupar espacio.
     * Así el campo editado no queda escondido detrás del teclado.
     */
    private fun ensureCardVisible(position: Int) {
        if (position == RecyclerView.NO_POSITION) return

        val layoutManager =
            binding.recyclerViewPlanTraspaso.layoutManager as? LinearLayoutManager
                ?: return

        val topOffset = dp(6)

        binding.recyclerViewPlanTraspaso.post {
            layoutManager.scrollToPositionWithOffset(position, topOffset)

            binding.recyclerViewPlanTraspaso.postDelayed({
                if (_binding != null) {
                    layoutManager.scrollToPositionWithOffset(position, topOffset)
                }
            }, 180L)
        }
    }

    private fun updateAddRowButton(count: Int) {
        binding.btnAgregarFilaVacia.text = when {
            count <= 0 -> "Fila"
            count == 1 -> "1 Fila"
            else -> "$count Filas"
        }

        binding.btnAgregarFilaVacia.contentDescription =
            when {
                count <= 0 -> "Agregar fila para notas"
                count == 1 -> "1 fila para notas. Toca para agregar otra."
                else -> "$count filas para notas. Toca para agregar otra."
            }
    }

    private fun showDatePicker() {
        val datePicker =
            MaterialDatePicker.Builder.datePicker()
                .setTitleText("Seleccionar fecha")
                .setSelection(
                    selectedDate.time +
                        TimeZone.getDefault().getOffset(selectedDate.time)
                )
                .build()

        datePicker.addOnPositiveButtonClickListener { selectionUtc ->
            val utcCalendar =
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = selectionUtc
                }

            val localCalendar =
                Calendar.getInstance().apply {
                    set(
                        utcCalendar.get(Calendar.YEAR),
                        utcCalendar.get(Calendar.MONTH),
                        utcCalendar.get(Calendar.DAY_OF_MONTH),
                        0,
                        0,
                        0
                    )
                    set(Calendar.MILLISECOND, 0)
                }

            selectedDate = localCalendar.time
            updateDateButtonText()
        }

        datePicker.show(
            parentFragmentManager,
            "DATE_PICKER_TRASPASO"
        )
    }

    private fun updateDateButtonText() {
        binding.buttonTraspasoDate.text =
            dateFormatCompact
                .format(selectedDate)
                .replace(".", "")
                .uppercase(Locale("es", "MX"))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        keyboardLayoutListener?.let { listener ->
            if (binding.root.viewTreeObserver.isAlive) {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        keyboardLayoutListener = null

        activity
            ?.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            ?.isVisible = bottomNavWasVisible

        super.onDestroyView()
        _binding = null
    }
}
