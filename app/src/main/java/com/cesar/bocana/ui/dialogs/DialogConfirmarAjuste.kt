package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.google.android.material.button.MaterialButton
import java.util.Locale

class DialogConfirmarAjuste : DialogFragment() {

    private var products: List<Product> = emptyList()
    private var adjustments: Map<String, Double> = emptyMap()
    private var onConfirmListener: ((Map<String, Double>) -> Unit)? = null
    private var isConfirming = false

    companion object {
        const val TAG = "DialogConfirmarAjuste"

        fun newInstance(products: List<Product>, adjustments: Map<String, Double>): DialogConfirmarAjuste {
            val fragment = DialogConfirmarAjuste()
            fragment.products = products
            fragment.adjustments = adjustments
            return fragment
        }
    }

    fun setOnConfirmListener(listener: (Map<String, Double>) -> Unit) {
        onConfirmListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_confirmar_ajuste, null)

        // Separar productos CON cambio vs SIN cambio
        val itemsWithChange = mutableListOf<Product>()
        val itemsWithoutChange = mutableListOf<Product>()

        for (product in products) {
            val newStock = adjustments[product.id]
            if (newStock != null && kotlin.math.abs(product.stockCongelador04 - newStock) > 0.01) {
                itemsWithChange.add(product)
            } else {
                itemsWithoutChange.add(product)
            }
        }

        // Resumen
        val textViewResumen = view.findViewById<TextView>(R.id.textViewResumenCambios)
        textViewResumen.text = String.format(
            Locale.getDefault(),
            "Confirmar Ajuste | %d productos con cambio",
            itemsWithChange.size
        )

        // RecyclerView para productos CON cambios
        val recyclerViewWithChange = view.findViewById<RecyclerView>(R.id.recyclerViewResumenAjuste)
        recyclerViewWithChange.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewWithChange.adapter = ResumenAdapter(itemsWithChange, adjustments, showWithoutChange = false)

        // RecyclerView para productos SIN cambios
        val textViewSinCambios = view.findViewById<TextView>(R.id.textViewSinCambios)
        val recyclerViewWithoutChange = view.findViewById<RecyclerView>(R.id.recyclerViewSinCambios)

        if (itemsWithoutChange.isNotEmpty()) {
            textViewSinCambios.visibility = View.VISIBLE
            recyclerViewWithoutChange.visibility = View.VISIBLE
            recyclerViewWithoutChange.layoutManager = LinearLayoutManager(requireContext())
            recyclerViewWithoutChange.adapter = ResumenAdapter(itemsWithoutChange, adjustments, showWithoutChange = true)
        }

        val buttonConfirmar = view.findViewById<MaterialButton>(R.id.buttonConfirmar)
        val buttonCancelar = view.findViewById<MaterialButton>(R.id.buttonCancelar)

        // Detectar si hay cambios severos (para cambiar color del botón)
        val hasSevereChange = itemsWithChange.any { product ->
            val newStock = adjustments[product.id] ?: 0.0
            val currentStock = product.stockCongelador04
            if (currentStock > 0) {
                val difference = currentStock - newStock
                val percentage = (kotlin.math.abs(difference) / currentStock) * 100
                percentage >= 50
            } else false
        }

        if (hasSevereChange) {
            buttonConfirmar.text = "⚠️ CONFIRMAR (Cambio Alto) ⚠️"
            buttonConfirmar.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        }

        buttonConfirmar.setOnClickListener {
            if (isConfirming) return@setOnClickListener
            isConfirming = true

            if (hasSevereChange) {
                showSevereWarningDialog {
                    onConfirmListener?.invoke(adjustments)
                    dismiss()
                }
            } else {
                onConfirmListener?.invoke(adjustments)
                dismiss()
            }
        }

        buttonCancelar.setOnClickListener {
            isConfirming = false
            dismiss()
        }

        val dialog = builder.setView(view).create()
        return dialog
    }

    private fun showSevereWarningDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ CAMBIO ALTO DETECTADO ⚠️")
            .setMessage(
                "Hay productos con un ajuste del 50% o más.\n\n" +
                        "¿Estás SEGURO de que quieres registrar este ajuste?"
            )
            .setPositiveButton("Sí, confirmar") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                // 🔥 RESETEAR isConfirming AL CANCELAR
                isConfirming = false
            }
            .setCancelable(false)
            .show()
    }

    inner class ResumenAdapter(
        private val items: List<Product>,
        private val adjustments: Map<String, Double>,
        private val showWithoutChange: Boolean = false
    ) : RecyclerView.Adapter<ResumenAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_resumen_ajuste, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val product = items[position]
            val newStock = adjustments[product.id]
            val currentStock = product.stockCongelador04

            holder.textProductName.text = product.name

            if (showWithoutChange || newStock == null || kotlin.math.abs(currentStock - newStock) <= 0.01) {
                // Sin cambios
                holder.textChangeDetails.text = "✓ Sin cambios"
                holder.textChangeDetails.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
                holder.textProductName.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
            } else {
                // Con cambios
                val difference = currentStock - newStock
                val differenceAbs = kotlin.math.abs(difference)
                val percentage = if (currentStock > 0) (differenceAbs / currentStock) * 100 else 0.0

                val isSevere = percentage >= 50
                val changeText = if (difference > 0) "📉 Ajuste" else "📈 Aumento"
                val severityText = if (isSevere) "⚠️ Cambio alto" else "🟢 Cambio normal"

                holder.textChangeDetails.text = String.format(
                    Locale.getDefault(),
                    "%s: %.2f → %.2f %s | %s",
                    changeText,
                    currentStock,
                    newStock,
                    product.unit,
                    severityText
                )

                // Color según severidad
                val color = when {
                    isSevere -> android.R.color.holo_red_dark
                    else -> android.R.color.holo_green_dark
                }

                holder.textChangeDetails.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
                holder.textProductName.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textProductName: TextView = view.findViewById(R.id.textProductNameResumen)
            val textChangeDetails: TextView = view.findViewById(R.id.textChangeDetails)
        }
    }
}