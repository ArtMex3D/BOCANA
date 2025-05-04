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
import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.UserRole
import com.cesar.bocana.databinding.ItemProductBinding
import java.util.Locale

interface ProductActionListener {
    fun onAddCompraClicked(product: Product)
    fun onSalidaClicked(product: Product)
    fun onTraspasoC04Clicked(product: Product)
    fun onItemClicked(product: Product)
    fun onEditC04Clicked(product: Product)
    fun onTraspasoC04MClicked(product: Product)
}

class ProductAdapter(
    private val actionListener: ProductActionListener,
    private var currentUserRole: UserRole?
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(ProductDiffCallback()) {

    private var currentLocationContext: String = Location.MATRIZ

    fun setCurrentUserRole(role: UserRole?) {
        if (role != currentUserRole) {
            currentUserRole = role
            notifyDataSetChanged()
        }
    }
    fun setCurrentLocationContext(newContext: String) {
        if (newContext != currentLocationContext) {
            currentLocationContext = newContext
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        return ProductViewHolder.from(parent, currentUserRole)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(getItem(position), actionListener, currentLocationContext)
    }

    class ProductViewHolder private constructor(
        private val binding: ItemProductBinding,
        private val userRole: UserRole?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Product, listener: ProductActionListener, contextLocation: String) {
            binding.textViewProductName.text = item.name
            binding.textViewProductUnit.text = item.unit

            val format = Locale.getDefault()
            val formattedMinStock = String.format(format, "%.2f", item.minStock)
            val formattedStockMatriz = String.format(format, "%.2f", item.stockMatriz)
            val formattedStockC04 = String.format(format, "%.2f", item.stockCongelador04)
            val formattedTotalStock = String.format(format, "%.2f", item.totalStock)

            binding.textViewMinStockValue.text = formattedMinStock
            binding.textViewStockMatrizValue.text = formattedStockMatriz
            binding.textViewStockC04Value.text = formattedStockC04
            binding.textViewStockTotalValue.text = formattedTotalStock

            val isLowStock = item.totalStock <= item.minStock && item.minStock > 0.0
            val context = binding.root.context
            val lowStockColor = ContextCompat.getColor(context, R.color.low_stock_red)
            val defaultTextColor = ContextCompat.getColor(context, android.R.color.tab_indicator_text) // O usa un color de tu tema

            binding.textViewMinStockValue.setTextColor(if (isLowStock) lowStockColor else defaultTextColor)
            binding.textViewMinStockLabel.setTextColor(if (isLowStock) lowStockColor else defaultTextColor)
            binding.imageViewLowStockIndicator.visibility = if (isLowStock) View.VISIBLE else View.GONE
            val totalStockColor = ContextCompat.getColor(context, R.color.total_stock_green)
            binding.textViewStockTotalValue.setTextColor(totalStockColor)

            val canModify = userRole == UserRole.ADMIN

            if (contextLocation == Location.MATRIZ) {
                binding.buttonAddCompra.visibility = View.VISIBLE
                binding.buttonAddSalida.visibility = View.VISIBLE
                binding.buttonAddTraspaso.visibility = View.VISIBLE
                binding.buttonEditC04.visibility = View.GONE
                binding.buttonTraspasoC04M.visibility = View.GONE

                binding.buttonAddCompra.isEnabled = canModify
                binding.buttonAddSalida.isEnabled = canModify
                binding.buttonAddTraspaso.isEnabled = canModify
                binding.buttonAddCompra.alpha = if (canModify) 1.0f else 0.5f
                binding.buttonAddSalida.alpha = if (canModify) 1.0f else 0.5f
                binding.buttonAddTraspaso.alpha = if (canModify) 1.0f else 0.5f

            } else { // CONGELADOR_04 Context
                binding.buttonAddCompra.visibility = View.GONE
                binding.buttonAddSalida.visibility = View.GONE
                binding.buttonAddTraspaso.visibility = View.GONE
                binding.buttonEditC04.visibility = View.VISIBLE // Este se eliminará en Fase 3
                binding.buttonTraspasoC04M.visibility = View.VISIBLE

                binding.buttonEditC04.isEnabled = canModify
                binding.buttonTraspasoC04M.isEnabled = canModify
                binding.buttonEditC04.alpha = if (canModify) 1.0f else 0.5f
                binding.buttonTraspasoC04M.alpha = if (canModify) 1.0f else 0.5f
            }

            binding.buttonAddCompra.setOnClickListener { if(canModify) listener.onAddCompraClicked(item) }
            binding.buttonAddSalida.setOnClickListener { if(canModify) listener.onSalidaClicked(item) }
            binding.buttonAddTraspaso.setOnClickListener { if(canModify) listener.onTraspasoC04Clicked(item) }
            binding.buttonEditC04.setOnClickListener { if(canModify) listener.onEditC04Clicked(item) }
            binding.buttonTraspasoC04M.setOnClickListener { if(canModify) listener.onTraspasoC04MClicked(item) }
            binding.root.setOnClickListener { listener.onItemClicked(item) }
        }

        companion object {
            fun from(parent: ViewGroup, userRole: UserRole?): ProductViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemProductBinding.inflate(layoutInflater, parent, false)
                return ProductViewHolder(binding, userRole)
            }
        }
    }
}

class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
    override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
        return oldItem == newItem // Comparación basada en data class
    }
}