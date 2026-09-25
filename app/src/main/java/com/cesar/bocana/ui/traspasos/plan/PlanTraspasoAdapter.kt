package com.cesar.bocana.ui.traspasos.plan

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.LoteDesglosado
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.cesar.bocana.databinding.ItemPlanTraspasoBinding
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class PlanTraspasoAdapter(
    private val viewModel: PlanificarTraspasoViewModel,
    private val onSeleccionarLotesClick: (TraspasoSugerenciaItem) -> Unit,
    private val onQuantityFocus: (position: Int) -> Unit
) : ListAdapter<TraspasoSugerenciaItem, PlanTraspasoAdapter.PlanViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    /**
     * null = al regenerar se permiten ver las razones originales.
     * Al tocar/editar una tarjeta, sólo queda visible la explicación de esa tarjeta.
     */
    private var activeReasonProductId: String? = null

    /**
     * Las tarjetas cuya sugerencia es cero arrancan compactas.
     * Si el usuario decide capturar una cantidad manual, se expanden sin alterar la lógica.
     */
    private val expandedZeroProductIds = mutableSetOf<String>()
    private var pendingFocusProductId: String? = null

    fun showAllReasonsAgain() {
        activeReasonProductId = null
        expandedZeroProductIds.clear()
        pendingFocusProductId = null
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        return PlanViewHolder(
            ItemPlanTraspasoBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Oculta las razones de las otras tarjetas sin volver a bindear la tarjeta que
     * acaba de tomar foco. Esto evita que el EditText pierda el foco/teclado.
     */
    private fun focusReason(productId: String) {
        if (activeReasonProductId == productId) return

        activeReasonProductId = productId

        for (index in 0 until itemCount) {
            if (getItem(index).product.id != productId) {
                notifyItemChanged(index)
            }
        }
    }

    inner class PlanViewHolder(
        private val binding: ItemPlanTraspasoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TraspasoSugerenciaItem) {
            val context = binding.root.context
            val isEmptyRow = item.product.id == "FILA_VACIA"

            clearListeners()

            if (isEmptyRow) {
                bindEmptyRow(item, context)
                return
            }

            bindProduct(item, context)
        }

        private fun clearListeners() {
            binding.checkboxIncludeInPdf.setOnCheckedChangeListener(null)
            binding.editTextCantidad.onFocusChangeListener = null
            binding.editTextCantidad.setOnEditorActionListener(null)
            binding.btnDeleteFilaVacia.setOnClickListener(null)
            binding.buttonSeleccionarLotes.setOnClickListener(null)
            binding.root.setOnClickListener(null)
        }

        private fun bindEmptyRow(
            item: TraspasoSugerenciaItem,
            context: Context
        ) {
            val card = binding.root as MaterialCardView
            card.setCardBackgroundColor(Color.parseColor("#F3F4F6"))
            card.strokeWidth = dp(context, 1)
            card.strokeColor = Color.parseColor("#D1D5DB")

            val count = item.cantidadEditadaUnidades.coerceAtLeast(1)

            binding.layoutGroupSummary.isVisible = false
            binding.textviewProductName.text =
                if (count == 1) {
                    "1 fila para notas"
                } else {
                    "$count filas para notas"
                }
            binding.textviewProductName.setTextColor(Color.parseColor("#4B5563"))

            binding.checkboxIncludeInPdf.isVisible = false
            binding.textV3Reason.isVisible = false
            binding.textPackagingHint.isVisible = false
            binding.labelLotesSugeridos.isVisible = false
            binding.layoutLotesAMover.isVisible = false
            binding.buttonSeleccionarLotes.isVisible = false
            binding.labelCantidadMover.isVisible = false
            binding.layoutCantidad.isVisible = false
            binding.textviewImpacto.isVisible = false
            binding.miniLoader.isVisible = false

            binding.btnDeleteFilaVacia.apply {
                isVisible = true
                text = "✕"
                contentDescription = "Eliminar filas para notas"
                setTextColor(Color.parseColor("#8B1E1E"))
            }

            binding.btnDeleteFilaVacia.setOnClickListener {
                viewModel.eliminarFilaVacia()
            }
        }

        private fun bindProduct(
            item: TraspasoSugerenciaItem,
            context: Context
        ) {
            val card = binding.root as MaterialCardView
            card.setCardBackgroundColor(Color.parseColor("#F8F7FC"))
            card.strokeWidth = dp(context, 1)
            card.strokeColor = if (item.v3ManualOverride) {
                Color.parseColor("#B8C3F2")
            } else {
                Color.parseColor("#E2E4F0")
            }

            val isBulk = item.unidadDeEmpaqueEditada
                .trim()
                .equals("Kg", ignoreCase = true)

            val hasTransferDecision = hasTransferDecision(item)
            val showDetails =
                hasTransferDecision ||
                    expandedZeroProductIds.contains(item.product.id)

            bindGroupHeader(item)
            bindReason(item)
            bindPackagingHint(item)

            binding.textviewProductName.text =
                if (item.v3IsGroupPrimary) {
                    "${item.product.name} · rector"
                } else {
                    item.product.name
                }
            binding.textviewProductName.setTextColor(Color.parseColor("#020961"))

            binding.checkboxIncludeInPdf.apply {
                isVisible = true
                text = "PDF"
                isChecked = item.incluidoEnPdf
            }

            binding.checkboxIncludeInPdf.setOnCheckedChangeListener { _, checked ->
                if (checked != item.incluidoEnPdf) {
                    showReasonLocally(item)
                    focusReason(item.product.id)
                    viewModel.actualizarInclusionEnPdf(item.product.id, checked)
                }
            }

            binding.btnDeleteFilaVacia.isVisible = false

            setDetailsVisible(showDetails)

            if (showDetails) {
                bindQuantity(item, context, isBulk)
                bindLots(item, context, isBulk)
                bindImpact(item)
            }

            binding.buttonSeleccionarLotes.setOnClickListener {
                showReasonLocally(item)
                focusReason(item.product.id)
                onSeleccionarLotesClick(item)
            }

            binding.root.setOnClickListener {
                showReasonLocally(item)
                focusReason(item.product.id)

                if (!showDetails) {
                    expandedZeroProductIds += item.product.id
                    pendingFocusProductId = item.product.id

                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position)
                    }
                }
            }

            if (
                showDetails &&
                pendingFocusProductId == item.product.id
            ) {
                pendingFocusProductId = null

                binding.editTextCantidad.post {
                    if (bindingAdapterPosition == RecyclerView.NO_POSITION) {
                        return@post
                    }

                    binding.editTextCantidad.requestFocus()
                    prepareEditorForTyping(binding.editTextCantidad)

                    onQuantityFocus(bindingAdapterPosition)
                    showKeyboard(context, binding.editTextCantidad)
                }
            }
        }

        private fun setDetailsVisible(visible: Boolean) {
            binding.labelLotesSugeridos.isVisible = visible
            binding.layoutLotesAMover.isVisible = visible
            binding.buttonSeleccionarLotes.isVisible = visible
            binding.labelCantidadMover.isVisible = visible
            binding.layoutCantidad.isVisible = visible
            binding.textviewImpacto.isVisible = visible
        }

        private fun bindGroupHeader(item: TraspasoSugerenciaItem) {
            binding.layoutGroupSummary.isVisible = item.v3ShowGroupHeader

            if (item.v3ShowGroupHeader) {
                binding.textGroupName.text =
                    item.v3GroupName
                        ?.uppercase(Locale.getDefault())
                        ?: "GRUPO"

                val habitual =
                    item.v3GroupHabitualTargetKg ?: 0.0
                val dynamic =
                    item.v3GroupDynamicTargetKg ?: 0.0

                binding.textGroupTargets.text = when {
                    habitual > 0.0 ->
                        "Objetivo habitual ${format1(habitual)} kg · " +
                            "sugerido hoy ${format1(dynamic)} kg"

                    else ->
                        "Sugerido hoy: ${format1(dynamic)} kg"
                }
            }
        }

        private fun bindReason(item: TraspasoSugerenciaItem) {
            val text = item.v3ReasonText
                ?.takeIf { it.isNotBlank() }
                ?.let(::cleanVisibleReason)

            val shouldShow =
                text != null &&
                    (
                        activeReasonProductId == null ||
                            activeReasonProductId == item.product.id
                        )

            binding.textV3Reason.isVisible = shouldShow

            if (shouldShow) {
                binding.textV3Reason.text = "💡 Sugerencia: $text"
            }
        }

        private fun showReasonLocally(item: TraspasoSugerenciaItem) {
            val text = item.v3ReasonText
                ?.takeIf { it.isNotBlank() }
                ?.let(::cleanVisibleReason)

            binding.textV3Reason.isVisible = text != null

            if (text != null) {
                binding.textV3Reason.text = "💡 Sugerencia: $text"
            }
        }

        /**
         * V3 es el motor interno; el usuario sólo necesita la explicación.
         */
        private fun cleanVisibleReason(raw: String): String {
            val trimmed = raw.trim()

            if (trimmed.startsWith("V3 sugería 0", ignoreCase = true)) {
                val afterFirstSentence =
                    trimmed.substringAfter(". ", missingDelimiterValue = "")
                if (afterFirstSentence.isNotBlank()) {
                    return afterFirstSentence
                }
            }

            return trimmed
                .replace(
                    "Cantidad ajustada por ti; V3 recalculó el resto sin cambiar esta decisión.",
                    "Cantidad modificada. Se conserva tu decisión.",
                    ignoreCase = false
                )
                .replace("V3 sugería ", "Sugerencia original: ", ignoreCase = false)
                .replace("V3 recalculó", "se recalculó", ignoreCase = false)
                .replace("Objetivo V3 de hoy", "Sugerido hoy", ignoreCase = false)
        }

        private fun bindPackagingHint(item: TraspasoSugerenciaItem) {
            val pending = item.v3PendingPackagingKg
            val requestedUnits = item.cantidadSolicitadaUnidades

            val shortage = if (requestedUnits != null) {
                requestedUnits > item.cantidadEditadaUnidades
            } else {
                item.v3RequestedKg > item.sugerenciaKg + 0.10
            }

            binding.textPackagingHint.isVisible =
                shortage && pending > 0.10

            if (binding.textPackagingHint.isVisible) {
                binding.textPackagingHint.text =
                    "📦 Hay ${format1(pending)} kg pendientes de empacar. " +
                        "Empaca lo necesario para completar el traspaso."
            }
        }

        private fun bindQuantity(
            item: TraspasoSugerenciaItem,
            context: Context,
            isBulk: Boolean
        ) {
            binding.miniLoader.isVisible = item.isRecalculating
            binding.layoutCantidad.alpha =
                if (item.isRecalculating) 0.5f else 1f
            binding.editTextCantidad.isEnabled = !item.isRecalculating

            val displayText = if (isBulk) {
                val requested = item.v3RequestedKg
                    .takeIf { it > 0.0 }
                    ?: item.sugerenciaKg

                String.format(
                    Locale.getDefault(),
                    "%.2f",
                    requested
                )
            } else {
                val requestedUnits =
                    item.cantidadSolicitadaUnidades
                        ?: item.cantidadEditadaUnidades

                requestedUnits.toString()
            }

            if (!binding.editTextCantidad.isFocused) {
                binding.editTextCantidad.setText(displayText)
            }

            binding.textviewUnidadEmpaque.text =
                if (isBulk) {
                    "Kg"
                } else {
                    item.unidadDeEmpaqueEditada
                        .ifBlank { item.product.unit }
                }

            binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                if (
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (
                        event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN
                        )
                ) {
                    v.clearFocus()
                    hideKeyboard(context, v)
                    true
                } else {
                    false
                }
            }

            binding.editTextCantidad.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showReasonLocally(item)
                    focusReason(item.product.id)
                    prepareEditorForTyping(binding.editTextCantidad)

                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onQuantityFocus(position)
                    }

                    return@setOnFocusChangeListener
                }

                if (isBulk) {
                    val newKg =
                        binding.editTextCantidad.text
                            ?.toString()
                            ?.replace(',', '.')
                            ?.toDoubleOrNull()
                            ?: 0.0

                    if (
                        kotlin.math.abs(
                            newKg - item.v3RequestedKg
                        ) > 0.01
                    ) {
                        viewModel.recalcularSugerenciaPorKg(
                            productId = item.product.id,
                            cantidadKg = newKg
                        )
                    }
                } else {
                    val newUnits =
                        binding.editTextCantidad.text
                            ?.toString()
                            ?.toIntOrNull()
                            ?: 0

                    val previous =
                        item.cantidadSolicitadaUnidades
                            ?: item.cantidadEditadaUnidades

                    if (newUnits != previous) {
                        viewModel.recalcularSugerenciaPorUnidades(
                            productId = item.product.id,
                            cantidadEnUnidades = newUnits
                        )
                    }
                }
            }
        }

        /**
         * Si el valor visible es cero, el campo queda vacío al tocarlo.
         * Si ya hay cantidad, conserva el número y pone el cursor al final.
         */
        private fun prepareEditorForTyping(view: android.widget.EditText) {
            val raw = view.text?.toString().orEmpty().trim()
            val numeric = raw.replace(',', '.').toDoubleOrNull()

            if (numeric != null && kotlin.math.abs(numeric) < 0.000001) {
                view.setText("")
            } else {
                view.setSelection(view.text?.length ?: 0)
            }
        }

        private fun bindLots(
            item: TraspasoSugerenciaItem,
            context: Context,
            isBulk: Boolean
        ) {
            binding.layoutLotesAMover.removeAllViews()

            if (item.lotesParaTraspaso.isEmpty()) {
                binding.labelLotesSugeridos.text = "Lotes sugeridos"

                binding.layoutLotesAMover.addView(
                    TextView(context).apply {
                        text =
                            if (item.v3RequestedKg > 0.01) {
                                "Sin mercancía empacada suficiente para esta sugerencia."
                            } else {
                                "No se requiere traspaso."
                            }

                        textSize = 11.5f
                        setTextColor(Color.parseColor("#7A8190"))
                        setPadding(
                            dp(context, 4),
                            dp(context, 6),
                            dp(context, 4),
                            dp(context, 6)
                        )
                    }
                )
                return
            }

            binding.labelLotesSugeridos.text =
                if (item.lotesSeleccionadosManualmente != null) {
                    "Lotes seleccionados manualmente"
                } else {
                    "Lotes sugeridos"
                }

            item.lotesParaTraspaso.forEach { breakdown ->
                addLotCard(
                    context = context,
                    breakdown = breakdown,
                    isBulk = isBulk
                )
            }
        }

        private fun addLotCard(
            context: Context,
            breakdown: LoteDesglosado,
            isBulk: Boolean
        ) {
            val lot = breakdown.lote

            val date =
                dateFormat.format(
                    breakdown.loteFecha
                        ?: lot?.originalReceivedAt
                        ?: lot?.receivedAt
                        ?: Date()
                )

            val supplier =
                breakdown.loteProveedor
                    ?: lot?.supplierName
                    ?: lot?.originalSupplierName
                    ?: "S/P"

            val card = MaterialCardView(context).apply {
                radius = dp(context, 10).toFloat()
                cardElevation = 0f
                strokeWidth = dp(context, 1)
                strokeColor = Color.parseColor("#E5E7EB")
                setCardBackgroundColor(Color.parseColor("#FCFCFF"))

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(context, 5)
                    }
            }

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(context, 10),
                    dp(context, 8),
                    dp(context, 10),
                    dp(context, 8)
                )
            }

            body.addView(
                TextView(context).apply {
                    text = "$supplier · $date"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#30384A"))
                    setTypeface(typeface, Typeface.BOLD)
                }
            )

            val takeText =
                if (
                    breakdown.cantidadATomarUnidades != null &&
                    !breakdown.loteUnidad.isNullOrBlank() &&
                    !isBulk
                ) {
                    val units =
                        ceil(
                            breakdown.cantidadATomarUnidades
                        ).toInt()

                    "$units ${
                        pluralUnit(
                            breakdown.loteUnidad ?: "",
                            units
                        )
                    }"
                } else {
                    "${format2(breakdown.cantidadATomarKg)} kg"
                }

            val availableText =
                if (
                    lot != null &&
                    !isBulk &&
                    (lot.pesoPorUnidad ?: 0.0) > 0.0
                ) {
                    val units =
                        floor(
                            lot.currentQuantity /
                                (lot.pesoPorUnidad ?: 1.0)
                        ).toInt()

                    "$units ${
                        pluralUnit(
                            lot.unidadDeEmpaque ?: "",
                            units
                        )
                    } disponibles"
                } else {
                    "${format2(
                        lot?.currentQuantity
                            ?: breakdown.cantidadATomarKg
                    )} kg disponibles"
                }

            body.addView(
                TextView(context).apply {
                    text = "Sacar $takeText · $availableText"
                    textSize = 10.8f
                    setTextColor(Color.parseColor("#697184"))
                    setPadding(
                        0,
                        dp(context, 2),
                        0,
                        0
                    )
                }
            )

            card.addView(body)
            binding.layoutLotesAMover.addView(card)
        }

        private fun bindImpact(item: TraspasoSugerenciaItem) {
            val requestedUnits =
                item.cantidadSolicitadaUnidades

            when {
                requestedUnits != null &&
                    requestedUnits > item.cantidadEditadaUnidades -> {

                    val missing =
                        requestedUnits - item.cantidadEditadaUnidades

                    binding.textviewImpacto.setTextColor(
                        Color.parseColor("#9A6700")
                    )

                    binding.textviewImpacto.text =
                        "Disponibles ${item.cantidadEditadaUnidades}; " +
                            "faltan $missing ${
                                pluralUnit(
                                    item.unidadDeEmpaqueEditada,
                                    missing
                                )
                            }."
                }

                requestedUnits == null &&
                    item.v3RequestedKg > item.sugerenciaKg + 0.10 -> {

                    binding.textviewImpacto.setTextColor(
                        Color.parseColor("#9A6700")
                    )

                    binding.textviewImpacto.text =
                        "Empacado disponible ${format1(item.v3AvailablePackagedKg)} kg · " +
                            "solicitado ${format1(item.v3RequestedKg)} kg."
                }

                else -> {
                    binding.textviewImpacto.setTextColor(
                        Color.parseColor("#3F6B49")
                    )

                    binding.textviewImpacto.text =
                        "Quedarán ${format2(item.impactoStockMatriz)} kg en Matriz"
                }
            }
        }

        private fun hasTransferDecision(item: TraspasoSugerenciaItem): Boolean {
            val units =
                item.cantidadSolicitadaUnidades
                    ?: item.cantidadEditadaUnidades

            // Si existe necesidad matemática pero hoy no hay nada físicamente transferible,
            // la tarjeta arranca compacta. La razón y el aviso de empaque siguen visibles y
            // el usuario puede tocar la tarjeta para expandirla y capturar una decisión manual.
            return item.v3ManualOverride ||
                item.sugerenciaKg > 0.01 ||
                units > 0
        }

        private fun showKeyboard(context: Context, view: View) {
            val imm =
                context.getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager

            imm.showSoftInput(
                view,
                InputMethodManager.SHOW_IMPLICIT
            )
        }

        private fun hideKeyboard(context: Context, view: View) {
            val imm =
                context.getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager

            imm.hideSoftInputFromWindow(
                view.windowToken,
                0
            )
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    class DiffCallback : DiffUtil.ItemCallback<TraspasoSugerenciaItem>() {
        override fun areItemsTheSame(
            oldItem: TraspasoSugerenciaItem,
            newItem: TraspasoSugerenciaItem
        ): Boolean =
            oldItem.product.id == newItem.product.id

        override fun areContentsTheSame(
            oldItem: TraspasoSugerenciaItem,
            newItem: TraspasoSugerenciaItem
        ): Boolean =
            oldItem == newItem
    }

    companion object {
        private fun format1(value: Double): String =
            String.format(
                Locale.getDefault(),
                "%.1f",
                value
            )

        private fun format2(value: Double): String =
            String.format(
                Locale.getDefault(),
                "%.2f",
                value
            )

        private fun pluralUnit(
            unit: String,
            amount: Int
        ): String {
            val clean =
                unit.trim()
                    .ifBlank { "unidades" }

            if (amount == 1) {
                return clean
            }

            return when {
                clean.endsWith(
                    "s",
                    ignoreCase = true
                ) -> clean

                clean.lastOrNull()
                    ?.lowercaseChar() in
                    listOf(
                        'a',
                        'e',
                        'i',
                        'o',
                        'u'
                    ) ->
                    "${clean}s"

                else ->
                    "${clean}es"
            }
        }
    }
}
