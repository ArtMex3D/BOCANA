package com.cesar.bocana.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

interface SubloteC04SelectionListener {
    fun onSubloteSelected(selectedLot: StockLot)
}

class SubloteC04SelectionAdapter(
    private val listener: SubloteC04SelectionListener
) : ListAdapter<GroupableListItem, RecyclerView.ViewHolder>(GroupableListItemDiffCallback()) {

    private var selectedSubloteId: String? = null
    private val shortDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
    private val fullDateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SUBLOTE = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is GroupableListItem.HeaderItem -> VIEW_TYPE_HEADER
            is GroupableListItem.SubLoteItem -> VIEW_TYPE_SUBLOTE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_sublote_group_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_SUBLOTE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_sublote_selectable, parent, false)
                SubloteViewHolder(view)
            }
            else -> throw IllegalArgumentException("Tipo de vista inválido")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GroupableListItem.HeaderItem -> (holder as HeaderViewHolder).bind(item)
            is GroupableListItem.SubLoteItem -> (holder as SubloteViewHolder).bind(item.stockLot)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerTitle: TextView = itemView.findViewById(R.id.textViewSubLoteGroupHeader)

        fun bind(item: GroupableListItem.HeaderItem) {
            headerTitle.text = item.title
        }
    }

    inner class SubloteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val radioButton: RadioButton = itemView.findViewById(R.id.radioButtonSubLoteSelect)
        private val traspasoInfo: TextView = itemView.findViewById(R.id.textViewSubLoteTraspasoInfo)
        private val quantityInfo: TextView = itemView.findViewById(R.id.textViewSubLoteQuantity)

        fun bind(sublote: StockLot) {
            val traspasoDateStr = sublote.receivedAt?.let { fullDateFormat.format(it) } ?: "N/A"
            val quantityStr = String.format(Locale.getDefault(), "%.2f ${sublote.unit}", sublote.currentQuantity)
            val displayText: String

            if (sublote.originalLotId != null && sublote.originalSupplierName != null && sublote.originalReceivedAt != null) {
                displayText = "Traspaso: $traspasoDateStr"
            } else {
                val supplierInfo = sublote.supplierName ?: "N/A"
                val lotNumberInfo = sublote.lotNumber ?: "N/A"
                if (sublote.supplierName != null || sublote.lotNumber != null) {
                    displayText = "(Prov: $supplierInfo, Lote: $lotNumberInfo) Traspaso: $traspasoDateStr"
                } else {
                    displayText = "(ID:...${sublote.id.takeLast(4)}) Traspaso: $traspasoDateStr"
                }
            }
            traspasoInfo.text = displayText
            quantityInfo.text = quantityStr
            radioButton.isChecked = sublote.id == selectedSubloteId

            val clickListener = View.OnClickListener {
                val previouslySelectedId = selectedSubloteId
                if (selectedSubloteId != sublote.id) {
                    selectedSubloteId = sublote.id
                    listener.onSubloteSelected(sublote)

                    if (previouslySelectedId != null) {
                        val oldIndex = currentList.indexOfFirst { it is GroupableListItem.SubLoteItem && it.stockLot.id == previouslySelectedId }
                        if (oldIndex != -1) notifyItemChanged(oldIndex)
                    }
                    notifyItemChanged(adapterPosition)
                }
            }
            itemView.setOnClickListener(clickListener)
            radioButton.setOnClickListener(clickListener)
        }
    }
}

class GroupableListItemDiffCallback : DiffUtil.ItemCallback<GroupableListItem>() {
    override fun areItemsTheSame(oldItem: GroupableListItem, newItem: GroupableListItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: GroupableListItem, newItem: GroupableListItem): Boolean {
        return oldItem == newItem
    }
}