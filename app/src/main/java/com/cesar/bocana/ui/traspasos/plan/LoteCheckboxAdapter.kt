package com.cesar.bocana.ui.traspasos.plan

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.Product // Importar Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.ItemLoteCheckboxBinding
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

interface LoteAdapterListener {
    fun onCheckboxToggled(lote: StockLot, isChecked: Boolean)
    fun onManualEditClicked(lote: StockLot)
}

// --- CONSTRUCTOR MODIFICADO ---
class LoteCheckboxAdapter(
    private val listener: LoteAdapterListener,
    private val isBulkProduct: Boolean // **CORREGIDO**: Recibe el flag del producto
) : ListAdapter<Pair<StockLot, Double?>, LoteCheckboxAdapter.LoteViewHolder>(LotDiffCallback()) {

    private var isModoDesglose = false
    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
    private var selectedIds = mutableSetOf<String>()

    fun setModoDesglose(isManual: Boolean) {
        if (isModoDesglose != isManual) {
            isModoDesglose = isManual
            notifyDataSetChanged() // Notificar para redibujar con la nueva vista
        }
    }

    fun setSelectedIds(ids: Set<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        if (!isModoDesglose) {
            notifyDataSetChanged() // Actualizar checkboxes si no estamos en modo desglose
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteViewHolder {
        val binding = ItemLoteCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LoteViewHolder(private val binding: ItemLoteCheckboxBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Pair<StockLot, Double?>) {
            val (lote, cantidadAsignada) = item
            val fecha = dateFormat.format(lote.receivedAt ?: Date())
            val proveedor = lote.supplierName ?: "S/P"

            val esGranel = isBulkProduct

            binding.textviewLoteInfo.text = "$fecha ($proveedor)"

            val esLoteEspecificoGranel = lote.unidadDeEmpaque.isNullOrBlank() || lote.pesoPorUnidad == null || lote.pesoPorUnidad <= 0.0
            if (esLoteEspecificoGranel) {
                binding.textviewLoteDetalle.text = "Disp: ${String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)} Kg"
            } else {
                val pesoUnidad = lote.pesoPorUnidad!!
                val unidadesDisponibles = floor(lote.currentQuantity / pesoUnidad).toInt()
                val unidad = lote.unidadDeEmpaque ?: "Unidad"
                binding.textviewLoteDetalle.text = "Disp: $unidadesDisponibles $unidad (${String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)} Kg)"
            }

            binding.checkboxLote.setOnCheckedChangeListener(null) // Limpiar listener anterior

            if (isModoDesglose) {
                // --- MODO DESGLOSE MANUAL ---
                binding.checkboxLote.visibility = View.GONE
                binding.inputLayoutCantidadDesglose.visibility = View.VISIBLE

                // **INICIO CORRECCIÓN INTERACCIÓN**
                // Deshabilitar la interacción directa con EditText y TextInputLayout
                binding.editTextCantidadDesglose.inputType = InputType.TYPE_NULL // Evitar teclado al tocar
                binding.editTextCantidadDesglose.isFocusable = false
                binding.editTextCantidadDesglose.isClickable = false
                binding.inputLayoutCantidadDesglose.isClickable = false // Hacer el layout no clicable
                binding.inputLayoutCantidadDesglose.isFocusable = false // Hacer el layout no enfocable
                // **FIN CORRECCIÓN INTERACCIÓN**

                if (esGranel) {
                    val cantidadKgTexto = if (cantidadAsignada != null && cantidadAsignada > 0.0) String.format(Locale.getDefault(), "%.2f", cantidadAsignada) else ""
                    binding.editTextCantidadDesglose.setText(cantidadKgTexto)
                    binding.inputLayoutCantidadDesglose.hint = "Kg"
                    binding.inputLayoutCantidadDesglose.suffixText = "Kg"
                } else {
                    val cantidadUnidadesTexto = if (cantidadAsignada != null && cantidadAsignada > 0.0) cantidadAsignada.toInt().toString() else ""
                    val unidadHint = lote.unidadDeEmpaque?.takeIf { it.isNotBlank() } ?: "Unidad"
                    binding.editTextCantidadDesglose.setText(cantidadUnidadesTexto)
                    binding.inputLayoutCantidadDesglose.hint = unidadHint
                    binding.inputLayoutCantidadDesglose.suffixText = unidadHint
                }

                // El listener ahora está en toda la fila (itemView)
                itemView.setOnClickListener { listener.onManualEditClicked(lote) }

            } else {
                // --- MODO CHECKBOX ---
                binding.checkboxLote.visibility = View.VISIBLE
                binding.inputLayoutCantidadDesglose.visibility = View.GONE

                binding.checkboxLote.isChecked = selectedIds.contains(lote.id)
                binding.checkboxLote.setOnCheckedChangeListener { _, isChecked ->
                    // Solo notificar si el modo NO es desglose (seguridad extra)
                    if (!isModoDesglose) {
                        listener.onCheckboxToggled(lote, isChecked)
                    }
                }
                // Permitir click en toda la fila para cambiar el checkbox
                itemView.setOnClickListener { binding.checkboxLote.toggle() }
            }
        }
    }

    // DiffCallback sin cambios
    class LotDiffCallback : DiffUtil.ItemCallback<Pair<StockLot, Double?>>() {
        override fun areItemsTheSame(oldItem: Pair<StockLot, Double?>, newItem: Pair<StockLot, Double?>): Boolean {
            return oldItem.first.id == newItem.first.id
        }
        override fun areContentsTheSame(oldItem: Pair<StockLot, Double?>, newItem: Pair<StockLot, Double?>): Boolean {
            // Comparar lote y cantidad asignada
            return oldItem.first == newItem.first && oldItem.second == newItem.second
        }
    }
}
