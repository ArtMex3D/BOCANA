package com.cesar.bocana.ui.traspasos.plan

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
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
import com.google.android.material.card.MaterialCardView
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
            val isFilaVacia = item.product.id == "FILA_VACIA"
            val esGranel = item.product.requiresPackaging

            // 1. Limpiar Listeners para evitar bugs de reciclaje
            binding.checkboxIncludeInPdf.setOnCheckedChangeListener(null)
            binding.editTextCantidad.onFocusChangeListener = null
            binding.editTextCantidad.setOnEditorActionListener(null)
            binding.btnDeleteFilaVacia.setOnClickListener(null)

            if (isFilaVacia) {
                // ==========================================
                // DISEÑO CAMALEÓN (FILA VACÍA)
                // ==========================================
                val cardView = binding.root as MaterialCardView
                cardView.setCardBackgroundColor(Color.parseColor("#FFF3F3F3")) // Gris tenue
                cardView.strokeWidth = 2
                cardView.strokeColor = Color.LTGRAY

                binding.textviewProductName.text = "Eliminar filas"
                binding.textviewProductName.setTextColor(Color.DKGRAY)

                // Ocultar elementos innecesarios
                binding.checkboxIncludeInPdf.visibility = View.INVISIBLE
                binding.labelLotesSugeridos.visibility = View.GONE
                binding.layoutLotesAMover.visibility = View.GONE
                binding.buttonSeleccionarLotes.visibility = View.GONE
                binding.labelCantidadMover.visibility = View.GONE
                binding.textviewImpacto.visibility = View.GONE

                binding.textviewUnidadEmpaque.text = "Filas"
                binding.btnDeleteFilaVacia.visibility = View.VISIBLE

                if (!binding.editTextCantidad.isFocused) {
                    binding.editTextCantidad.setText(item.cantidadEditadaUnidades.toString())
                }
                binding.editTextCantidad.isEnabled = true
                binding.layoutCantidad.alpha = 1.0f

                // Evento: Borrar Fila Vacía
                binding.btnDeleteFilaVacia.setOnClickListener {
                    viewModel.eliminarFilaVacia()
                }

                // Evento: Teclado (Enter)
                binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                        v.clearFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                        true
                    } else false
                }

                // Evento: Perder el foco actualiza la cantidad en el ViewModel
                binding.editTextCantidad.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val qty = binding.editTextCantidad.text.toString().toIntOrNull() ?: 1
                        if (qty != item.cantidadEditadaUnidades && qty > 0) {
                            viewModel.actualizarCantidadFilaVacia(qty)
                        } else if (qty <= 0) {
                            viewModel.eliminarFilaVacia()
                        }
                    }
                }

            } else {
                // ==========================================
                // DISEÑO NORMAL (PRODUCTOS REALES)
                // ==========================================
                val cardView = binding.root as MaterialCardView
                // ✨ Rescatamos tu color gris-morado suave Eye-Care
                cardView.setCardBackgroundColor(Color.parseColor("#F0EBF5"))
                cardView.strokeWidth = 0

                binding.textviewProductName.text = item.product.name
                // ✨ Rescatamos tu azul marino profundo para el texto
                binding.textviewProductName.setTextColor(Color.parseColor("#020961"))

                binding.checkboxIncludeInPdf.visibility = View.VISIBLE
                binding.btnDeleteFilaVacia.visibility = View.GONE
                binding.labelLotesSugeridos.visibility = View.VISIBLE
                binding.layoutLotesAMover.visibility = View.VISIBLE
                binding.buttonSeleccionarLotes.visibility = View.VISIBLE
                binding.labelCantidadMover.visibility = View.VISIBLE
                binding.textviewImpacto.visibility = View.VISIBLE

                binding.checkboxIncludeInPdf.isChecked = item.incluidoEnPdf
                binding.checkboxIncludeInPdf.setOnCheckedChangeListener { _, isChecked ->
                    if (item.incluidoEnPdf != isChecked) {
                        viewModel.actualizarInclusionEnPdf(item.product.id, isChecked)
                    }
                }

                if (!binding.editTextCantidad.isFocused) {
                    // Si es granel, mostramos los kilos; si es fijo, las unidades
                    val displayQty = if (esGranel) String.format(Locale.getDefault(), "%.2f", item.sugerenciaKg) else item.cantidadEditadaUnidades.toString()
                    binding.editTextCantidad.setText(displayQty)
                }

                binding.textviewUnidadEmpaque.text = if (esGranel) "Kg" else item.unidadDeEmpaqueEditada

                binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                        v.clearFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                        true
                    } else false
                }

                binding.editTextCantidad.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !esGranel) { // Solo recalculamos por texto si NO es granel
                        val nuevaCantidad = binding.editTextCantidad.text.toString().toIntOrNull() ?: 0
                        if (nuevaCantidad == item.cantidadEditadaUnidades) return@setOnFocusChangeListener

                        val totalUnidadesDisponibles = item.lotesParaTraspaso.sumOf { desglose ->
                            val pesoUnidad = desglose.lote?.pesoPorUnidad ?: 1.0
                            if (pesoUnidad > 0) Math.floor((desglose.lote?.currentQuantity ?: 0.0) / pesoUnidad) else 0.0
                        }.toInt()

                        val cantidadFinal = if (nuevaCantidad > totalUnidadesDisponibles) {
                            Snackbar.make(binding.root, "Stock máximo es $totalUnidadesDisponibles. Cantidad ajustada.", Snackbar.LENGTH_LONG).show()
                            totalUnidadesDisponibles
                        } else nuevaCantidad

                        viewModel.recalcularSugerenciaPorUnidades(item.product.id, cantidadFinal)
                    }
                }

                binding.miniLoader.isVisible = item.isRecalculating
                binding.layoutCantidad.alpha = if (item.isRecalculating) 0.5f else 1.0f
                // ✨ Protegemos la edición directa si es Granel (Kg)
                binding.editTextCantidad.isEnabled = !item.isRecalculating && !esGranel

                binding.layoutLotesAMover.removeAllViews()
                if (item.lotesParaTraspaso.isNotEmpty()) {
                    val labelText = if (item.lotesSeleccionadosManualmente != null) "Lotes Seleccionados:" else "Lotes a Usar (PEPS):"
                    binding.labelLotesSugeridos.text = labelText
                    item.lotesParaTraspaso.forEach { desglose -> addLoteView(context, desglose, esGranel) }
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
        }

        private fun addLoteView(context: Context, desglose: LoteDesglosado, esGranel: Boolean) {
            val fecha = dateFormat.format(desglose.loteFecha ?: Date())
            val proveedor = desglose.loteProveedor ?: "S/P"

            val cantidadStr = if (!esGranel && desglose.cantidadATomarUnidades != null && !desglose.loteUnidad.isNullOrBlank()) {
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