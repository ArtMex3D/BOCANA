package com.cesar.bocana.ui.migration

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.ItemLotMigrationBinding
import java.text.SimpleDateFormat
import java.util.*

class LotMigrationAdapter(
    private val onEditClick: (StockLot) -> Unit
) : ListAdapter<StockLot, LotMigrationAdapter.LotViewHolder>(LotDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LotViewHolder {
        val binding = ItemLotMigrationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LotViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LotViewHolder(private val binding: ItemLotMigrationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(lote: StockLot) {
            val context = binding.root.context
            val fecha = lote.receivedAt?.let { dateFormat.format(it) } ?: "Sin Fecha"
            val proveedor = lote.supplierName ?: "S/P"
            binding.textViewLotInfo.text = "$fecha - $proveedor - ${lote.location}"
            binding.textViewStockInfo.text = "Stock Registrado: ${String.format("%.2f", lote.currentQuantity)} ${lote.unit}"

            // Lógica para determinar si el lote necesita conversión
            val necesitaConversion = lote.unidadDeEmpaque.isNullOrBlank() || lote.pesoPorUnidad == null || lote.cantidadInicialUnidades == null

            if (necesitaConversion) {
                binding.statusIcon.setImageResource(android.R.drawable.stat_sys_warning)
                binding.statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.transfer_yellow))
                binding.textViewStatus.text = "Requiere conversión"
                binding.buttonEditLote.text = "Convertir"
            } else {
                binding.statusIcon.setImageResource(R.drawable.verificado)
                binding.statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.positive_green))
                binding.textViewStatus.text = "Convertido: ${String.format("%.1f", lote.cantidadInicialUnidades)} ${lote.unidadDeEmpaque} de ${String.format("%.2f", lote.pesoPorUnidad)} Kg c/u"
                binding.buttonEditLote.text = "Editar"
            }

            binding.buttonEditLote.setOnClickListener {
                onEditClick(lote)
            }
        }
    }

    class LotDiffCallback : DiffUtil.ItemCallback<StockLot>() {
        override fun areItemsTheSame(oldItem: StockLot, newItem: StockLot): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: StockLot, newItem: StockLot): Boolean = oldItem == newItem
    }
}
