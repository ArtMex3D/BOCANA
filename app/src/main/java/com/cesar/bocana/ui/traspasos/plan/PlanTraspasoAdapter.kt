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
import com.google.android.material.snackbar.Snackbar
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

            // ***** INICIO DE SOLUCIÓN ROBUSTA PARA CHECKBOX *****
            // 1. Desvincula el listener para evitar que se dispare al reciclar.
            binding.checkboxIncludeInPdf.setOnCheckedChangeListener(null)
            // 2. Establece el estado del checkbox basándose SIEMPRE en el dato del item.
            binding.checkboxIncludeInPdf.isChecked = item.incluidoEnPdf
            // 3. Vuelve a vincular el listener para capturar solo las nuevas acciones del usuario.
            binding.checkboxIncludeInPdf.setOnCheckedChangeListener { _, isChecked ->
                // Llama al ViewModel para que actualice el estado en la fuente de datos.
                if (item.incluidoEnPdf != isChecked) {
                    viewModel.actualizarInclusionEnPdf(item.product.id, isChecked)
                }
            }
            // ***** FIN DE SOLUCIÓN ROBUSTA PARA CHECKBOX *****


            binding.textviewProductName.text = item.product.name

            if (!binding.editTextCantidad.isFocused) {
                binding.editTextCantidad.setText(item.cantidadEditadaUnidades.toString())
            }
            binding.textviewUnidadEmpaque.text = item.unidadDeEmpaqueEditada

            binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    v.clearFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else {
                    false
                }
            }

            binding.editTextCantidad.setOnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) {
                    val nuevaCantidadStr = binding.editTextCantidad.text.toString()
                    val nuevaCantidad = nuevaCantidadStr.toIntOrNull() ?: 0

                    if (nuevaCantidad == item.cantidadEditadaUnidades) return@setOnFocusChangeListener

                    val totalUnidadesDisponibles = item.lotesParaTraspaso.sumOf { desglose ->
                        val pesoUnidad = desglose.lote?.pesoPorUnidad ?: 1.0
                        if (pesoUnidad > 0) Math.floor((desglose.lote?.currentQuantity ?: 0.0) / pesoUnidad) else 0.0
                    }.toInt()


                    val cantidadFinal = if (nuevaCantidad > totalUnidadesDisponibles) {
                        Snackbar.make(binding.root, "Stock máximo es $totalUnidadesDisponibles. Cantidad ajustada.", Snackbar.LENGTH_LONG).show()
                        totalUnidadesDisponibles
                    } else {
                        nuevaCantidad
                    }

                    viewModel.recalcularSugerenciaPorUnidades(item.product.id, cantidadFinal)
                }
            }

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

            binding.buttonSeleccionarLotes.setOnClickListener {
                onSeleccionarLotesClick(item)
            }
        }

        private fun addLoteView(context: Context, desglose: LoteDesglosado) {
            val fecha = dateFormat.format(desglose.loteFecha ?: Date())
            val proveedor = desglose.loteProveedor ?: "S/P"

            val cantidadStr = if (desglose.cantidadATomarUnidades != null && !desglose.loteUnidad.isNullOrBlank()) {
                val unidadesEnteras = ceil(desglose.cantidadATomarUnidades).toInt()
                "$unidadesEnteras ${desglose.loteUnidad}"
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
            return oldItem == newItem
        }
    }
}
