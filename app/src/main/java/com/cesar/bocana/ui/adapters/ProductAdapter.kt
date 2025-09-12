package com.cesar.bocana.ui.adapters

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.UserRole
import com.cesar.bocana.databinding.ItemProductBinding
import com.cesar.bocana.utils.NetworkStatus.isOnline
import java.text.SimpleDateFormat
import java.util.Locale

interface ProductActionListener {
    fun onAddCompraClicked(product: Product)
    fun onSalidaClicked(product: Product, anchorView: View)
    fun onTraspasoC04Clicked(product: Product, anchorView: View)
    fun onItemClicked(product: Product)
    fun onEditC04Clicked(product: Product)
    fun onTraspasoC04MClicked(product: Product)
}

class ProductAdapter(
    private val actionListener: ProductActionListener,
    private var currentUserRole: UserRole?
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(ProductDiffCallback()) {

    private var currentLocationContext: String = Location.MATRIZ
    private val updateDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
    private var isOnline: Boolean = true // Estado de la conexión


    fun setCurrentUserRole(role: UserRole?) {
        if (role != currentUserRole) {
            currentUserRole = role
            notifyDataSetChanged()
        }
    }
    fun setOnlineStatus(online: Boolean) {
        if (isOnline != online) {
            isOnline = online
            notifyDataSetChanged() // Redibujar todos los items visibles
        }
    }

    fun setCurrentLocationContext(newContext: String) {
        if (newContext != currentLocationContext) {
            currentLocationContext = newContext
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        return ProductViewHolder.from(parent, currentUserRole, updateDateFormat)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(getItem(position), actionListener, currentLocationContext)
    }

    class ProductViewHolder private constructor(
        private val binding: ItemProductBinding,
        private val userRole: UserRole?,
        private val dateFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Product, listener: ProductActionListener, contextLocation: String) {
            val context = binding.root.context
            val format = Locale.getDefault()
            val canModify = userRole == UserRole.ADMIN

            // --- HEADER INFO ---
            binding.textViewProductName.text = item.name.uppercase(Locale.ROOT)
            binding.textViewLocation.text = if (contextLocation == Location.MATRIZ) "Matriz" else "Congelador 04"
            item.updatedAt?.let {
                binding.textViewLastUpdate.text = "Últ. act: ${dateFormat.format(it)}"
            } ?: run {
                binding.textViewLastUpdate.text = ""
            }

            // --- MIN STOCK & WARNING ---
            val formattedMinStock = String.format(format, "%.2f", item.minStock)
            val minStockText = "StockMin: $formattedMinStock ${item.unit}"
            val isLowStock = item.totalStock <= item.minStock && item.minStock > 0.0

            if (isLowStock) {
                binding.textViewMinStockValue.setTextColor(Color.parseColor("#EF4444")) // Red color
                binding.textViewMinStockValue.text = "⚠️ $minStockText"
                if (binding.textViewMinStockValue.animation == null) {
                    val pulse = AnimationUtils.loadAnimation(context, R.anim.pulse_warning)
                    binding.textViewMinStockValue.startAnimation(pulse)
                }
            } else {
                binding.textViewMinStockValue.setTextColor(Color.parseColor("#64748b")) // Normal color
                binding.textViewMinStockValue.text = minStockText
                binding.textViewMinStockValue.clearAnimation()
            }

            // --- STOCK VALUES ---
            val stockValueToShow = if (contextLocation == Location.MATRIZ) item.stockMatriz else item.stockCongelador04
            binding.textViewLocationStockLabel.text = if (contextLocation == Location.MATRIZ) "Stock Matriz:" else "Stock C-04:"
            binding.textViewLocationStockValue.text = String.format(format, "%.2f", stockValueToShow)
            binding.textViewStockTotalValue.text = String.format(format, "%.2f", item.totalStock)

            // --- TOGGLE ACTION BUTTONS VISIBILITY ---
            if (contextLocation == Location.MATRIZ) {
                binding.layoutActionButtons.visibility = View.VISIBLE
                binding.layoutActionButtonsC04.visibility = View.GONE
            } else { // CONGELADOR_04
                binding.layoutActionButtons.visibility = View.GONE
                binding.layoutActionButtonsC04.visibility = View.VISIBLE
            }

            // Habilitar/deshabilitar botones según rol Y estado de conexión
            val canPerformWriteAction = canModify && isOnline
            val alphaValue = if (canPerformWriteAction) 1.0f else 0.5f

            binding.layoutActionButtons.alpha = alphaValue
            binding.layoutActionButtonsC04.alpha = alphaValue

            val offlineClickListener = View.OnClickListener {
                if (!isOnline) {
                    Toast.makeText(context, "Esta acción requiere conexión a internet.", Toast.LENGTH_SHORT).show()
                }
            }

            // --- SET LISTENERS ---
            binding.root.setOnClickListener { listener.onItemClicked(item) }

            binding.buttonAddCompra.setOnClickListener { if (canPerformWriteAction) listener.onAddCompraClicked(item) else offlineClickListener.onClick(it) }
            binding.buttonAddSalida.setOnClickListener { view -> if (canPerformWriteAction) listener.onSalidaClicked(item, view) else offlineClickListener.onClick(view) }
            binding.buttonAddTraspaso.setOnClickListener { view -> if (canPerformWriteAction) listener.onTraspasoC04Clicked(item, view) else offlineClickListener.onClick(view) }
            binding.buttonEditC04.setOnClickListener { if (canPerformWriteAction) listener.onEditC04Clicked(item) else offlineClickListener.onClick(it) }
            binding.buttonTraspasoC04M.setOnClickListener { if (canPerformWriteAction) listener.onTraspasoC04MClicked(item) else offlineClickListener.onClick(it) }
        }

        companion object {
            fun from(parent: ViewGroup, userRole: UserRole?, dateFormat: SimpleDateFormat): ProductViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemProductBinding.inflate(layoutInflater, parent, false)
                return ProductViewHolder(binding, userRole, dateFormat)
            }
        }
    }
}

class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
    override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
        return oldItem == newItem
    }
}
