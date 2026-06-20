package com.cesar.bocana.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.ItemDialogLotSelectionBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

// Modelo para el encabezado del grupo
data class GroupHeaderItem(val headerTitle: String)



// Nueva interfaz para selección única
interface SingleLotSelectionListener {
    fun onLotSelected(selectedLot: StockLot)
}

class LotSelectionAdapter(
    private val multiSelectEnabled: Boolean = true, // true por defecto para multi-selección
    private val singleLotSelectionListener: SingleLotSelectionListener? = null // Listener para selección única
) : ListAdapter<StockLot, LotSelectionAdapter.LotViewHolder>(LotDiffCallback()) {

    private val selectedLotsMap = mutableMapOf<String, Boolean>()
    private var singleSelectedLotId: String? = null // Para rastrear el lote seleccionado en modo single-select

    private val dateFormatter = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
    private val dateTimeFormatter = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())


    fun getSelectedLotIds(): List<String> {
        return if (multiSelectEnabled) {
            selectedLotsMap.filter { it.value }.keys.toList()
        } else {
            singleSelectedLotId?.let { listOf(it) } ?: emptyList()
        }
    }

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

    fun getSingleSelectedLot(): StockLot? {
        if (multiSelectEnabled || singleSelectedLotId == null) return null
        return currentList.find { it.id == singleSelectedLotId }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LotViewHolder {
        return LotViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: LotViewHolder, position: Int) {
        val item = getItem(position)
        val isSelected = if (multiSelectEnabled) {
            selectedLotsMap[item.id] ?: false
        } else {
            item.id == singleSelectedLotId
        }

        holder.bind(item, isSelected, dateFormatter, dateTimeFormatter, multiSelectEnabled) { lotId, nowSelected ->
            if (multiSelectEnabled) {
                selectedLotsMap[lotId] = nowSelected
            } else {
                val previouslySelectedId = singleSelectedLotId
                if (nowSelected) {
                    singleSelectedLotId = lotId
                    singleLotSelectionListener?.onLotSelected(item) // Notificar al listener
                } else if (singleSelectedLotId == lotId) {
                    // Si se deselecciona el actualmente seleccionado (aunque el checkbox no permite deselección directa usualmente)
                    singleSelectedLotId = null
                }
                // Refrescar el item anterior si había uno y el nuevo
                previouslySelectedId?.let { oldId -> currentList.indexOfFirst { it.id == oldId }.takeIf { it != -1 }?.let { notifyItemChanged(it) } }
                currentList.indexOfFirst { it.id == lotId }.takeIf { it != -1 }?.let { notifyItemChanged(it) }

            }
        }
    }

    class LotViewHolder private constructor(private val binding: ItemDialogLotSelectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: StockLot,
            isSelected: Boolean,
            dateOnlyFormatter: SimpleDateFormat,
            dateTimeFormatter: SimpleDateFormat,
            isMultiSelectEnabled: Boolean,
            onSelectionChanged: (String, Boolean) -> Unit
        ) {
            val context = binding.root.context

            val supplierDisplay = if (item.originalLotId != null) {
                "Origen: ${item.originalSupplierName ?: "Desconocido"}"
            } else {
                item.supplierName ?: "Sin proveedor"
            }
            binding.textViewLotSupplier.text = supplierDisplay

            val receivedAtStr = item.receivedAt?.let { dateTimeFormatter.format(it) } ?: "Fecha N/A"
            binding.textViewLotDate.text = "📦 Recibido: $receivedAtStr"

            val qtyStr = String.format(Locale.getDefault(), "%.2f", item.currentQuantity)
            binding.textViewLotQuantity.text = "$qtyStr ${item.unit}"

            binding.checkBoxLotSelection.setOnCheckedChangeListener(null)
            binding.checkBoxLotSelection.isChecked = isSelected

            if (isMultiSelectEnabled) {
                binding.checkBoxLotSelection.isClickable = true
                binding.checkBoxLotSelection.setOnCheckedChangeListener { _, isChecked ->
                    onSelectionChanged(item.id, isChecked)
                }
                binding.root.setOnClickListener {
                    binding.checkBoxLotSelection.isChecked = !binding.checkBoxLotSelection.isChecked
                }
            } else {
                binding.checkBoxLotSelection.isClickable = false
                binding.root.setOnClickListener {
                    if (!isSelected) {
                        onSelectionChanged(item.id, true)
                    }
                }
            }

            if (!isMultiSelectEnabled && isSelected) {
                binding.root.setBackgroundColor(ContextCompat.getColor(context, R.color.pending_item_background))
            } else {
                binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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