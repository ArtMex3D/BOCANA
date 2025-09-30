package com.cesar.bocana.ui.traspasos.plan

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.LoteDesglosado
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.cesar.bocana.databinding.ItemPlanTraspasoBinding
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

class PlanTraspasoAdapter(
    private val viewModel: PlanificarTraspasoViewModel,
    private val onSeleccionarLotesClick: (TraspasoSugerenciaItem) -> Unit
) : ListAdapter<TraspasoSugerenciaItem, PlanTraspasoAdapter.PlanViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        val binding = ItemPlanTraspasoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlanViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlanViewHolder(private val binding: ItemPlanTraspasoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TraspasoSugerenciaItem) {
            val context = binding.root.context

            binding.checkboxIncludeInPdf.isChecked = item.incluidoEnPdf
            binding.textviewProductName.text = item.product.name

            // <-- CAMBIO: Evita actualizar el texto si el usuario lo está editando
            if (!binding.editTextCantidad.isFocused) {
                binding.editTextCantidad.setText(item.cantidadEditadaUnidades.toString())
            }
            binding.textviewUnidadEmpaque.text = item.unidadDeEmpaqueEditada

            // <-- MEJORA: Listener más robusto para confirmar la edición de cantidad
            binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    v.clearFocus() // Quita el foco
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0) // Esconde el teclado
                    // La lógica de recalcular se mueve al onFocusChangeListener para mayor seguridad
                    true
                } else {
                    false
                }
            }

            binding.editTextCantidad.setOnFocusChangeListener { _, hasFocus ->
                // <-- LÓGICA MEJORADA: Se recalcula solo cuando se pierde el foco
                if (!hasFocus) {
                    val nuevaCantidadStr = binding.editTextCantidad.text.toString()
                    val nuevaCantidad = nuevaCantidadStr.toIntOrNull() ?: 0
                    // <-- EVITA RECALCULAR SI NO HAY CAMBIOS: Solo llama al ViewModel si el número es diferente
                    if (nuevaCantidad != item.cantidadEditadaUnidades) {
                        viewModel.recalcularSugerenciaPorUnidades(item.product.id, nuevaCantidad)
                    }
                }
            }

            // <-- NUEVO: Muestra un mini-loader mientras el ViewModel recalcula
            binding.miniLoader.isVisible = item.isRecalculating
            binding.layoutCantidad.alpha = if (item.isRecalculating) 0.5f else 1.0f
            binding.editTextCantidad.isEnabled = !item.isRecalculating

            binding.layoutLotesAMover.removeAllViews()
            if (item.lotesParaTraspaso.isNotEmpty()) {
                val labelText = if (item.lotesSeleccionadosManualmente != null) "Lotes Seleccionados:" else "Lotes a Usar (PEPS):"
                binding.labelLotesSugeridos.text = labelText
                item.lotesParaTraspaso.forEach { desglose ->
                    addLoteView(context, desglose)
                }
            } else {
                binding.labelLotesSugeridos.text = "Lotes a Usar (PEPS):"
                val noLotesView = TextView(context).apply {
                    text = if (item.sugerenciaKg > 0) "  • No hay lotes con suficiente stock." else "  • No se requiere traspaso."
                    setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
                }
                binding.layoutLotesAMover.addView(noLotesView)
            }

            binding.textviewImpacto.text = "Impacto: Quedarán ${"%.2f".format(item.impactoStockMatriz)} Kg en Matriz"

            binding.checkboxIncludeInPdf.setOnCheckedChangeListener { _, isChecked ->
                item.incluidoEnPdf = isChecked
            }
            binding.buttonSeleccionarLotes.setOnClickListener {
                onSeleccionarLotesClick(item)
            }
        }

        private fun addLoteView(context: Context, desglose: LoteDesglosado) {
            val lote = desglose.lote
            val fecha = dateFormat.format(lote.receivedAt ?: Date())
            val proveedor = lote.supplierName ?: "S/P"

            // <-- LÓGICA MEJORADA: Muestra la cantidad en unidades si está disponible, si no, en Kg
            val cantidadStr = if (desglose.cantidadATomarUnidades != null && !lote.unidadDeEmpaque.isNullOrBlank()) {
                val unidadesEnteras = ceil(desglose.cantidadATomarUnidades).toInt()
                "$unidadesEnteras ${lote.unidadDeEmpaque}"
            } else {
                "${"%.2f".format(desglose.cantidadATomarKg)} Kg"
            }

            val loteText = "  • $cantidadStr de ($proveedor - $fecha)"
            val loteView = TextView(context).apply {
                text = loteText
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
            }
            binding.layoutLotesAMover.addView(loteView)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TraspasoSugerenciaItem>() {
        override fun areItemsTheSame(oldItem: TraspasoSugerenciaItem, newItem: TraspasoSugerenciaItem): Boolean {
            return oldItem.product.id == newItem.product.id
        }

        override fun areContentsTheSame(oldItem: TraspasoSugerenciaItem, newItem: TraspasoSugerenciaItem): Boolean {
            // <-- MEJORA: Una comparación más completa para evitar redibujados innecesarios
            return oldItem == newItem
        }
    }
}
