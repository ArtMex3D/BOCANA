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

            // Mostrar información del lote padre si existe (para sublotes en C04)
            val origenInfo = if (item.originalLotId != null) {
                val origDateStr = item.originalReceivedAt?.let { dateOnlyFormatter.format(it) } ?: "N/A"
                val origSupplierStr = item.originalSupplierName ?: (item.originalLotNumber ?: "Origen Desc.")
                "Origen: ${origSupplierStr} (${origDateStr})"
            } else {
                // Para lotes de Matriz (o lotes en C04 sin info de padre)
                item.supplierName ?: "S/Prov"
            }

            val receivedAtDateStr = item.receivedAt?.let { dateTimeFormatter.format(it) } ?: "Fecha N/A"
            val displayText = "$origenInfo\nLlegada/Traspaso: $receivedAtDateStr"

            binding.textViewLotDate.text = displayText // Usamos textViewLotDate para toda la info
            binding.textViewLotSupplier.visibility = View.GONE // Ocultamos el supplier individual si ya está en date

            val qtyStr = String.format(Locale.getDefault(), "%.2f", item.currentQuantity)
            val unitStr = item.unit
            binding.textViewLotQuantity.text = "$qtyStr $unitStr"

            binding.checkBoxLotSelection.setOnCheckedChangeListener(null)
            binding.checkBoxLotSelection.isChecked = isSelected

            if (isMultiSelectEnabled) {
                binding.checkBoxLotSelection.setOnClickListener {
                    onSelectionChanged(item.id, binding.checkBoxLotSelection.isChecked)
                }
                binding.root.setOnClickListener {
                    binding.checkBoxLotSelection.isChecked = !binding.checkBoxLotSelection.isChecked
                    onSelectionChanged(item.id, binding.checkBoxLotSelection.isChecked)
                }
            } else { // Modo selección única
                binding.checkBoxLotSelection.isClickable = false // El checkbox no cambia el estado directamente
                binding.root.setOnClickListener {
                    if (!isSelected) { // Solo actuar si no está ya seleccionado
                        onSelectionChanged(item.id, true)
                    }
                }
            }

            // Resaltado visual para el seleccionado en modo single-select
            if (!isMultiSelectEnabled && isSelected) {
                binding.root.setBackgroundColor(ContextCompat.getColor(context, R.color.pending_item_background)) // Un color de resaltado
            } else {
                binding.root.setBackgroundColor(Color.TRANSPARENT) // Sin resaltado
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