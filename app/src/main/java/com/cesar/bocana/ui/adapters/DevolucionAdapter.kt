package com.cesar.bocana.ui.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.DevolucionPendiente
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.databinding.ItemDevolucionBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

interface DevolucionActionListener {
    fun onCompletarClicked(devolucion: DevolucionPendiente)
}

class DevolucionAdapter(private val listener: DevolucionActionListener) :
    ListAdapter<DevolucionPendiente, DevolucionAdapter.DevolucionViewHolder>(DevolucionDiffCallback()) {

    private val dateFormatter = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DevolucionViewHolder {
        Log.d(TAG_ADAPTER, "onCreateViewHolder")
        return DevolucionViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: DevolucionViewHolder, position: Int) {
        val item = getItem(position)
        val previousStatus = if (position > 0) getItem(position - 1).status else null
        Log.d(TAG_ADAPTER, "onBindViewHolder - Position: $position, ItemStatus: ${item.status}, PreviousStatus: $previousStatus")
        holder.bind(item, previousStatus, listener, dateFormatter, position)
    }

    class DevolucionViewHolder private constructor(private val binding: ItemDevolucionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: DevolucionPendiente,
            previousStatus: DevolucionStatus?,
            listener: DevolucionActionListener,
            formatter: SimpleDateFormat,
            position: Int
        ) {
            binding.textViewDevProductName.text = item.productName
            binding.textViewDevProviderValue.text = item.provider

            val formattedQuantity = String.format(Locale.getDefault(), "%.2f", item.quantity)
            binding.textViewDevQuantityValue.text = "$formattedQuantity ${item.unit}"

            binding.textViewDevReasonValue.text = item.reason
            binding.textViewDevDateValue.text = item.registeredAt?.let { formatter.format(it) } ?: "--"

            val context = binding.root.context
            val isCompleted = item.status == DevolucionStatus.COMPLETADO

            if (isCompleted) {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.completed_item_grey))
                binding.buttonCompletarDevolucion.isEnabled = false
                binding.buttonCompletarDevolucion.alpha = 0.5f
            } else {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.pending_item_yellow))
                binding.buttonCompletarDevolucion.isEnabled = true
                binding.buttonCompletarDevolucion.alpha = 1.0f
            }

            binding.buttonCompletarDevolucion.setOnClickListener {
                if (!isCompleted) listener.onCompletarClicked(item)
            }

            val showHeader = isCompleted && (previousStatus == null || previousStatus == DevolucionStatus.PENDIENTE)
            Log.d(TAG_VIEWHOLDER, "bind (Pos $position) - ItemStatus: ${item.status}, PrevStatus: $previousStatus, isCompleted: $isCompleted, ShowHeader: $showHeader")

            binding.textViewHistorialHeader.visibility = if (showHeader) View.VISIBLE else View.GONE
            Log.d(TAG_VIEWHOLDER, "bind (Pos $position) - Header ${if(showHeader) "VISIBLE" else "GONE"}")

        }

        companion object {
            private const val TAG_VIEWHOLDER = "DevolucionViewHolder"
            fun from(parent: ViewGroup): DevolucionViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemDevolucionBinding.inflate(layoutInflater, parent, false)
                return DevolucionViewHolder(binding)
            }
        }
    }

    companion object {
        private const val TAG_ADAPTER = "DevolucionAdapter"
    }

}

class DevolucionDiffCallback : DiffUtil.ItemCallback<DevolucionPendiente>() {
    override fun areItemsTheSame(oldItem: DevolucionPendiente, newItem: DevolucionPendiente): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: DevolucionPendiente, newItem: DevolucionPendiente): Boolean {
        return oldItem == newItem
    }
}