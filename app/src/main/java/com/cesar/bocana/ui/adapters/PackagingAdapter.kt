package com.cesar.bocana.ui.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.databinding.ItemPackagingBinding
import java.text.SimpleDateFormat
import java.util.Locale

interface PackagingActionListener {
    fun onLabelsClicked(task: PendingPackagingTask)
    fun onMarkPackagedClicked(task: PendingPackagingTask)
}

enum class PackagingVisualLevel {
    BLUE,
    GREEN,
    AMBER,
    RED
}

data class PackagingUiItem(
    val task: PendingPackagingTask,
    val actualQuantityKg: Double,
    val statusText: String,
    val insightText: String,
    val visualLevel: PackagingVisualLevel,
    val priority: Boolean
)

class PackagingAdapter(private val listener: PackagingActionListener) :
    ListAdapter<PackagingUiItem, PackagingAdapter.PackagingViewHolder>(PackagingDiffCallback()) {

    private val dateFormatter = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackagingViewHolder =
        PackagingViewHolder(ItemPackagingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PackagingViewHolder, position: Int) {
        holder.bind(getItem(position), listener, dateFormatter)
    }

    class PackagingViewHolder(private val binding: ItemPackagingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PackagingUiItem, listener: PackagingActionListener, formatter: SimpleDateFormat) {
            val task = item.task
            binding.textViewPackProductName.text = task.productName
            binding.textViewPackQuantityValue.text = "${formatQuantity(item.actualQuantityKg)} ${task.unit.ifBlank { "Kg" }} pendientes"
            binding.textViewPackSupplier.text = task.supplierName?.takeIf { it.isNotBlank() } ?: "Proveedor sin registrar"
            binding.textViewPackDateValue.text = task.receivedAt?.let { "Llegó ${formatter.format(it)}" } ?: "Fecha no disponible"
            binding.textViewPackTimeElapsed.text = item.statusText
            binding.textViewPackInsight.text = item.insightText

            val palette = when (item.visualLevel) {
                PackagingVisualLevel.GREEN -> Palette("#EFF9F1", "#2E7D32", "#2E7D32")
                PackagingVisualLevel.BLUE -> Palette("#EEF5FF", "#2867A8", "#2867A8")
                PackagingVisualLevel.AMBER -> Palette("#FFF4E3", "#C66A00", "#C66A00")
                PackagingVisualLevel.RED -> Palette("#FBE3E3", "#B3261E", "#B3261E")
            }

            binding.packagingCardView.setCardBackgroundColor(Color.parseColor(palette.background))
            binding.packagingCardView.strokeColor = Color.parseColor(palette.accent)
            binding.packagingCardView.strokeWidth = 3
            binding.textViewPackTimeElapsed.setTextColor(Color.WHITE)
            binding.textViewPackTimeElapsed.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.parseColor(palette.badge))
            }
            // El mensaje operativo informa; no compite visualmente con la antigüedad.
            binding.textViewPackInsight.setTextColor(Color.parseColor("#555964"))

            binding.buttonLabels.setOnClickListener { listener.onLabelsClicked(task) }
            binding.buttonMarkPackaged.setOnClickListener { listener.onMarkPackagedClicked(task) }
        }

        private data class Palette(val background: String, val accent: String, val badge: String)

        companion object {
            private fun formatQuantity(value: Double): String {
                val rounded = kotlin.math.round(value)
                return if (kotlin.math.abs(value - rounded) < 0.01) {
                    rounded.toLong().toString()
                } else {
                    String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.')
                }
            }
        }
    }
}

class PackagingDiffCallback : DiffUtil.ItemCallback<PackagingUiItem>() {
    override fun areItemsTheSame(oldItem: PackagingUiItem, newItem: PackagingUiItem): Boolean =
        oldItem.task.id == newItem.task.id

    override fun areContentsTheSame(oldItem: PackagingUiItem, newItem: PackagingUiItem): Boolean =
        oldItem == newItem
}
