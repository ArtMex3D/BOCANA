package com.cesar.bocana.ui.traspasos.confirmar

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.TraspasoPlanificado
import com.cesar.bocana.databinding.ItemTraspasoConfirmarBinding
import java.text.SimpleDateFormat
import java.util.*

class ConfirmarTraspasoAdapter(
    private val onConfirmClick: (TraspasoPlanificado) -> Unit,
    private val onCancelClick: (TraspasoPlanificado) -> Unit
) : ListAdapter<TraspasoPlanificado, ConfirmarTraspasoAdapter.ConfirmarViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfirmarViewHolder {
        val binding = ItemTraspasoConfirmarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConfirmarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConfirmarViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConfirmarViewHolder(private val binding: ItemTraspasoConfirmarBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(plan: TraspasoPlanificado) {
            val fecha = plan.fechaPlan?.let { dateFormat.format(it) } ?: "Fecha no disponible"
            binding.textViewPlanDate.text = "Plan para: $fecha"
            binding.textViewCreatedBy.text = "Creado por: ${plan.createdBy}"

            binding.buttonConfirm.setOnClickListener {
                onConfirmClick(plan)
            }
            binding.buttonCancel.setOnClickListener {
                onCancelClick(plan)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TraspasoPlanificado>() {
        override fun areItemsTheSame(oldItem: TraspasoPlanificado, newItem: TraspasoPlanificado): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TraspasoPlanificado, newItem: TraspasoPlanificado): Boolean = oldItem == newItem
    }
}