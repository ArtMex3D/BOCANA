package com.cesar.bocana.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockMovement
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class StockMovementAdapter(private val context: Context) :
    ListAdapter<StockMovement, StockMovementAdapter.MovementViewHolder>(MovementDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm", Locale.getDefault())
    private val quantityFormat = DecimalFormat("#,##0.##")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovementViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stock_movement, parent, false)
        return MovementViewHolder(view)
    }

    override fun onBindViewHolder(holder: MovementViewHolder, position: Int) {
        val movement = getItem(position)
        holder.bind(movement)
    }

    inner class MovementViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.movement_color_indicator)
        private val title: TextView = itemView.findViewById(R.id.movement_title)
        private val subtitle: TextView = itemView.findViewById(R.id.movement_subtitle)
        private val date: TextView = itemView.findViewById(R.id.movement_date)

        fun bind(movement: StockMovement) {
            val movementTypeString = movement.type?.name ?: "DESCONOCIDO"

            // --- CORRECCIÓN 1: Lógica de Signo (+/-) ---
            // El formateador de números ya añade el "-" a los negativos.
            // Nosotros solo añadimos el "+" explícitamente a las entradas.
            // Los ajustes negativos y salidas mostrarán correctamente su signo "-".
            val isPositiveMovement = movement.quantity > 0
            val quantitySign = if (isPositiveMovement) "+" else ""
            val formattedQuantity = quantityFormat.format(movement.quantity)

            // Hemos quitado la referencia a '.unit' que causaba el error anterior
            title.text = "${movement.productName}: ${quantitySign}${formattedQuantity}"

            val formattedMovementType = movementTypeString.replace("_", " ").uppercase(Locale.ROOT)
            subtitle.text = "$formattedMovementType por ${movement.userName}"

            movement.timestamp?.let {
                date.text = dateFormat.format(it)
            }

            // --- CORRECCIÓN 2: Lógica de Colores ---
            // Añadimos "AJUSTE_C04" a la categoría de movimientos negativos (rojo).
            // Hacemos la comprobación de "TRASPASO" más robusta.
            val colorRes = when {
                movementTypeString in listOf("COMPRA", "AJUSTE_POSITIVO", "DEVOLUCION_CLIENTE") -> R.color.positive_green
                movementTypeString in listOf("SALIDA_CONSUMO", "DEVOLUCION_PROVEEDOR", "AJUSTE_NEGATIVO", "BAJA_PRODUCTO", "AJUSTE_C04",  "AJUSTE_STOCK_C04") -> R.color.negative_red
                movementTypeString.contains("TRASPASO") -> R.color.transfer_yellow
                else -> R.color.gray_400
            }
            colorIndicator.setBackgroundColor(ContextCompat.getColor(context, colorRes))
        }
    }
}

class MovementDiffCallback : DiffUtil.ItemCallback<StockMovement>() {
    override fun areItemsTheSame(oldItem: StockMovement, newItem: StockMovement): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: StockMovement, newItem: StockMovement): Boolean {
        return oldItem == newItem
    }
}