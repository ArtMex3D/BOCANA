package com.cesar.bocana.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.ItemDialogLotSelectionBinding // Importa el binding generado
import java.text.SimpleDateFormat
import java.util.Locale

class LotSelectionAdapter : ListAdapter<StockLot, LotSelectionAdapter.LotViewHolder>(LotDiffCallback()) {

    // Mapa para rastrear qué lotes están seleccionados (ID -> Boolean)
    private val selectedLots = mutableMapOf<String, Boolean>()
    private val dateFormatter = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    // Función para obtener los IDs de los lotes seleccionados
    fun getSelectedLotIds(): List<String> {
        return selectedLots.filter { it.value }.keys.toList()
    }

    // Función para obtener la cantidad total de los lotes seleccionados
    fun getSelectedLotsTotalQuantity(): Double {
        var total = 0.0
        val selectedIds = getSelectedLotIds()
        currentList.forEach { lot ->
            if (selectedIds.contains(lot.id)) {
                total += lot.currentQuantity
            }
        }
        return total
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LotViewHolder {
        return LotViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: LotViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, selectedLots[item.id] ?: false, dateFormatter) { lotId, isSelected ->
            selectedLots[lotId] = isSelected // Actualizar estado de selección
        }
    }

    class LotViewHolder private constructor(private val binding: ItemDialogLotSelectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: StockLot,
            isSelected: Boolean,
            formatter: SimpleDateFormat,
            onSelectionChanged: (String, Boolean) -> Unit
        ) {
            val context = binding.root.context
            val dateStr = item.receivedAt?.let { formatter.format(it) } ?: "Fecha N/A"
            val provStr = item.supplierName ?: "S/Prov"
            val qtyStr = String.format(Locale.getDefault(), "%.2f", item.currentQuantity)
            val unitStr = item.unit

            binding.textViewLotDate.text = dateStr
            binding.textViewLotSupplier.text = provStr
            binding.textViewLotQuantity.text = "$qtyStr $unitStr"

            // Sincronizar CheckBox SIN disparar el listener
            binding.checkBoxLotSelection.setOnCheckedChangeListener(null)
            binding.checkBoxLotSelection.isChecked = isSelected
            binding.checkBoxLotSelection.setOnCheckedChangeListener { _, checked ->
                onSelectionChanged(item.id, checked)
            }

            // Permitir seleccionar también haciendo clic en toda la fila
            binding.root.setOnClickListener {
                binding.checkBoxLotSelection.isChecked = !binding.checkBoxLotSelection.isChecked
                // El listener del checkbox se disparará y llamará a onSelectionChanged
            }
        }

        companion object {
            fun from(parent: ViewGroup): LotViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemDialogLotSelectionBinding.inflate(layoutInflater, parent, false)
                return LotViewHolder(binding)
            }
        }
    }
}

class LotDiffCallback : DiffUtil.ItemCallback<StockLot>() {
    override fun areItemsTheSame(oldItem: StockLot, newItem: StockLot): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: StockLot, newItem: StockLot): Boolean {
        return oldItem == newItem
    }
}