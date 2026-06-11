package com.cesar.bocana.ui.traspasos.plan

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.ItemLoteCheckboxBinding
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

// Interfaz para comunicar los clics al DialogFragment
interface LoteAdapterListener {
    fun onCheckboxToggled(lote: StockLot, isChecked: Boolean)
    fun onManualEditClicked(lote: StockLot)
}

// --- CONSTRUCTOR RESCATADO: Sabe si es granel o no ---
class LoteCheckboxAdapter(
    private val listener: LoteAdapterListener,
    private val isBulkProduct: Boolean
) : ListAdapter<Pair<StockLot, Double?>, LoteCheckboxAdapter.LoteViewHolder>(LotDiffCallback()) { // RESCATADO: Usa Double? para soportar Kilos

    private var isModoDesglose = false
    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
    private var selectedIds = mutableSetOf<String>()

    fun setModoDesglose(isManual: Boolean) {
        if (isModoDesglose != isManual) {
            isModoDesglose = isManual
            notifyDataSetChanged()
        }
    }

    fun setSelectedIds(ids: Set<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        if (!isModoDesglose) {
            notifyDataSetChanged()
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
                val pesoUnidad = lote.pesoPorUnidad ?: 1.0
                val unidadesDisponibles = floor(lote.currentQuantity / pesoUnidad).toInt()
                val unidad = lote.unidadDeEmpaque ?: "Unidad"
                binding.textviewLoteDetalle.text = "Disp: $unidadesDisponibles $unidad (${String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)} Kg)"
            }

            binding.checkboxLote.setOnCheckedChangeListener(null)

            if (isModoDesglose) {
                binding.checkboxLote.visibility = View.GONE
                binding.inputLayoutCantidadDesglose.visibility = View.VISIBLE

                // Bloqueo robusto de clics directos
                binding.editTextCantidadDesglose.inputType = InputType.TYPE_NULL
                binding.editTextCantidadDesglose.isFocusable = false
                binding.editTextCantidadDesglose.isClickable = false
                binding.inputLayoutCantidadDesglose.isClickable = false
                binding.inputLayoutCantidadDesglose.isFocusable = false

                // RESCATADO: Formateo correcto dependiendo si son Kilos (decimales) o Unidades (enteros)
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

                // Asignamos un único listener a TODA la fila.
                itemView.setOnClickListener {
                    listener.onManualEditClicked(lote)
                }

            } else {
                binding.checkboxLote.visibility = View.VISIBLE
                binding.inputLayoutCantidadDesglose.visibility = View.GONE
                binding.checkboxLote.isChecked = selectedIds.contains(lote.id)

                itemView.setOnClickListener { binding.checkboxLote.toggle() }

                binding.checkboxLote.setOnCheckedChangeListener { _, isChecked ->
                    if (!isModoDesglose) {
                        listener.onCheckboxToggled(lote, isChecked)
                    }
                }
            }
        }
    }

    class LotDiffCallback : DiffUtil.ItemCallback<Pair<StockLot, Double?>>() {
        override fun areItemsTheSame(oldItem: Pair<StockLot, Double?>, newItem: Pair<StockLot, Double?>): Boolean {
            return oldItem.first.id == newItem.first.id
        }
        override fun areContentsTheSame(oldItem: Pair<StockLot, Double?>, newItem: Pair<StockLot, Double?>): Boolean {
            return oldItem.first == newItem.first && oldItem.second == newItem.second
        }
    }
}