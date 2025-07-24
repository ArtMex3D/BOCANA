package com.cesar.bocana.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.InventoryRepository
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.databinding.ItemStockMovementBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class StockMovementAdapter(
    private val repository: InventoryRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) : PagingDataAdapter<StockMovement, StockMovementAdapter.StockMovementViewHolder>(STOCK_MOVEMENT_COMPARATOR) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StockMovementViewHolder {
        val binding = ItemStockMovementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StockMovementViewHolder(binding, repository, lifecycleScope)
    }

    override fun onBindViewHolder(holder: StockMovementViewHolder, position: Int) {
        val currentItem = getItem(position)
        if (currentItem != null) {
            holder.bind(currentItem)
        }
    }

    class StockMovementViewHolder(
        private val binding: ItemStockMovementBinding,
        private val repository: InventoryRepository,
        private val lifecycleScope: LifecycleCoroutineScope
    ) : RecyclerView.ViewHolder(binding.root) {

        private val movementDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        private val lotDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

        fun bind(movement: StockMovement) {
            binding.apply {
                val context = itemView.context

                val quantitySign = when (movement.type) {
                    MovementType.COMPRA, MovementType.AJUSTE_POSITIVO -> "+"
                    MovementType.SALIDA_CONSUMO, MovementType.SALIDA_DEVOLUCION, MovementType.AJUSTE_NEGATIVO, MovementType.BAJA_PRODUCTO, MovementType.AJUSTE_STOCK_C04, MovementType.SALIDA_CONSUMO_C04 -> "-"
                    else -> ""
                }
                val formattedQuantity = String.format(Locale.getDefault(), "%.2f", movement.quantity)
                movementTitle.text = "${movement.productName}: $quantitySign$formattedQuantity"

                val movementTypeName = movement.type.name.replace('_', ' ')
                movementSubtitle.text = "$movementTypeName por ${movement.userName}"

                movementDate.text = movement.timestamp?.let { movementDateFormat.format(it) } ?: "Fecha no disponible"

                if (!movement.affectedLotIds.isNullOrEmpty()) {
                    movementLots.visibility = View.VISIBLE
                    movementLots.text = "Cargando lote..."

                    lifecycleScope.launch {
                        try {
                            val lotDates = movement.affectedLotIds.mapNotNull { lotId ->
                                repository.getLotById(lotId)?.receivedAt
                            }.map { date ->
                                lotDateFormat.format(date)
                            }.distinct() // Muestra fechas únicas si varios lotes son del mismo día

                            if (lotDates.isNotEmpty()) {
                                movementLots.text = "Lote(s) del: ${lotDates.joinToString()}"
                            } else {
                                movementLots.text = "Lote: ID no encontrado"
                            }
                        } catch (e: Exception) {
                            movementLots.text = "Lote: (Error)"
                        }
                    }
                } else {
                    movementLots.visibility = View.GONE
                }

                val indicatorColor = when (movement.type) {
                    MovementType.COMPRA, MovementType.AJUSTE_POSITIVO -> ContextCompat.getColor(context, R.color.positive_green)
                    MovementType.SALIDA_CONSUMO -> ContextCompat.getColor(context, R.color.teal_700)
                    MovementType.SALIDA_DEVOLUCION, MovementType.AJUSTE_NEGATIVO, MovementType.BAJA_PRODUCTO, MovementType.AJUSTE_STOCK_C04 -> ContextCompat.getColor(context, R.color.negative_red)
                    MovementType.TRASPASO_M_C04, MovementType.TRASPASO_C04_M -> ContextCompat.getColor(context, R.color.transfer_yellow)
                    else -> ContextCompat.getColor(context, R.color.gray_400)
                }
                movementColorIndicator.setBackgroundColor(indicatorColor)
            }
        }
    }

    companion object {
        private val STOCK_MOVEMENT_COMPARATOR = object : DiffUtil.ItemCallback<StockMovement>() {
            override fun areItemsTheSame(oldItem: StockMovement, newItem: StockMovement): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: StockMovement, newItem: StockMovement): Boolean =
                oldItem == newItem
        }
    }
}