package com.cesar.bocana.ui.printing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.databinding.ItemActiveLabelBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActiveLabelAdapter(
    private val onView: (ActiveLabelRecord) -> Unit,
    private val onEdit: (ActiveLabelRecord) -> Unit,
    private val onDelete: (ActiveLabelRecord) -> Unit
) : RecyclerView.Adapter<ActiveLabelAdapter.Holder>() {

    private val items = mutableListOf<ActiveLabelRecord>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(records: List<ActiveLabelRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ItemActiveLabelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemActiveLabelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ActiveLabelRecord) {
            binding.textViewActiveLabelTitle.text = item.title
            binding.textViewActiveLabelType.text = item.typeLabel
            binding.textViewActiveLabelSummary.text = item.summary.ifBlank { "Lista para imprimir" }
            binding.textViewActiveLabelTime.text = "Hoy · ${timeFormat.format(Date(item.createdAtMillis))}"
            binding.buttonActiveView.setOnClickListener { onView(item) }
            binding.buttonActiveEdit.setOnClickListener { onEdit(item) }
            binding.buttonActiveDelete.setOnClickListener { onDelete(item) }
        }
    }
}
