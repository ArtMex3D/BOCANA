package com.cesar.bocana.ui.suppliers

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Supplier
import com.cesar.bocana.databinding.ItemSupplierBinding
import com.google.android.material.chip.Chip

// Interfaz DEFINIDA aquí, incluyendo el nuevo método
interface SupplierActionListener {
    fun onSupplierClicked(supplier: Supplier)
    fun onSupplierStatusChanged(supplierId: String, newStatus: Boolean)
}

class SupplierAdapter(private val listener: SupplierActionListener) :
    ListAdapter<Supplier, SupplierAdapter.SupplierViewHolder>(SupplierDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SupplierViewHolder {
        return SupplierViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: SupplierViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }

    class SupplierViewHolder private constructor(private val binding: ItemSupplierBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Supplier, listener: SupplierActionListener) {
            binding.textViewSupplierName.text = item.name

            binding.textViewSupplierContact.visibility = if (!item.contactPerson.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.textViewSupplierContact.text = "Contacto: ${item.contactPerson ?: ""}"

            binding.textViewSupplierPhone.visibility = if (!item.phone.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.textViewSupplierPhone.text = "Tel: ${item.phone ?: ""}"

            // Configurar Chip Interactivo (Visual inicial)
            val context = binding.root.context
            val chip: Chip = binding.chipSupplierStatus
            val initialStatus = item.isActive // Guardamos el estado que llega con el item

            // Aplicar estado visual inicial
            chip.isChecked = initialStatus
            chip.isCloseIconVisible = false
            chip.isChipIconVisible = false
            chip.isCheckedIconVisible = true // Mostrar palomita si está activo

            val activeColor = ContextCompat.getColorStateList(context, R.color.stock_ok)
            val inactiveColor = ContextCompat.getColorStateList(context, R.color.completed_item_grey)
            val activeTextColor = ContextCompat.getColor(context, R.color.white)
            val inactiveTextColor = ContextCompat.getColor(context, R.color.black)

            if (initialStatus) {
                chip.text = "Activo"
                chip.chipBackgroundColor = activeColor
                chip.setTextColor(activeTextColor)
            } else {
                chip.text = "Inactivo"
                chip.chipBackgroundColor = inactiveColor
                chip.setTextColor(inactiveTextColor)
            }

            // Listener para el Chip
            // Usamos setOnClickListener en lugar de setOnCheckedChangeListener para controlar mejor
            chip.setOnClickListener {
                val newState = !item.isActive // Calculamos cuál sería el nuevo estado

                // 1. Actualizar la apariencia del chip INMEDIATAMENTE (Feedback visual)
                chip.isChecked = newState // Actualiza el estado visual del check
                if (newState) {
                    chip.text = "Activo"
                    chip.chipBackgroundColor = activeColor
                    chip.setTextColor(activeTextColor)
                } else {
                    chip.text = "Inactivo"
                    chip.chipBackgroundColor = inactiveColor
                    chip.setTextColor(inactiveTextColor)
                }

                // 2. Llamar al listener para que el Fragment guarde en Firestore el NUEVO estado
                listener.onSupplierStatusChanged(item.id, newState)
            }

            // Listener para click en toda la tarjeta (ir a editar)
            binding.root.setOnClickListener { listener.onSupplierClicked(item) }
        }


        companion object {
            fun from(parent: ViewGroup): SupplierViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemSupplierBinding.inflate(layoutInflater, parent, false)
                return SupplierViewHolder(binding)
            }
        }
    }
}

class SupplierDiffCallback : DiffUtil.ItemCallback<Supplier>() {
    override fun areItemsTheSame(oldItem: Supplier, newItem: Supplier): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: Supplier, newItem: Supplier): Boolean {
        return oldItem == newItem
    }
}