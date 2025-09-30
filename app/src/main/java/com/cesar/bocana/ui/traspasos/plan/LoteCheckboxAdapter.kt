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
            val cantUnidades = lote.cantidadInicialUnidades?.let { String.format("%.0f", it) } ?: String.format("%.2f", lote.initialQuantity)
            binding.textviewLoteInfo.text = "$fecha ($proveedor) - $cantUnidades $unidad"
            binding.textviewLoteDetalle.text = "Disp: ${String.format("%.2f", lote.currentQuantity)} Kg"

            binding.checkboxLote.setOnCheckedChangeListener(null)

            if (isModoDesglose) {
                binding.checkboxLote.visibility = View.GONE
                binding.inputLayoutCantidadDesglose.visibility = View.VISIBLE
                binding.editTextCantidadDesglose.inputType = InputType.TYPE_NULL
                binding.editTextCantidadDesglose.isFocusable = false
                binding.editTextCantidadDesglose.isClickable = false

                val cantidadTexto = (cantidadAsignada ?: 0).takeIf { it > 0 }?.toString() ?: ""
                binding.editTextCantidadDesglose.setText(cantidadTexto)
                binding.inputLayoutCantidadDesglose.hint = lote.unidadDeEmpaque ?: "Kg"

                // <-- CORRECCIÓN DE UX: El listener está en 'itemView', que es toda la fila.
                itemView.setOnClickListener { listener.onManualEditClicked(lote) }

            } else {
                binding.checkboxLote.visibility = View.VISIBLE
                binding.inputLayoutCantidadDesglose.visibility = View.GONE
                binding.checkboxLote.isChecked = selectedIds.contains(lote.id)

                // <-- CORRECCIÓN DE UX: El listener está en 'itemView' para hacer toda la fila clicable.
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
            return oldItem.first == newItem.first && oldItem.second == newItem.second
        }
    }
}

