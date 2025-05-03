package com.cesar.bocana.ui.adapters

import android.graphics.Color // Importar Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat // Importar ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R // Importar R
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.databinding.ItemPackagingBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit // Importar TimeUnit
import android.view.View
interface PackagingActionListener {
    fun onMarkPackagedClicked(task: PendingPackagingTask)
}

class PackagingAdapter(private val listener: PackagingActionListener) :
    ListAdapter<PendingPackagingTask, PackagingAdapter.PackagingViewHolder>(PackagingDiffCallback()) {

    private val dateFormatter = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackagingViewHolder {
        return PackagingViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: PackagingViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, listener, dateFormatter)
    }

    class PackagingViewHolder private constructor(private val binding: ItemPackagingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PendingPackagingTask, listener: PackagingActionListener, formatter: SimpleDateFormat) {
            binding.textViewPackProductName.text = item.productName
            binding.textViewPackQuantityValue.text = "${item.quantityReceived} ${item.unit}"
            val receivedDate: Date? = item.receivedAt // Usar fecha de Firestore

            binding.textViewPackDateValue.text = receivedDate?.let { formatter.format(it) } ?: "--"

            // --- Lógica para Tiempo Transcurrido y Alerta ---
            val timeElapsedTextView = binding.textViewPackTimeElapsed
            if (receivedDate != null) {
                val currentTime = System.currentTimeMillis() // Hora actual del dispositivo
                val receivedTime = receivedDate.time
                val diffMillis = currentTime - receivedTime

                if (diffMillis >= 0) { // Asegurar que la diferencia no sea negativa
                    val days = TimeUnit.MILLISECONDS.toDays(diffMillis)
                    // val hours = TimeUnit.MILLISECONDS.toHours(diffMillis) % 24 // Descomentar si quieres mostrar horas

                    if (days >= 3) {
                        // Más de 3 días - Mostrar alerta roja
                        timeElapsedTextView.text = "¡${days} DÍAS RETRASO!"
                        timeElapsedTextView.visibility = View.VISIBLE
                        // Cambiar fondo de la tarjeta a un rojo pálido
                        binding.packagingCardView.setCardBackgroundColor(
                            ContextCompat.getColor(binding.root.context, R.color.low_stock_red_background) // Necesitas definir este color
                        )
                        timeElapsedTextView.setTextColor(Color.RED) // Color de texto rojo
                    } else {
                        // Menos de 3 días - Opcional: mostrar días/horas o nada
                        // timeElapsedTextView.text = "${days}d ${hours}h" // Ejemplo mostrando días y horas
                        timeElapsedTextView.visibility = View.INVISIBLE // Ocultar si no está retrasado
                        // Restaurar color original de la tarjeta
                        binding.packagingCardView.setCardBackgroundColor(
                            ContextCompat.getColor(binding.root.context, R.color.pending_item_background) // Necesitas definir este color
                        )
                    }
                } else {
                    // Fecha futura? Ocultar/mostrar mensaje error
                    timeElapsedTextView.visibility = View.INVISIBLE
                    binding.packagingCardView.setCardBackgroundColor(
                        ContextCompat.getColor(binding.root.context, R.color.pending_item_background)
                    )
                }

            } else {
                // No hay fecha de recepción
                timeElapsedTextView.visibility = View.INVISIBLE
                binding.packagingCardView.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.pending_item_background)
                )
            }
            // --- Fin Lógica Tiempo ---

            binding.buttonMarkPackaged.setOnClickListener {
                listener.onMarkPackagedClicked(item)
            }
        }

        companion object {
            fun from(parent: ViewGroup): PackagingViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemPackagingBinding.inflate(layoutInflater, parent, false)
                return PackagingViewHolder(binding)
            }
        }
    }
}

class PackagingDiffCallback : DiffUtil.ItemCallback<PendingPackagingTask>() {
    override fun areItemsTheSame(oldItem: PendingPackagingTask, newItem: PendingPackagingTask): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: PendingPackagingTask, newItem: PendingPackagingTask): Boolean {
        return oldItem == newItem
    }
}