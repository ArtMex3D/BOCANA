package com.cesar.bocana.ui.migration

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.ItemCategoryMigrationBinding

class CategoryMigrationAdapter(
    private val context: Context,
    private val allProducts: List<Product>,
    private val onCategoryChanged: (productId: String, newCategory: String) -> Unit,
    private val onRectorChanged: (productId: String, newRectorId: String?) -> Unit
) : ListAdapter<Product, CategoryMigrationAdapter.ViewHolder>(ProductDiffCallback()) {

    private val categories = listOf("FIJO", "PESCADO_GRANDE", "PESCADO_CHICO")
    private val productNames = allProducts.map { it.name }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryMigrationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCategoryMigrationBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Setup adapters once
            val categoryAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, categories)
            binding.autoCompleteCategoria.setAdapter(categoryAdapter)

            val rectorAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, productNames)
            binding.autoCompleteProductoRector.setAdapter(rectorAdapter)
        }

        fun bind(product: Product) {
            binding.textViewProductName.text = product.name

            // Set current values without triggering listeners
            binding.autoCompleteCategoria.setText(product.categoria, false)
            val rectorProduct = allProducts.find { it.id == product.productoRectorId }
            binding.autoCompleteProductoRector.setText(rectorProduct?.name ?: "", false)

            // Show/hide rector selector
            binding.textFieldLayoutProductoRector.visibility = if (product.categoria == "PESCADO_CHICO") View.VISIBLE else View.GONE

            // Clear previous listeners to prevent multiple triggers on recycled views
            binding.autoCompleteCategoria.onItemClickListener = null
            binding.autoCompleteProductoRector.onItemClickListener = null

            // Set new listeners
            binding.autoCompleteCategoria.setOnItemClickListener { _, _, position, _ ->
                val newCategory = categories[position]
                onCategoryChanged(product.id, newCategory)
                // The fragment will receive this event and submit a new list, which will re-bind this view
            }

            binding.autoCompleteProductoRector.setOnItemClickListener { _, _, position, _ ->
                val selectedName = productNames[position]
                val newRector = allProducts.find { it.name == selectedName }
                onRectorChanged(product.id, newRector?.id)
            }
        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
    }
}
