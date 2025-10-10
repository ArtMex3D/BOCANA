package com.cesar.bocana.ui.traspasos.confirmar

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.databinding.ItemAjusteFinalProductoBinding
import java.text.SimpleDateFormat
import java.util.*

// Interfaz para comunicar las acciones del usuario al Fragment
interface AjusteFinalAdapterListener {
    fun onCantidadConfirmadaChanged(detalleId: String, nuevaCantidad: Double)
    fun onEditarLotesClicked(item: AjusteFinalItem)
}

class AjusteFinalTraspasoAdapter(
    private val listener: AjusteFinalAdapterListener
) : ListAdapter<AjusteFinalItem, AjusteFinalTraspasoAdapter.DetalleViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetalleViewHolder {
        val binding = ItemAjusteFinalProductoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DetalleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DetalleViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }

    class DetalleViewHolder(private val binding: ItemAjusteFinalProductoBinding) : RecyclerView.ViewHolder(binding.root) {
        private val lotDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

        fun bind(item: AjusteFinalItem, listener: AjusteFinalAdapterListener) {
            binding.editTextCantidadKg.onFocusChangeListener = null
            binding.editTextCantidadKg.setOnEditorActionListener(null)

            val detalle = item.detalleOriginal
            binding.textViewProductName.text = detalle.productName

            val sugerenciaUnidades = if (detalle.sugerenciaUnidades > 0) "${detalle.sugerenciaUnidades} ${detalle.unidadDeEmpaque}" else ""
            binding.textViewSugerencia.text = "Sugerido: $sugerenciaUnidades (${String.format("%.2f", detalle.sugerenciaKg)} Kg)"

            binding.editTextCantidadKg.setText(String.format("%.2f", item.cantidadConfirmadaKg))

            val lotesInfo = item.lotesConfirmados.joinToString("\n") { desglose ->
                val fecha = desglose.loteFecha?.let { lotDateFormat.format(it) } ?: "N/A"
                "• Lote ($fecha): ${String.format("%.2f", desglose.cantidadATomarKg)} Kg"
            }
            binding.textViewLotesSeleccionados.text = if (lotesInfo.isNotEmpty()) lotesInfo else "No hay lotes asignados."

            // Listener para el botón de editar lotes
            binding.buttonSeleccionarLotes.setOnClickListener {
                listener.onEditarLotesClicked(item)
            }

            // Listener para cuando el usuario termina de editar la cantidad
            val onEditDone = {
                val newQty = binding.editTextCantidadKg.text.toString().toDoubleOrNull()
                if (newQty != null && newQty != item.cantidadConfirmadaKg) {
                    listener.onCantidadConfirmadaChanged(detalle.id, newQty)
                }
                binding.editTextCantidadKg.clearFocus()
            }

            binding.editTextCantidadKg.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    onEditDone()
                }
            }
            binding.editTextCantidadKg.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onEditDone()
                    true
                } else {
                    false
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AjusteFinalItem>() {
        override fun areItemsTheSame(oldItem: AjusteFinalItem, newItem: AjusteFinalItem): Boolean {
            return oldItem.detalleOriginal.id == newItem.detalleOriginal.id
        }

        override fun areContentsTheSame(oldItem: AjusteFinalItem, newItem: AjusteFinalItem): Boolean {
            return oldItem == newItem
        }
    }
}
