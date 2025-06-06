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

interface SubloteC04SelectionListener {
    fun onSubloteSelected(selectedLot: StockLot)
}

class SubloteC04SelectionAdapter(
    private val listener: SubloteC04SelectionListener
) : ListAdapter<GroupableListItem, RecyclerView.ViewHolder>(GroupableListItemDiffCallback()) {

    private var selectedSubloteId: String? = null
    // Formatos de fecha no son necesarios aquí si el ítem solo muestra cantidad

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
                HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sublote_group_header, parent, false))
            }
            VIEW_TYPE_SUBLOTE -> {
                SubloteViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sublote_selectable, parent, false))
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
        private val infoTextView: TextView = itemView.findViewById(R.id.textViewSubLoteInfo) // ID actualizado

        fun bind(sublote: StockLot) {
            infoTextView.text = String.format(Locale.getDefault(), "Actual: %.2f %s", sublote.currentQuantity, sublote.unit)
            radioButton.isChecked = sublote.id == selectedSubloteId

            val clickListener = View.OnClickListener {
                val previouslySelectedId = selectedSubloteId
                if (selectedSubloteId != sublote.id) {
                    selectedSubloteId = sublote.id
                    listener.onSubloteSelected(sublote)
                    notifyItemChangedIfFound(previouslySelectedId)
                    notifyItemChangedIfFound(sublote.id) // Notificar el actual para actualizar su estado visual
                }
            }
            itemView.setOnClickListener(clickListener)
            radioButton.setOnClickListener(clickListener)
        }

        private fun notifyItemChangedIfFound(lotId: String?) {
            lotId?.let { id ->
                val index = currentList.indexOfFirst { it is GroupableListItem.SubLoteItem && it.stockLot.id == id }
                if (index != -1) {
                    notifyItemChanged(index)
                }
            }
        }
    }
}

class GroupableListItemDiffCallback : DiffUtil.ItemCallback<GroupableListItem>() {
    override fun areItemsTheSame(oldItem: GroupableListItem, newItem: GroupableListItem): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: GroupableListItem, newItem: GroupableListItem): Boolean = oldItem == newItem
}