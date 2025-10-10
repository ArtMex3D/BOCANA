package com.cesar.bocana.ui.traspasos.config

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.ItemConfiguracionTraspasoBinding
import java.util.Collections

class ConfiguracionTraspasoAdapter(
    private val viewModel: ConfiguracionTraspasoViewModel
) : ListAdapter<Product, ConfiguracionTraspasoAdapter.ConfigViewHolder>(ProductDiffCallback()) {

    private val productList: MutableList<Product> = mutableListOf()
    private var expandedPosition = -1

    override fun submitList(list: List<Product>?) {
        super.submitList(list?.let { ArrayList(it) })
        productList.clear()
        if (list != null) {
            productList.addAll(list)
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < productList.size && toPosition < productList.size) {
            Collections.swap(productList, fromPosition, toPosition)
            notifyItemMoved(fromPosition, toPosition)
        }
    }

    fun getFinalOrder(): List<Product> {
        return productList
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
        val binding = ItemConfiguracionTraspasoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConfigViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConfigViewHolder(private val binding: ItemConfiguracionTraspasoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            binding.textViewProductName.text = product.name
            binding.editTextStockIdeal.setText(product.stockIdealC04.toString())

            val tipoEmpaqueText = if (product.requiresPackaging) "GRANEL" else "PESO FIJO (${product.unit})"
            binding.textViewTipoEmpaque.text = "Tipo: $tipoEmpaqueText"
            binding.editTextEspacioExtra.setText(product.espacioExtraPDF.toString())

            val context = binding.root.context
            if (bindingAdapterPosition % 2 == 0) {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.zebra_claro))
            } else {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.zebra_oscuro))
            }

            val isExpanded = bindingAdapterPosition == expandedPosition
            binding.expandableLayout.isVisible = isExpanded
            binding.arrowIcon.rotation = if (isExpanded) 180f else 0f

            binding.root.setOnClickListener {
                val previousExpandedPosition = expandedPosition
                expandedPosition = if (isExpanded) -1 else bindingAdapterPosition

                if (previousExpandedPosition != -1) {
                    notifyItemChanged(previousExpandedPosition)
                }
                notifyItemChanged(bindingAdapterPosition)
            }

            addTextWatcher(binding.editTextStockIdeal) {
                val stockIdeal = it.toDoubleOrNull() ?: 0.0
                if (stockIdeal != product.stockIdealC04) {
                    viewModel.updateProductConfig(product.id, "stockIdealC04", stockIdeal)
                }
            }

            addTextWatcher(binding.editTextEspacioExtra) {
                val espacioExtra = it.toDoubleOrNull() ?: 0.0
                if (espacioExtra != product.espacioExtraPDF) {
                    viewModel.updateProductConfig(product.id, "espacioExtraPDF", espacioExtra)
                }
            }

            // ***** INICIO DE LA SOLUCIÓN ROBUSTA PARA EL SWITCH *****
            // 1. Quita cualquier listener anterior para evitar que se dispare al reciclar la vista.
            binding.switchModoManual.setOnCheckedChangeListener(null)
            // 2. Asigna el estado correcto basado en los datos del producto actual.
            binding.switchModoManual.isChecked = product.modoManualPDF
            // 3. Vuelve a asignar el listener para capturar solo las interacciones del usuario.
            binding.switchModoManual.setOnCheckedChangeListener { _, isChecked ->
                // Solo se actualiza si el estado realmente ha cambiado para evitar escrituras innecesarias.
                if (isChecked != product.modoManualPDF) {
                    viewModel.updateProductConfig(product.id, "modoManualPDF", isChecked)
                }
            }
            // ***** FIN DE LA SOLUCIÓN *****
        }

        private fun addTextWatcher(editText: EditText, onUpdate: (String) -> Unit) {
            val context = editText.context
            editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    onUpdate(editText.text.toString())
                }
            }

            editText.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                    view.clearFocus()
                    onUpdate(editText.text.toString())
                    return@setOnKeyListener true
                }
                return@setOnKeyListener false
            }
        }
    }
}

class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
    override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
}

