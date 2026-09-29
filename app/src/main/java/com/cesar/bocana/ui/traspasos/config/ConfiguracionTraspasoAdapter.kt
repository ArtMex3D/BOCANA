package com.cesar.bocana.ui.traspasos.config

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.ItemConfiguracionTraspasoBinding
import java.text.DecimalFormat
import java.util.Collections

class ConfiguracionTraspasoAdapter(
    private val viewModel: ConfiguracionTraspasoViewModel,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ConfiguracionTraspasoAdapter.ConfigViewHolder>() {

    private val products = mutableListOf<Product>()
    private var expandedProductId: String? = null
    private var orderChanged = false

    init {
        setHasStableIds(true)
    }

    fun setProducts(newProducts: List<Product>) {
        // Mientras hay un orden local pendiente no permitimos que una emisión
        // antigua haga brincar visualmente la lista antes de guardar el drag.
        if (orderChanged && newProducts.map { it.id }.toSet() == products.map { it.id }.toSet()) {
            val byId = newProducts.associateBy { it.id }
            products.indices.forEach { index ->
                products[index] = byId[products[index].id] ?: products[index]
            }
            notifyItemRangeChanged(0, products.size)
            return
        }

        val old = products.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newProducts.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newProducts[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newProducts[newItemPosition]
        })
        products.clear()
        products.addAll(newProducts)
        if (expandedProductId != null && products.none { it.id == expandedProductId }) {
            expandedProductId = null
        }
        diff.dispatchUpdatesTo(this)
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in products.indices || toPosition !in products.indices) return false
        if (fromPosition == toPosition) return true
        Collections.swap(products, fromPosition, toPosition)
        orderChanged = true
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun consumeFinalOrder(): List<Product>? {
        if (!orderChanged) return null
        orderChanged = false
        return products.toList()
    }

    override fun getItemId(position: Int): Long = products[position].id.hashCode().toLong()

    override fun getItemCount(): Int = products.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
        return ConfigViewHolder(
            ItemConfiguracionTraspasoBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
        holder.bind(products[position])
    }

    inner class ConfigViewHolder(
        private val binding: ItemConfiguracionTraspasoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            val context = binding.root.context
            binding.textViewProductName.text = product.name
            binding.textViewOrder.text = (bindingAdapterPosition + 1).toString()
            binding.editTextStockIdeal.setText(formatNumber(product.stockIdealC04))
            binding.editTextEspacioExtra.setText(formatNumber(product.espacioExtraPDF))

            binding.textViewTipoEmpaque.text = if (product.requiresPackaging) {
                "Empaque: granel"
            } else {
                "Empaque: peso fijo (${product.unit})"
            }

            val rowColor = if (bindingAdapterPosition % 2 == 0) {
                R.color.bocana_surface
            } else {
                R.color.bocana_surface_alt
            }
            binding.root.setCardBackgroundColor(ContextCompat.getColor(context, rowColor))

            val isExpanded = expandedProductId == product.id
            binding.expandableLayout.isVisible = isExpanded
            binding.arrowIcon.animate().cancel()
            binding.arrowIcon.rotation = if (isExpanded) 180f else 0f

            binding.headerContainer.setOnClickListener {
                val previousId = expandedProductId
                val previousPosition = products.indexOfFirst { it.id == previousId }
                expandedProductId = if (expandedProductId == product.id) null else product.id
                if (previousPosition >= 0) notifyItemChanged(previousPosition)
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) notifyItemChanged(currentPosition)
            }

            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart(this)
                }
                false
            }

            addCommitListener(binding.editTextStockIdeal) { raw ->
                raw.toDoubleOrNull()?.coerceAtLeast(0.0)?.let { stockIdeal ->
                    if (stockIdeal != product.stockIdealC04) {
                        viewModel.updateProductConfig(product.id, "stockIdealC04", stockIdeal)
                    }
                }
            }

            addCommitListener(binding.editTextEspacioExtra) { raw ->
                raw.toDoubleOrNull()?.coerceIn(0.0, 10.0)?.let { extra ->
                    if (extra != product.espacioExtraPDF) {
                        viewModel.updateProductConfig(product.id, "espacioExtraPDF", extra)
                    }
                }
            }

            binding.switchModoManual.setOnCheckedChangeListener(null)
            binding.switchModoManual.isChecked = product.modoManualPDF
            binding.switchModoManual.setOnCheckedChangeListener { _, checked ->
                if (checked != product.modoManualPDF) {
                    viewModel.updateProductConfig(product.id, "modoManualPDF", checked)
                }
            }
        }

        private fun addCommitListener(editText: EditText, onCommit: (String) -> Unit) {
            editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) onCommit(editText.text?.toString().orEmpty())
            }
            editText.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    val keyboard = view.context
                        .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    keyboard.hideSoftInputFromWindow(view.windowToken, 0)
                    view.clearFocus()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun formatNumber(value: Double): String = DecimalFormat("0.##").format(value)
}
