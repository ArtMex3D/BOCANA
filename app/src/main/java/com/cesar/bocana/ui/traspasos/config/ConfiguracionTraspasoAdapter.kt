// main/java/com/cesar/bocana/ui/traspasos/config/ConfiguracionTraspasoAdapter.kt
package com.cesar.bocana.ui.traspasos.config

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.PrioridadDesabasto // Importar constantes
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.ItemConfiguracionTraspasoBinding
import com.google.android.material.chip.ChipGroup
import java.util.Collections

class ConfiguracionTraspasoAdapter(
    private val viewModel: ConfiguracionTraspasoViewModel
) : ListAdapter<Product, ConfiguracionTraspasoAdapter.ConfigViewHolder>(ProductDiffCallback()) {

    private val productList: MutableList<Product> = mutableListOf()
    private var expandedPosition = -1

    override fun submitList(list: List<Product>?) {
        // Importante: Crear una nueva lista para DiffUtil
        super.submitList(list?.let { ArrayList(it) })
        // Actualizar la lista interna que usa el Drag&Drop
        productList.clear()
        if (list != null) {
            productList.addAll(list)
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < productList.size && toPosition < productList.size) {
            // Mover en la lista interna
            Collections.swap(productList, fromPosition, toPosition)
            // Notificar al RecyclerView para la animación visual
            notifyItemMoved(fromPosition, toPosition)
            // Podrías llamar a viewModel.updateProductOrder aquí si quieres guardar en cada movimiento,
            // pero es más eficiente guardarlo en clearView del ItemTouchHelper.
        }
    }

    fun getFinalOrder(): List<Product> {
        // Devuelve la lista interna que refleja el orden del usuario
        return ArrayList(productList) // Devolver una copia
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
        // Usar la lista interna 'productList' que mantiene el orden del drag & drop
        holder.bind(productList[position])
    }

    inner class ConfigViewHolder(private val binding: ItemConfiguracionTraspasoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Listener del ChipGroup se configura una vez aquí para eficiencia
            // Usamos setOnCheckedStateChangeListener que funciona mejor con singleSelection
            binding.chipGroupPrioridad.setOnCheckedStateChangeListener { group, checkedIds ->
                // Solo reaccionar si la posición es válida y la vista está expandida
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition == expandedPosition) {
                    // Obtener el producto de la lista interna (la que usa Drag&Drop)
                    val product = productList[currentPosition]
                    val nuevaPrioridad = if (checkedIds.isNotEmpty()) {
                        when (checkedIds[0]) { // Tomamos el primer ID (y único en singleSelection)
                            R.id.chipPrioridadAlta -> PrioridadDesabasto.ALTA
                            R.id.chipPrioridadBaja -> PrioridadDesabasto.BAJA
                            R.id.chipPrioridadMedia -> PrioridadDesabasto.MEDIA
                            else -> product.prioridadDesabasto // Mantener actual si hay error
                        }
                    } else {
                        // Si se deselecciona (aunque no debería pasar con singleSelection), mantener la actual o poner default
                        PrioridadDesabasto.MEDIA // O product.prioridadDesabasto
                    }

                    if (nuevaPrioridad != product.prioridadDesabasto) {
                        Log.d("AdapterConfig", "Prioridad cambiada (Chip Listener) para ${product.name} a $nuevaPrioridad")
                        viewModel.updateProductConfig(product.id, "prioridadDesabasto", nuevaPrioridad)
                    }
                }
            }
        }


        fun bind(product: Product) {
            binding.textViewProductName.text = product.name
            // Limpiar listeners antes de setear texto para evitar llamadas recursivas
            binding.editTextStockIdeal.onFocusChangeListener = null
            binding.editTextStockMaximo.onFocusChangeListener = null
            binding.editTextEspacioExtra.onFocusChangeListener = null

            binding.editTextStockIdeal.setText(product.stockIdealC04.toString())
            binding.editTextStockMaximo.setText(product.stockMaximoC04.toString()) // Poblar nuevo campo

            // --- Seleccionar Chip de Prioridad correcto ---
            binding.chipGroupPrioridad.setOnCheckedStateChangeListener(null) // Desactivar listener temporalmente
            when (product.prioridadDesabasto) {
                PrioridadDesabasto.ALTA -> binding.chipPrioridadAlta.isChecked = true
                PrioridadDesabasto.MEDIA -> binding.chipPrioridadMedia.isChecked = true
                PrioridadDesabasto.BAJA -> binding.chipPrioridadBaja.isChecked = true
                // Asegurarnos de desmarcar los otros explícitamente si usamos chequeo manual
                // O simplemente confiar en clearCheck() antes de marcar el correcto
                else -> binding.chipGroupPrioridad.clearCheck()
            }
            // Volver a asignar el listener después de establecer el estado inicial
            binding.chipGroupPrioridad.setOnCheckedStateChangeListener { group, checkedIds ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition == expandedPosition) {
                    val currentProduct = productList[currentPosition]
                    val nuevaPrioridad = if (checkedIds.isNotEmpty()){
                        when (checkedIds[0]) {
                            R.id.chipPrioridadAlta -> PrioridadDesabasto.ALTA
                            R.id.chipPrioridadBaja -> PrioridadDesabasto.BAJA
                            R.id.chipPrioridadMedia -> PrioridadDesabasto.MEDIA
                            else -> currentProduct.prioridadDesabasto
                        }
                    } else {
                        PrioridadDesabasto.MEDIA // O currentProduct.prioridadDesabasto
                    }

                    if (nuevaPrioridad != currentProduct.prioridadDesabasto) {
                        Log.d("AdapterConfig", "Prioridad cambiada (Re-assigned Listener) para ${currentProduct.name} a $nuevaPrioridad")
                        viewModel.updateProductConfig(currentProduct.id, "prioridadDesabasto", nuevaPrioridad)
                    }
                }
            }


            val tipoEmpaqueText = if (product.requiresPackaging) "GRANEL" else "PESO FIJO (${product.unit})"
            binding.textViewTipoEmpaque.text = "Tipo: $tipoEmpaqueText"
            binding.editTextEspacioExtra.setText(product.espacioExtraPDF.toString())

            val context = binding.root.context
            if (bindingAdapterPosition % 2 == 0) {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.zebra_claro))
            } else {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.zebra_oscuro))
            }

            // Manejo de expansión con la variable de clase
            val isExpanded = bindingAdapterPosition == expandedPosition
            binding.expandableLayout.isVisible = isExpanded
            binding.arrowIcon.rotation = if (isExpanded) 180f else 0f

            // Listener para expandir/colapsar
            binding.root.setOnClickListener {
                val clickedPosition = bindingAdapterPosition
                if (clickedPosition == RecyclerView.NO_POSITION) return@setOnClickListener // Evitar crash si el item se está eliminando

                val previousExpandedPosition = expandedPosition
                expandedPosition = if (isExpanded) -1 else clickedPosition // Actualiza la variable de clase

                // Notificar cambios para que se redibujen los items afectados
                // Colapsa el anterior si existía y no es el mismo que el actual
                if (previousExpandedPosition != -1 && previousExpandedPosition != expandedPosition) {
                    notifyItemChanged(previousExpandedPosition)
                }
                // Expande/colapsa el actual
                notifyItemChanged(clickedPosition)

            }


            // Listeners para guardar cambios en EditText (incluyendo el nuevo)
            addTextWatcher(binding.editTextStockIdeal) { newValueStr ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    val currentProduct = productList[currentPosition] // Usar productList
                    val stockIdeal = newValueStr.toDoubleOrNull() ?: 0.0
                    if (stockIdeal != currentProduct.stockIdealC04) {
                        // Validar contra el máximo actual en memoria (o en el EditText)
                        val stockMaximoActual = binding.editTextStockMaximo.text.toString().toDoubleOrNull() ?: currentProduct.stockMaximoC04
                        if (stockIdeal > stockMaximoActual && stockMaximoActual > 0) { // Permitir si max es 0
                            Toast.makeText(binding.root.context, "El Stock Mínimo no puede ser mayor al Máximo", Toast.LENGTH_SHORT).show()
                            binding.editTextStockIdeal.setText(currentProduct.stockIdealC04.toString()) // Revertir
                        } else {
                            viewModel.updateProductConfig(currentProduct.id, "stockIdealC04", stockIdeal)
                        }
                    }
                }
            }
            // Añadir listener para stockMaximo
            addTextWatcher(binding.editTextStockMaximo) { newValueStr ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    val currentProduct = productList[currentPosition] // Usar productList
                    val stockMaximo = newValueStr.toDoubleOrNull() ?: 0.0
                    if (stockMaximo != currentProduct.stockMaximoC04) {
                        // Validar contra el ideal(mínimo) actual en memoria (o en el EditText)
                        val stockIdealActual = binding.editTextStockIdeal.text.toString().toDoubleOrNull() ?: currentProduct.stockIdealC04
                        if (stockMaximo < stockIdealActual && stockMaximo > 0) { // Permitir si es 0
                            Toast.makeText(binding.root.context, "El Stock Máximo no puede ser menor al Mínimo", Toast.LENGTH_SHORT).show()
                            binding.editTextStockMaximo.setText(currentProduct.stockMaximoC04.toString()) // Revertir
                        } else {
                            viewModel.updateProductConfig(currentProduct.id, "stockMaximoC04", stockMaximo)
                        }
                    }
                }
            }

            addTextWatcher(binding.editTextEspacioExtra) { newValueStr ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    val currentProduct = productList[currentPosition] // Usar productList
                    val espacioExtra = newValueStr.toDoubleOrNull() ?: 0.0
                    if (espacioExtra != currentProduct.espacioExtraPDF) {
                        viewModel.updateProductConfig(currentProduct.id, "espacioExtraPDF", espacioExtra)
                    }
                }
            }

            // Listener para el Switch modoManualPDF (sin cambios aparentes necesarios)
            binding.switchModoManual.setOnCheckedChangeListener(null)
            binding.switchModoManual.isChecked = product.modoManualPDF
            binding.switchModoManual.setOnCheckedChangeListener { _, isChecked ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    val currentProduct = productList[currentPosition] // Usar productList
                    if (isChecked != currentProduct.modoManualPDF) {
                        viewModel.updateProductConfig(currentProduct.id, "modoManualPDF", isChecked)
                    }
                }
            }
        }

        private fun addTextWatcher(editText: EditText, onUpdate: (String) -> Unit) {
            val context = editText.context
            // Limpiar listener anterior para evitar duplicados al reciclar
            editText.onFocusChangeListener = null
            editText.setOnKeyListener(null)

            editText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    // Solo actualizar si la posición es válida
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onUpdate((v as EditText).text.toString())
                    }
                }
            }

            editText.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Solo actualizar si la posición es válida
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                        v.clearFocus() // Esto disparará el onFocusChangeListener
                        return@setOnKeyListener true
                    }
                }
                return@setOnKeyListener false
            }
        }
    }
}

// ProductDiffCallback sin cambios
class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
    override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
    // Comparar contenido incluyendo los nuevos campos
    override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
}