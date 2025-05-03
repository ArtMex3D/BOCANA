package com.cesar.bocana.ui.adapters

import android.util.Log // Importar Log
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
import java.util.*

// Interfaz sin cambios
interface DevolucionActionListener {
    fun onCompletarClicked(devolucion: DevolucionPendiente)
}

// Adapter con LOGS añadidos
class DevolucionAdapter(private val listener: DevolucionActionListener) :
    ListAdapter<DevolucionPendiente, DevolucionAdapter.DevolucionViewHolder>(DevolucionDiffCallback()) {

    private val dateFormatter = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DevolucionViewHolder {
        Log.d(TAG_ADAPTER, "onCreateViewHolder") // Log creación
        return DevolucionViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: DevolucionViewHolder, position: Int) {
        val item = getItem(position)
        val previousStatus = if (position > 0) getItem(position - 1).status else null
        // --- LOGS EN onBindViewHolder ---
        Log.d(TAG_ADAPTER, "onBindViewHolder - Position: $position, ItemStatus: ${item.status}, PreviousStatus: $previousStatus")
        // ---------------------------------
        holder.bind(item, previousStatus, listener, dateFormatter, position) // Pasa position para logging
    }

    // ViewHolder con LOGS añadidos
    class DevolucionViewHolder private constructor(private val binding: ItemDevolucionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: DevolucionPendiente,
            previousStatus: DevolucionStatus?,
            listener: DevolucionActionListener,
            formatter: SimpleDateFormat,
            position: Int // Recibe position para logging
        ) {
            // ... (Poblar datos como estaba) ...
            binding.textViewDevProductName.text = item.productName; binding.textViewDevProviderValue.text = item.provider
            binding.textViewDevQuantityValue.text = "${item.quantity}"; binding.textViewDevReasonValue.text = item.reason
            binding.textViewDevDateValue.text = item.registeredAt?.let { formatter.format(it) } ?: "--"

            val context = binding.root.context
            val isCompleted = item.status == DevolucionStatus.COMPLETADO

            // Aplicar estilos según estado
            if (isCompleted) {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.completed_item_grey))
                binding.buttonCompletarDevolucion.isEnabled = false; binding.buttonCompletarDevolucion.alpha = 0.5f
            } else {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.pending_item_yellow))
                binding.buttonCompletarDevolucion.isEnabled = true; binding.buttonCompletarDevolucion.alpha = 1.0f
            }

            // Listener botón completar
            binding.buttonCompletarDevolucion.setOnClickListener { if (!isCompleted) listener.onCompletarClicked(item) }

            // --- Lógica y LOGS para mostrar el encabezado ---
            val showHeader = isCompleted && (previousStatus == null || previousStatus == DevolucionStatus.PENDIENTE)
            Log.d(TAG_VIEWHOLDER, "bind (Pos $position) - ItemStatus: ${item.status}, PrevStatus: $previousStatus, isCompleted: $isCompleted, ShowHeader: $showHeader")

            if (showHeader) {
                binding.textViewHistorialHeader.visibility = View.VISIBLE
                Log.d(TAG_VIEWHOLDER, "bind (Pos $position) - Header VISIBLE")
            } else {
                binding.textViewHistorialHeader.visibility = View.GONE
                Log.d(TAG_VIEWHOLDER, "bind (Pos $position) - Header GONE")
            }
            // ----------------------------------------------
        } // Fin de bind

        companion object {
            private const val TAG_VIEWHOLDER = "DevolucionViewHolder" // TAG para logs del ViewHolder
            fun from(parent: ViewGroup): DevolucionViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemDevolucionBinding.inflate(layoutInflater, parent, false)
                return DevolucionViewHolder(binding)
            }
        }
    } // Fin ViewHolder

    // Companion Object para TAG del Adapter
    companion object {
        private const val TAG_ADAPTER = "DevolucionAdapter" // TAG para logs del Adapter
    }

} // Fin Adapter

// DiffUtil sin cambios
class DevolucionDiffCallback : DiffUtil.ItemCallback<DevolucionPendiente>() {
    override fun areItemsTheSame(oldItem: DevolucionPendiente, newItem: DevolucionPendiente): Boolean { return oldItem.id == newItem.id }
    override fun areContentsTheSame(oldItem: DevolucionPendiente, newItem: DevolucionPendiente): Boolean { return oldItem == newItem }
}