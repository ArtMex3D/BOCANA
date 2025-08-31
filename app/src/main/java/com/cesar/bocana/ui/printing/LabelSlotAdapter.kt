package com.cesar.bocana.ui.printing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.databinding.ItemLabelSlotBinding
import java.text.SimpleDateFormat
import java.util.*

class LabelSlotAdapter(
    private val slots: List<IndividualLabelConfig?>,
    private val onAssign: (Int) -> Unit,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<LabelSlotAdapter.SlotViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotViewHolder {
        val binding = ItemLabelSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SlotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        holder.bind(position, slots[position])
    }

    override fun getItemCount(): Int = slots.size

    inner class SlotViewHolder(private val binding: ItemLabelSlotBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int, config: IndividualLabelConfig?) {
            binding.textViewSlotNumber.text = "${position + 1}:"

            val isConfigured = config != null
            binding.layoutConfiguredInfo.isVisible = isConfigured
            binding.buttonEdit.isVisible = isConfigured
            binding.buttonDelete.isVisible = isConfigured
            binding.buttonAssign.isVisible = !isConfigured

            if (config != null) {
                binding.textViewProductName.text = config.product.name
                val weightText = if (config.weight == "Manual") "Manual" else "${config.weight} ${config.unit}"
                binding.textViewDetails.text = "${config.supplierName} - ${dateFormat.format(config.date)} - $weightText"
            }

            binding.buttonAssign.setOnClickListener { onAssign(adapterPosition) }
            binding.buttonEdit.setOnClickListener { onEdit(adapterPosition) }
            binding.buttonDelete.setOnClickListener { onDelete(adapterPosition) }
        }
    }
}