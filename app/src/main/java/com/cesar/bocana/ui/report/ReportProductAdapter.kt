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
        notifyDataSetChanged() // Se usa para actualizar toda la lista de una vez
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
            // Se asigna un listener a toda la fila para una mejor experiencia de usuario.
            itemView.setOnClickListener {
                // Se asegura de que la posición del item sea válida antes de hacer algo.
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    val product = getItem(adapterPosition)

                    if (selectedProductIds.contains(product.id)) {
                        selectedProductIds.remove(product.id)
                    } else {
                        selectedProductIds.add(product.id)
                    }

                    notifyItemChanged(adapterPosition)
                    onSelectionChanged(selectedProductIds)
                }
            }
        }

        fun bind(product: Product, isSelected: Boolean) {
            nameTextView.text = product.name
            checkBox.isClickable = false
            checkBox.isChecked = isSelected
        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
    }
}