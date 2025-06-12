package com.cesar.bocana.ui.report

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product

class ReportProductAdapter(
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<Product, ReportProductAdapter.ProductViewHolder>(ProductDiffCallback()) {

    private val selectedProductIds = mutableSetOf<String>()

    fun setSelectedIds(ids: Set<String>) {
        Log.d("ReportAdapter", "setSelectedIds llamado con ${ids.size} IDs.")
        selectedProductIds.clear()
        selectedProductIds.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged(selectedProductIds)
    }

    fun getSelectedIds(): Set<String> {
        Log.d("ReportAdapter", "getSelectedIds llamado. Devolviendo ${selectedProductIds.size} IDs.")
        return selectedProductIds
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report_product_selectable, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val product = getItem(position)
        holder.bind(product, selectedProductIds.contains(product.id))
    }

    inner class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkbox_product)
        private val nameTextView: TextView = itemView.findViewById(R.id.textview_product_name)

        init {
            // Un solo listener en el contenedor de la fila es más eficiente y mejora la UX.
            itemView.setOnClickListener {
                // Solo reaccionar si la posición es válida para evitar crashes
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    val product = getItem(adapterPosition)
                    toggleSelection(product)
                }
            }
        }

        fun bind(product: Product, isSelected: Boolean) {
            nameTextView.text = product.name
            // Se asigna el estado visual del checkbox sin activar listeners
            checkBox.isChecked = isSelected
        }

        private fun toggleSelection(product: Product) {
            val isCurrentlySelected = selectedProductIds.contains(product.id)
            if (isCurrentlySelected) {
                selectedProductIds.remove(product.id)
                Log.d("ReportAdapter", "Producto QUITADO: ${product.name}. Total seleccionados: ${selectedProductIds.size}")
            } else {
                selectedProductIds.add(product.id)
                Log.d("ReportAdapter", "Producto AÑADIDO: ${product.name}. Total seleccionados: ${selectedProductIds.size}")
            }
            // Notificar al fragmento sobre el cambio
            onSelectionChanged(selectedProductIds)
            // Actualizar la vista de este item específico para que el checkbox se redibuje
            notifyItemChanged(adapterPosition)
        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
    }
}