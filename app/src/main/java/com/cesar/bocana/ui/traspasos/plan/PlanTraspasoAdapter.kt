// main/java/com/cesar/bocana/ui/traspasos/plan/PlanTraspasoAdapter.kt
package com.cesar.bocana.ui.traspasos.plan

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.ViewModel
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
    private val empaquesPdf = listOf("Cajas", "Costales", "Bolsas", "Piezas", "Atados", "Otros")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        val binding = ItemPlanTraspasoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val empaqueAdapter = ArrayAdapter(parent.context, android.R.layout.simple_dropdown_item_1line, empaquesPdf)
        return PlanViewHolder(binding, empaqueAdapter)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlanViewHolder(
        private val binding: ItemPlanTraspasoBinding,
        private val empaquePdfAdapter: ArrayAdapter<String>
    ) : RecyclerView.ViewHolder(binding.root) {

        private var pdfUnidadesWatcher: TextWatcher? = null
        private var pdfEmpaqueWatcher: TextWatcher? = null

        fun bind(item: TraspasoSugerenciaItem) {
            val context = binding.root.context
            val product = item.product
            val esGranel = product.requiresPackaging

            // Remove listeners to avoid unwanted triggers during re-binding
            binding.checkboxIncludeInPdf.setOnCheckedChangeListener(null)
            binding.editTextCantidad.onFocusChangeListener = null
            binding.editTextCantidad.setOnEditorActionListener(null)
            binding.editTextUnidadesPdf.removeTextChangedListener(pdfUnidadesWatcher)
            binding.autoCompleteEmpaquePdf.removeTextChangedListener(pdfEmpaqueWatcher)
            binding.autoCompleteEmpaquePdf.onItemClickListener = null

            binding.checkboxIncludeInPdf.isChecked = item.incluidoEnPdf
            binding.checkboxIncludeInPdf.setOnCheckedChangeListener { _, isChecked ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    val currentItem = getItem(currentPosition)
                    if (currentItem.product.name != "FILA_VACIA" && currentItem.incluidoEnPdf != isChecked) {
                        viewModel.actualizarInclusionEnPdf(currentItem.product.id, isChecked)
                    }
                }
            }

            binding.textviewProductName.text = product.name
            binding.iconSugerenciaEspecial.isVisible = item.isSugerenciaLiquidacion
            if (item.isSugerenciaLiquidacion) {
                binding.iconSugerenciaEspecial.setOnClickListener {
                    Snackbar.make(binding.root, "Sugerencia: Se añadieron KGs para liquidar un lote próximo a agotarse.", Snackbar.LENGTH_LONG).show()
                }
            } else {
                binding.iconSugerenciaEspecial.setOnClickListener(null)
            }

            if (esGranel) {
                // MODO GRANEL (KG)
                binding.textInputLayoutCantidad.hint = "KG Sugeridos"
                binding.editTextCantidad.setText(String.format(Locale.getDefault(), "%.2f", item.sugerenciaKg))
                binding.editTextCantidad.inputType = InputType.TYPE_NULL
                binding.editTextCantidad.isFocusable = false
                binding.textviewUnidadEmpaque.text = "Kg"

                binding.layoutGranelPdfInfo.isVisible = true
                binding.editTextUnidadesPdf.setText(if (item.cantidadEditadaUnidades > 0) item.cantidadEditadaUnidades.toString() else "")
                binding.autoCompleteEmpaquePdf.setAdapter(empaquePdfAdapter)
                binding.autoCompleteEmpaquePdf.setText(item.unidadDeEmpaqueEditada.takeIf { it != "Kg" } ?: "", false)

                pdfUnidadesWatcher = object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION) {
                            getItem(currentPosition).cantidadEditadaUnidades = s.toString().toIntOrNull() ?: 0
                            viewModel.actualizarCacheConItemModificado(getItem(currentPosition))
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                }
                binding.editTextUnidadesPdf.addTextChangedListener(pdfUnidadesWatcher)

                pdfEmpaqueWatcher = object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION) {
                            getItem(currentPosition).unidadDeEmpaqueEditada = s.toString().trim()
                            viewModel.actualizarCacheConItemModificado(getItem(currentPosition))
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                }
                binding.autoCompleteEmpaquePdf.addTextChangedListener(pdfEmpaqueWatcher)

                binding.autoCompleteEmpaquePdf.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        val selectedEmpaque = empaquePdfAdapter.getItem(position) ?: ""
                        getItem(currentPosition).unidadDeEmpaqueEditada = selectedEmpaque
                        viewModel.actualizarCacheConItemModificado(getItem(currentPosition))
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(binding.autoCompleteEmpaquePdf.windowToken, 0)
                        binding.autoCompleteEmpaquePdf.clearFocus()
                    }
                }
            } else {
                // MODO FIJO (Unidades)
                binding.textInputLayoutCantidad.hint = "Unidades Sugeridas"
                val currentText = binding.editTextCantidad.text.toString()
                val newText = item.cantidadEditadaUnidades.toString()
                if (currentText != newText) {
                    binding.editTextCantidad.setText(newText)
                }
                binding.editTextCantidad.inputType = InputType.TYPE_CLASS_NUMBER
                binding.editTextCantidad.isFocusableInTouchMode = true
                binding.editTextCantidad.isFocusable = true
                binding.textviewUnidadEmpaque.text = item.unidadDeEmpaqueEditada

                binding.layoutGranelPdfInfo.isVisible = false

                binding.editTextCantidad.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                    if (!hasFocus) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION) {
                            val currentItem = getItem(currentPosition)
                            val nuevaCantidadStr = binding.editTextCantidad.text.toString()
                            val nuevaCantidad = nuevaCantidadStr.toIntOrNull() ?: 0
                            if (nuevaCantidad != currentItem.cantidadEditadaUnidades && !currentItem.product.requiresPackaging) {
                                Log.d("Adapter", "Focus perdido en Fijo ${currentItem.product.name}. Nueva Cant: $nuevaCantidad, Anterior: ${currentItem.cantidadEditadaUnidades}. Llamando a ViewModel.")
                                viewModel.recalcularSugerenciaPorUnidades(currentItem.product.id, nuevaCantidad)
                            }
                        }
                    }
                }

                // **SOLUCIÓN**: Se añade el listener para la tecla "Enter" o "Done" del teclado.
                binding.editTextCantidad.setOnEditorActionListener { v, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                        v.clearFocus() // Quita el foco, lo que dispara el onFocusChangeListener
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0) // Oculta el teclado
                        true // Indica que el evento fue manejado
                    } else {
                        false
                    }
                }
            }

            binding.miniLoader.isVisible = item.isRecalculating
            val alphaValue = if (item.isRecalculating) 0.5f else 1.0f
            binding.layoutCantidad.alpha = alphaValue
            binding.layoutGranelPdfInfo.alpha = alphaValue
            binding.editTextCantidad.isEnabled = !item.isRecalculating && !esGranel
            binding.editTextUnidadesPdf.isEnabled = !item.isRecalculating
            binding.autoCompleteEmpaquePdf.isEnabled = !item.isRecalculating

            binding.layoutLotesAMover.removeAllViews()
            if (item.lotesParaTraspaso.isNotEmpty()) {
                val labelText = if (item.lotesSeleccionadosManualmente != null) "Lotes Seleccionados:" else "Lotes Sugeridos (FIFO):"
                binding.labelLotesSugeridos.text = labelText
                item.lotesParaTraspaso.forEach { desglose ->
                    addLoteView(context, desglose, esGranel)
                }
            } else {
                binding.labelLotesSugeridos.text = "Lotes Sugeridos (FIFO):"
                val noLotesView = TextView(context).apply {
                    text = if (item.sugerenciaKg > 0) "  • No hay lotes libres con stock." else "  • No se requiere traspaso."
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                }
                binding.layoutLotesAMover.addView(noLotesView)
            }

            binding.textviewImpacto.text = "Impacto: Quedarán ${String.format(Locale.getDefault(),"%.2f", item.impactoStockMatriz)} Kg en Matriz"
            binding.buttonSeleccionarLotes.setOnClickListener {
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    onSeleccionarLotesClick(getItem(currentPosition))
                }
            }
            binding.buttonSeleccionarLotes.visibility = if (item.product.name == "FILA_VACIA" || item.isRecalculating) View.GONE else View.VISIBLE
            binding.iconSugerenciaEspecial.visibility = if (item.product.name == "FILA_VACIA") View.GONE else if (item.isSugerenciaLiquidacion) View.VISIBLE else View.GONE

            if (item.product.name == "FILA_VACIA") {
                binding.checkboxIncludeInPdf.isEnabled = false
                binding.editTextCantidad.isEnabled = false
                binding.layoutGranelPdfInfo.isVisible = false
                binding.labelLotesSugeridos.visibility = View.GONE
                binding.layoutLotesAMover.visibility = View.GONE
                binding.textviewImpacto.visibility = View.GONE
                binding.labelCantidadMover.visibility = View.GONE
                binding.layoutCantidad.visibility = View.GONE
                binding.textviewProductName.text = "--- Fila Vacía para Notas ---"
                TextViewCompat.setTextAppearance(binding.textviewProductName, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                binding.textviewProductName.textAlignment = View.TEXT_ALIGNMENT_CENTER
            } else {
                binding.checkboxIncludeInPdf.isEnabled = true
                binding.labelLotesSugeridos.visibility = View.VISIBLE
                binding.layoutLotesAMover.visibility = View.VISIBLE
                binding.textviewImpacto.visibility = View.VISIBLE
                binding.labelCantidadMover.visibility = View.VISIBLE
                binding.layoutCantidad.visibility = View.VISIBLE
                TextViewCompat.setTextAppearance(binding.textviewProductName, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                binding.textviewProductName.setTypeface(null, android.graphics.Typeface.BOLD)
                binding.textviewProductName.textAlignment = View.TEXT_ALIGNMENT_INHERIT
            }
        }

        private fun addLoteView(context: Context, desglose: LoteDesglosado, esGranel: Boolean) {
            val fecha = desglose.loteFecha?.let { dateFormat.format(it) } ?: "N/A"
            val proveedor = desglose.loteProveedor ?: "S/P"

            val cantidadStr = if (!esGranel && desglose.cantidadATomarUnidades != null && !desglose.loteUnidad.isNullOrBlank()) {
                val unidadesEnteras = ceil(desglose.cantidadATomarUnidades).toInt()
                "$unidadesEnteras ${desglose.loteUnidad}"
            } else {
                "${String.format(Locale.getDefault(),"%.2f", desglose.cantidadATomarKg)} Kg"
            }

            val loteText = "  • $cantidadStr de ($proveedor - $fecha)"
            val loteView = TextView(context).apply {
                text = loteText
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            }
            binding.layoutLotesAMover.addView(loteView)
        }
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

/**
 * Extension function to update the cache from the adapter.
 * This should be a top-level function.
 */
fun ViewModel.actualizarCacheConItemModificado(itemModificado: TraspasoSugerenciaItem) {
    if (this is PlanificarTraspasoViewModel) {
        val currentState = this.uiState.value
        val newList = currentState.sugerencias.map {
            if (it.product.id == itemModificado.product.id) itemModificado else it
        }
        this.guardarEnCache(newList)
    }
}
