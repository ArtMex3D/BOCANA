package com.cesar.bocana.ui.traspasos.plan

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.ItemLoteCheckboxBinding
import java.text.SimpleDateFormat
import java.util.*

// Interfaz para comunicar los clics al DialogFragment
interface LoteAdapterListener {
    fun onCheckboxToggled(lote: StockLot, isChecked: Boolean)
    fun onManualEditClicked(lote: StockLot)
}

class LoteCheckboxAdapter(
    private val listener: LoteAdapterListener
) : ListAdapter<Pair<StockLot, Int?>, LoteCheckboxAdapter.LoteViewHolder>(LotDiffCallback()) {

    private var isModoDesglose = false
    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
    private var selectedIds = mutableSetOf<String>()

    fun setModoDesglose(isManual: Boolean) {
        if (isModoDesglose != isManual) {
            isModoDesglose = isManual
            notifyDataSetChanged()
        }
    }

    fun setSelectedIds(ids: Set<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteViewHolder {
        val binding = ItemLoteCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LoteViewHolder(private val binding: ItemLoteCheckboxBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Pair<StockLot, Int?>) {
            val (lote, cantidadAsignada) = item

            val fecha = dateFormat.format(lote.receivedAt ?: Date())
            val proveedor = lote.supplierName ?: "S/P"

            val unidad = lote.unidadDeEmpaque ?: "Kg"
            val pesoUnidad = lote.pesoPorUnidad ?: 1.0

            val unidadesActuales = if (pesoUnidad > 0 && lote.unidadDeEmpaque != null) {
                Math.floor(lote.currentQuantity / pesoUnidad).toInt()
            } else {
                null
            }

            if (unidadesActuales != null) {
                binding.textviewLoteInfo.text = "$fecha ($proveedor)"
                binding.textviewLoteDetalle.text = "Disp: $unidadesActuales $unidad (${String.format("%.2f", lote.currentQuantity)} Kg)"
            } else {
                binding.textviewLoteInfo.text = "$fecha ($proveedor)"
                binding.textviewLoteDetalle.text = "Disp: ${String.format("%.2f", lote.currentQuantity)} $unidad"
            }

            binding.checkboxLote.setOnCheckedChangeListener(null)

            if (isModoDesglose) {
                binding.checkboxLote.visibility = View.GONE
                binding.inputLayoutCantidadDesglose.visibility = View.VISIBLE

                // El campo de texto no es editable directamente, solo muestra el valor.
                binding.editTextCantidadDesglose.inputType = InputType.TYPE_NULL
                binding.editTextCantidadDesglose.isFocusable = false
                binding.editTextCantidadDesglose.isClickable = false

                val cantidadTexto = (cantidadAsignada ?: 0).takeIf { it > 0 }?.toString() ?: ""
                binding.editTextCantidadDesglose.setText(cantidadTexto)
                binding.inputLayoutCantidadDesglose.hint = lote.unidadDeEmpaque ?: "Kg"

                // --- ✨ SOLUCIÓN DEFINITIVA PARA CLIC UNIFICADO ---
// Hacemos que los componentes internos no intercepten los clics.
                binding.inputLayoutCantidadDesglose.isClickable = false
                binding.inputLayoutCantidadDesglose.isFocusable = false
                binding.editTextCantidadDesglose.isClickable = false
                binding.editTextCantidadDesglose.isFocusable = false

// Asignamos un único listener a TODA la fila.
                itemView.setOnClickListener {
                    // Si estamos en modo desglose, se ejecuta la acción de editar.
                    if (isModoDesglose) {
                        listener.onManualEditClicked(lote)
                    } else {
                        // Si no, se marca/desmarca el checkbox.
                        binding.checkboxLote.toggle()
                    }
                }
// --- FIN DE LA SOLUCIÓN ---

            } else {
                binding.checkboxLote.visibility = View.VISIBLE
                binding.inputLayoutCantidadDesglose.visibility = View.GONE
                binding.checkboxLote.isChecked = selectedIds.contains(lote.id)
                itemView.setOnClickListener { binding.checkboxLote.toggle() }
                binding.checkboxLote.setOnCheckedChangeListener { _, isChecked ->
                    listener.onCheckboxToggled(lote, isChecked)
                }
            }
        }
    }

    class LotDiffCallback : DiffUtil.ItemCallback<Pair<StockLot, Int?>>() {
        override fun areItemsTheSame(oldItem: Pair<StockLot, Int?>, newItem: Pair<StockLot, Int?>): Boolean {
            return oldItem.first.id == newItem.first.id
        }
        override fun areContentsTheSame(oldItem: Pair<StockLot, Int?>, newItem: Pair<StockLot, Int?>): Boolean {
            return oldItem == newItem
        }
    }
}