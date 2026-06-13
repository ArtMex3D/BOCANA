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
import com.google.android.material.button.MaterialButton  // ← IMPORT NUEVO
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

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewResumenAjuste)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val items = products.filter { adjustments.containsKey(it.id) }
        val adapter = ResumenAdapter(items, adjustments)
        recyclerView.adapter = adapter

        val textViewResumen = view.findViewById<TextView>(R.id.textViewResumenCambios)
        val stats = calculateStats(items, adjustments)
        textViewResumen.text = String.format(
            Locale.getDefault(),
            "📊 Resumen: %d producto(s) | ⚠️ Severos: %d | 🟢 Leves: %d",
            stats.total,
            stats.severe,
            stats.light
        )

        val buttonConfirmar = view.findViewById<MaterialButton>(R.id.buttonConfirmar)  // ← SIMPLIFICADO
        val buttonCancelar = view.findViewById<MaterialButton>(R.id.buttonCancelar)    // ← SIMPLIFICADO

        if (stats.severe > 0) {
            buttonConfirmar.text = "⚠️ CONFIRMAR AJUSTE (Riesgo Alto) ⚠️"
            buttonConfirmar.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        }

        buttonConfirmar.setOnClickListener {
            if (isConfirming) return@setOnClickListener
            isConfirming = true

            if (stats.severe > 0) {
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
            dismiss()
        }

        val dialog = builder.create()  // ✅ Crear diálogo UNA vez
        return dialog  // ✅ Retornar el mismo diálogo
    }


    private fun calculateStats(
        items: List<Product>,
        adjustments: Map<String, Double>
    ): Stats {
        var severe = 0
        var light = 0

        for (product in items) {
            val newStock = adjustments[product.id] ?: continue
            val currentStock = product.stockCongelador04
            val difference = kotlin.math.abs(currentStock - newStock)

            if (currentStock > 0) {
                val percentage = (difference / currentStock) * 100
                if (percentage >= 50 || newStock == 0.0) {
                    severe++
                } else if (percentage > 0) {
                    light++
                }
            } else if (currentStock == 0.0 && newStock > 0) {
                // Aumento desde cero (caso especial)
                light++
            }
        }

        return Stats(items.size, severe, light)
    }

    private fun showSevereWarningDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ ADVERTENCIA DE STOCK CRÍTICO ⚠️")
            .setMessage(
                "Hay productos con ajuste alto, 50% o más.\n\n" +
                        "Esto significa que más de la mitad del stock desaparecera.\n\n" +
                        "¿Estás SEGURO de que quieres registrar este ajuste?"
            )
            .setPositiveButton("Sí, confirmar ajuste") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("Cancelar", null)
            .setCancelable(false)
            .show()
    }

    data class Stats(val total: Int, val severe: Int, val light: Int)

    inner class ResumenAdapter(
        private val items: List<Product>,
        private val adjustments: Map<String, Double>
    ) : RecyclerView.Adapter<ResumenAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_resumen_ajuste, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val product = items[position]
            val newStock = adjustments[product.id] ?: 0.0
            val currentStock = product.stockCongelador04
            val difference = currentStock - newStock
            val differenceAbs = kotlin.math.abs(difference)
            val isIncrease = difference < 0

            // Calcular porcentaje de cambio
            val percentage = if (currentStock > 0) (differenceAbs / currentStock) * 100 else 0.0

            // Determinar color y severidad según reglas:
            // - Gris: Sin cambio significativo (diferencia < 0.01 kg)
            // - Verde: Pérdida del 1% al 49% (merma leve)
            // - Rojo: Pérdida del 50% al 100% (merma severa) o stock llega a cero
            val (color, severityText) = when {
                kotlin.math.abs(difference) <= 0.01 ->
                    Pair(android.R.color.darker_gray, "Sin cambios")
                percentage <= 49 && difference > 0 ->
                    Pair(android.R.color.holo_green_dark, "Cambio bajo")
                difference < 0 ->
                    Pair(android.R.color.holo_orange_dark, "Cambio medio")
                else ->
                    Pair(android.R.color.holo_red_dark, "⚠️ Cambio alto")
            }

            // Formatear el texto principal
            val changeSymbol = when {
                kotlin.math.abs(difference) <= 0.01 -> "●"
                difference > 0 -> "▼"
                else -> "▲"
            }

            holder.textProductName.text = product.name
            holder.textProductName.setTextColor(ContextCompat.getColor(holder.itemView.context, color))

            holder.textChangeDetails.text = String.format(
                Locale.getDefault(),
                "%s %s: %.2f → %.2f %s | %s (%.1f%%)",
                changeSymbol,
                if (difference > 0) "Pérdida" else "Ganancia",
                currentStock,
                newStock,
                product.unit,
                severityText,
                percentage
            )
            holder.textChangeDetails.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textProductName: TextView = view.findViewById(R.id.textProductNameResumen)
            val textChangeDetails: TextView = view.findViewById(R.id.textChangeDetails)
        }
    }
}