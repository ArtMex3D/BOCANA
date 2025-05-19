package com.cesar.bocana.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.ItemProductArchivedBinding
import java.util.Locale

interface ArchivedProductActionListener {
    fun onReactivateProductClicked(product: Product)
    fun onArchivedProductClicked(product: Product) // Para ir a editar
}

class ArchivedProductAdapter(
    private val listener: ArchivedProductActionListener
) : ListAdapter<Product, ArchivedProductAdapter.ArchivedProductViewHolder>(ProductDiffCallback()) { // Reutiliza ProductDiffCallback

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArchivedProductViewHolder {
        return ArchivedProductViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: ArchivedProductViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }

    class ArchivedProductViewHolder private constructor(
        private val binding: ItemProductArchivedBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Product, listener: ArchivedProductActionListener) {
            binding.textViewArchivedProductName.text = item.name
            binding.textViewArchivedProductUnit.text = item.unit

            val format = Locale.getDefault()
            val stockMatrizStr = String.format(format, "%.2f", item.stockMatriz)
            val stockC04Str = String.format(format, "%.2f", item.stockCongelador04)
            val totalStockStr = String.format(format, "%.2f", item.totalStock)
            binding.textViewArchivedProductStockInfo.text = "Stock Total: $totalStockStr ${item.unit} (Matriz: $stockMatrizStr, C04: $stockC04Str)"


            binding.buttonReactivateProduct.setOnClickListener {
                listener.onReactivateProductClicked(item)
            }
            binding.root.setOnClickListener {
                listener.onArchivedProductClicked(item)
            }
        }

        companion object {
            fun from(parent: ViewGroup): ArchivedProductViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                val binding = ItemProductArchivedBinding.inflate(inflater, parent, false)
                return ArchivedProductViewHolder(binding)
            }
        }
    }
}
