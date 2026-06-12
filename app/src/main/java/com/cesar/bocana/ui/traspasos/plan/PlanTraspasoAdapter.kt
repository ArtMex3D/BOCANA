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
        val binding =
            ItemPlanTraspasoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlanViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlanViewHolder(private val binding: ItemPlanTraspasoBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TraspasoSugerenciaItem) {
            val context = binding.root.context
            val isFilaVacia = item.product.id == "FILA_VACIA"

            // 🚀 DESBLOQUEO VISUAL
            val esGranel =
                item.product.requiresPackaging && (item.unidadDeEmpaqueEditada == "Kg" || item.unidadDeEmpaqueEditada.isBlank())

            binding.checkboxIncludeInPdf.setOnCheckedChangeListener(null)
            binding.editTextCantidad.onFocusChangeListener = null
            binding.editTextCantidad.setOnEditorActionListener(null)
            binding.btnDeleteFilaVacia.setOnClickListener(null)

            if (isFilaVacia) {
                val cardView = binding.root as MaterialCardView
                cardView.setCardBackgroundColor(Color.parseColor("#FFF3F3F3"))
                cardView.strokeWidth = 2
                cardView.strokeColor = Color.LTGRAY

                binding.textviewProductName.text = "Eliminar filas"
                binding.textviewProductName.setTextColor(Color.DKGRAY)

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

                binding.btnDeleteFilaVacia.setOnClickListener {
                    viewModel.eliminarFilaVacia()
                }

                binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                        v.clearFocus()
                        val imm =
                            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                        true
                    } else false
                }

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
                val cardView = binding.root as MaterialCardView
                cardView.setCardBackgroundColor(Color.parseColor("#F0EBF5"))
                cardView.strokeWidth = 0

                binding.textviewProductName.text = item.product.name
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
                    val displayQty = if (esGranel) String.format(
                        Locale.getDefault(),
                        "%.2f",
                        item.sugerenciaKg
                    ) else item.cantidadEditadaUnidades.toString()
                    binding.editTextCantidad.setText(displayQty)
                }

                binding.textviewUnidadEmpaque.text =
                    if (esGranel) "Kg" else item.unidadDeEmpaqueEditada

                binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                        v.clearFocus()
                        val imm =
                            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                        true
                    } else false
                }

                binding.editTextCantidad.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !esGranel) {
                        val nuevaCantidad =
                            binding.editTextCantidad.text.toString().toIntOrNull() ?: 0
                        if (nuevaCantidad == item.cantidadEditadaUnidades) return@setOnFocusChangeListener

                        val totalUnidadesDisponibles = item.lotesParaTraspaso.sumOf { desglose ->
                            val pesoUnidad = desglose.lote?.pesoPorUnidad ?: 1.0
                            if (pesoUnidad > 0) Math.floor(
                                (desglose.lote?.currentQuantity ?: 0.0) / pesoUnidad
                            ) else 0.0
                        }.toInt()

                        val cantidadFinal = if (nuevaCantidad > totalUnidadesDisponibles) {
                            Snackbar.make(
                                binding.root,
                                "Stock máximo es $totalUnidadesDisponibles. Cantidad ajustada.",
                                Snackbar.LENGTH_LONG
                            ).show()
                            totalUnidadesDisponibles
                        } else nuevaCantidad

                        viewModel.recalcularSugerenciaPorUnidades(item.product.id, cantidadFinal)
                    }
                }

                binding.miniLoader.isVisible = item.isRecalculating
                binding.layoutCantidad.alpha = if (item.isRecalculating) 0.5f else 1.0f
                binding.editTextCantidad.isEnabled = !item.isRecalculating && !esGranel

                binding.layoutLotesAMover.removeAllViews()
                if (item.lotesParaTraspaso.isNotEmpty()) {
                    val labelText =
                        if (item.lotesSeleccionadosManualmente != null) "Lotes Seleccionados:" else "Lotes a Usar (PEPS):"
                    binding.labelLotesSugeridos.text = labelText
                    item.lotesParaTraspaso.forEach { desglose ->
                        addLoteView(
                            context,
                            desglose,
                            esGranel
                        )
                    }
                } else {
                    binding.labelLotesSugeridos.text = "Lotes a Usar (PEPS):"
                    val noLotesView = TextView(context).apply {
                        text =
                            if (item.sugerenciaKg > 0) "  • No hay lotes con suficiente stock." else "  • No se requiere traspaso."
                        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
                    }
                    binding.layoutLotesAMover.addView(noLotesView)
                }

                binding.textviewImpacto.text =
                    "Impacto: Quedarán ${"%.2f".format(item.impactoStockMatriz)} Kg en Matriz"

                binding.buttonSeleccionarLotes.setOnClickListener {
                    onSeleccionarLotesClick(item)
                }
            }
        }

        private fun addLoteView(context: Context, desglose: LoteDesglosado, esGranel: Boolean) {
            val fecha = dateFormat.format(desglose.loteFecha ?: Date())
            val proveedor = desglose.loteProveedor ?: "S/P"

            val esFijo = desglose.lotePesoPorUnidad != null && desglose.lotePesoPorUnidad > 0.0 && !desglose.loteUnidad.isNullOrBlank() && desglose.loteUnidad != "Kg"

            val cantidadStr = if (esFijo && desglose.cantidadATomarUnidades != null) {
                val unidadesEnteras = ceil(desglose.cantidadATomarUnidades).toInt()
                // FIX NULOS KOTLIN: Uso de ?.trim() ?: ""
                val unidadEscrita = desglose.loteUnidad?.trim() ?: ""

                val unidadPlural = if (unidadesEnteras == 1 || unidadEscrita.isEmpty()) {
                    unidadEscrita
                } else {
                    // FIX NULOS KOTLIN: Obtener char seguro
                    val ultimaLetra = unidadEscrita.lastOrNull()?.lowercaseChar()
                    if (ultimaLetra == 'a' || ultimaLetra == 'e' || ultimaLetra == 'i' || ultimaLetra == 'o' || ultimaLetra == 'u') {
                        "${unidadEscrita}s"
                    } else if (unidadEscrita.lowercase().endsWith("s")) {
                        unidadEscrita
                    } else {
                        "${unidadEscrita}es"
                    }
                }
                "$unidadesEnteras $unidadPlural"
            } else {
                "${String.format(Locale.getDefault(), "%.2f", desglose.cantidadATomarKg)} Kg"
            }

            val loteText = "  • $cantidadStr de ($proveedor - $fecha)"
            val loteView = TextView(context).apply {
                text = loteText
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
            }
            binding.layoutLotesAMover.addView(loteView)
        }
    }

    // CLASE DIFFCALLBACK TOTALMENTE AFUERA DE ADAPTER
    class DiffCallback : DiffUtil.ItemCallback<TraspasoSugerenciaItem>() {
        override fun areItemsTheSame(
            oldItem: TraspasoSugerenciaItem,
            newItem: TraspasoSugerenciaItem
        ): Boolean {
            return oldItem.product.id == newItem.product.id
        }

        override fun areContentsTheSame(
            oldItem: TraspasoSugerenciaItem,
            newItem: TraspasoSugerenciaItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}