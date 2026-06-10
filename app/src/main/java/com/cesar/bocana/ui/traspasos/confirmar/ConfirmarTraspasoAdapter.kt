package com.cesar.bocana.ui.traspasos.confirmar

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.TraspasoPlanificado
import com.cesar.bocana.databinding.ItemTraspasoConfirmarBinding
import java.text.SimpleDateFormat
import java.util.*

class ConfirmarTraspasoAdapter(
    private val onEliminarClick: (TraspasoPlanificado) -> Unit,
    private val onVerPdfClick: (TraspasoPlanificado) -> Unit
) : ListAdapter<TraspasoPlanificado, ConfirmarTraspasoAdapter.ConfirmarViewHolder>(DiffCallback()) {

    // Formato: 24 / Julio / 2025
    private val dateFormat = SimpleDateFormat("dd / MMMM / yyyy", Locale("es", "ES"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfirmarViewHolder {
        val binding = ItemTraspasoConfirmarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConfirmarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConfirmarViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ConfirmarViewHolder(private val binding: ItemTraspasoConfirmarBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(plan: TraspasoPlanificado, position: Int) {

            // 1. Mostrar Fecha
            val fecha = plan.fechaPlan?.let { dateFormat.format(it) } ?: "Fecha no disponible"
            binding.textViewPlanDate.text = fecha.uppercase()
            binding.textViewCreatedBy.text = "Generado por: ${plan.createdBy}"

            // 2. Lógica de la Etiqueta NUEVO (Solo el índice 0 lo tiene)
            if (position == 0) {
                binding.chipNuevo.visibility = View.VISIBLE
            } else {
                binding.chipNuevo.visibility = View.GONE
            }

            // 3. Diseño Zebra (Gris tenue / Blanco)
            if (position % 2 == 0) {
                binding.cardViewPdf.setCardBackgroundColor(Color.WHITE)
            } else {
                binding.cardViewPdf.setCardBackgroundColor(Color.parseColor("#F5F5F5")) // Gris super claro
            }

            // 4. Botones
            binding.buttonEliminar.setOnClickListener { onEliminarClick(plan) }
            binding.buttonVerPdf.setOnClickListener { onVerPdfClick(plan) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TraspasoPlanificado>() {
        override fun areItemsTheSame(oldItem: TraspasoPlanificado, newItem: TraspasoPlanificado): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TraspasoPlanificado, newItem: TraspasoPlanificado): Boolean = oldItem == newItem
    }
}