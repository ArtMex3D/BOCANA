package com.cesar.bocana.ui.ajustecompleto

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.ItemAjusteCompletoRowBinding
import java.util.Locale

class AjusteCompletoAdapter(
    private val onStockChanged: (productId: String, physicalStock: Double?, maxStock: Double) -> Unit,
    private val onEditorActionNext: (position: Int) -> Unit = {},
    private val onEditorActionDone: () -> Unit = {}
) : ListAdapter<Product, AjusteCompletoAdapter.ProductViewHolder>(ProductDiffCallback()) {

    private val stockValues = mutableMapOf<String, Double?>()
    private val TAG = "AjusteCompletoAdapter"

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val binding = ItemAjusteCompletoRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ProductViewHolder(
            binding,
            onStockChanged,
            stockValues,
            onEditorActionNext,
            onEditorActionDone
        )
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(getItem(position), position, itemCount)
    }

    fun getPhysicalStocks(): Map<String, Double?> = stockValues.toMap()

    // 🔥 NUEVA FUNCIÓN: Sincronizar drafts desde el Fragmento
    fun setDrafts(drafts: Map<String, Double>) {
        stockValues.clear()
        stockValues.putAll(drafts)
        notifyDataSetChanged()
        Log.d(TAG, "setDrafts: ${drafts.size} drafts sincronizados")
    }

    fun clearStocks() {
        stockValues.clear()
        notifyDataSetChanged()
    }

    class ProductViewHolder(
        private val binding: ItemAjusteCompletoRowBinding,
        private val onStockChanged: (productId: String, physicalStock: Double?, maxStock: Double) -> Unit,
        private val stockValues: MutableMap<String, Double?>,
        private val onEditorActionNext: (position: Int) -> Unit,
        private val onEditorActionDone: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentProductId: String = ""
        private var currentProduct: Product? = null
        private var currentPosition: Int = 0
        private val TAG = "ProductViewHolder"

        fun bind(product: Product, position: Int, totalItems: Int) {
            currentProductId = product.id
            currentProduct = product
            currentPosition = position

            Log.d(TAG, "bind: ${product.name}, posición $position de $totalItems, teórico: ${product.stockCongelador04}")

            binding.textViewProductName.text = product.name

            val theoreticalStock = product.stockCongelador04
            binding.textViewTheoreticalStock.text = String.format(
                Locale.getDefault(),
                "📦 Stock teórico: %.2f %s",
                theoreticalStock,
                product.unit
            )

            // 🔥 FIX: Remover TextWatcher anterior
            val oldWatcher = binding.editTextPhysicalStock.getTag(R.id.textWatcher) as? android.text.TextWatcher
            oldWatcher?.let {
                binding.editTextPhysicalStock.removeTextChangedListener(it)
                Log.d(TAG, "TextWatcher removido para ${product.name}")
            }

            binding.editTextPhysicalStock.error = null

            // 🔥 FIX CRÍTICO: Usar toString() en lugar de String.format para evitar comas en español
            val savedValue = stockValues[product.id]
            if (savedValue != null) {
                binding.editTextPhysicalStock.setText(savedValue.toString())
                Log.d(TAG, "Valor restaurado para ${product.name}: $savedValue")
            } else {
                binding.editTextPhysicalStock.setText("")
                Log.d(TAG, "Campo limpiado para ${product.name}")
            }

            // Configurar acción del teclado
            if (position == totalItems - 1) {
                binding.editTextPhysicalStock.imeOptions = EditorInfo.IME_ACTION_DONE
                binding.editTextPhysicalStock.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        Log.d(TAG, "ACTION_DONE para ${product.name}")
                        saveCurrentValue(product)
                        onEditorActionDone()
                        true
                    } else false
                }
            } else {
                binding.editTextPhysicalStock.imeOptions = EditorInfo.IME_ACTION_NEXT
                binding.editTextPhysicalStock.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_NEXT) {
                        Log.d(TAG, "ACTION_NEXT para ${product.name}")
                        saveCurrentValue(product)
                        onEditorActionNext(position + 1)
                        true
                    } else false
                }
            }

            // Crear nuevo TextWatcher
            val textWatcher = object : android.text.TextWatcher {
                private var isUpdating = false

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: android.text.Editable?) {
                    if (isUpdating) return
                    isUpdating = true

                    val text = s.toString()

                    if (text.isBlank()) {
                        val oldValue = stockValues[currentProductId]
                        if (oldValue != null) {
                            stockValues.remove(currentProductId)
                            onStockChanged(currentProductId, null, product.stockCongelador04)
                            Log.d(TAG, "Valor eliminado para ${product.name}")
                        }
                    } else {
                        val inputValue = text.toDoubleOrNull()
                        if (inputValue != null && inputValue > product.stockCongelador04 + 0.01) {
                            Log.w(TAG, "Validación fallida: $inputValue > ${product.stockCongelador04}")
                            binding.editTextPhysicalStock.error = "⚠️ No puede exceder el stock teórico"
                        } else if (inputValue != null) {
                            binding.editTextPhysicalStock.error = null
                            val oldValue = stockValues[currentProductId]
                            if (oldValue != inputValue) {
                                stockValues[currentProductId] = inputValue
                                onStockChanged(currentProductId, inputValue, product.stockCongelador04)
                                Log.d(TAG, "Valor guardado para ${product.name}: $inputValue")
                            }
                        }
                    }
                    isUpdating = false
                }
            }

            binding.editTextPhysicalStock.addTextChangedListener(textWatcher)
            binding.editTextPhysicalStock.setTag(R.id.textWatcher, textWatcher)
        }

        private fun saveCurrentValue(product: Product) {
            val text = binding.editTextPhysicalStock.text.toString()
            val physicalStock = if (text.isBlank()) {
                null
            } else {
                text.toDoubleOrNull()
            }

            val oldValue = stockValues[currentProductId]
            if (oldValue != physicalStock) {
                if (physicalStock != null) {
                    stockValues[currentProductId] = physicalStock
                } else {
                    stockValues.remove(currentProductId)
                }
                onStockChanged(currentProductId, physicalStock, product.stockCongelador04)
                Log.d(TAG, "saveCurrentValue: ${product.name} cambió de $oldValue a $physicalStock")
            }
        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem.id == newItem.id &&
                    oldItem.name == newItem.name &&
                    oldItem.stockCongelador04 == newItem.stockCongelador04 &&
                    oldItem.unit == newItem.unit
        }
    }
}