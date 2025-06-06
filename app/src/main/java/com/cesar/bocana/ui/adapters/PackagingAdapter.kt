package com.cesar.bocana.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.databinding.ItemPackagingBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

interface PackagingActionListener {
    fun onMarkPackagedClicked(task: PendingPackagingTask)
}

class PackagingAdapter(private val listener: PackagingActionListener) :
    ListAdapter<PendingPackagingTask, PackagingAdapter.PackagingViewHolder>(PackagingDiffCallback()) {

    private val dateFormatter = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackagingViewHolder {
        return PackagingViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: PackagingViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, listener, dateFormatter)
    }

    class PackagingViewHolder private constructor(private val binding: ItemPackagingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PendingPackagingTask, listener: PackagingActionListener, formatter: SimpleDateFormat) {
            binding.textViewPackProductName.text = item.productName
            binding.textViewPackQuantityValue.text = String.format(Locale.getDefault(), "%.2f %s", item.quantityReceived, item.unit)
            val receivedDate: Date? = item.receivedAt

            binding.textViewPackDateValue.text = receivedDate?.let { formatter.format(it) } ?: "--"

            val timeElapsedTextView = binding.textViewPackTimeElapsed
            if (receivedDate != null) {
                val currentTime = System.currentTimeMillis()
                val receivedTime = receivedDate.time
                val diffMillis = currentTime - receivedTime

                if (diffMillis >= 0) {
                    val days = TimeUnit.MILLISECONDS.toDays(diffMillis)

                    when {
                        days >= 3 -> {
                            timeElapsedTextView.text = "¡${days} DÍAS RETRASO!"
                            timeElapsedTextView.visibility = View.VISIBLE
                            binding.packagingCardView.setCardBackgroundColor(
                                ContextCompat.getColor(binding.root.context, R.color.low_stock_red_background)
                            )
                            timeElapsedTextView.setTextColor(Color.RED)
                        }
                        days == 2L -> {
                            timeElapsedTextView.text = "2 DÍAS RETRASO"
                            timeElapsedTextView.visibility = View.VISIBLE
                            binding.packagingCardView.setCardBackgroundColor(
                                ContextCompat.getColor(binding.root.context, R.color.pending_item_background) // Considera un color ámbar
                            )
                            timeElapsedTextView.setTextColor(ContextCompat.getColor(binding.root.context, R.color.black)) // O color ámbar
                        }
                        days == 1L -> {
                            timeElapsedTextView.text = "1 DÍA RETRASO"
                            timeElapsedTextView.visibility = View.VISIBLE
                            binding.packagingCardView.setCardBackgroundColor(
                                ContextCompat.getColor(binding.root.context, R.color.pending_item_background)
                            )
                            timeElapsedTextView.setTextColor(ContextCompat.getColor(binding.root.context, R.color.black))
                        }
                        else -> { // days == 0L
                            timeElapsedTextView.text = "Recibido hoy"
                            timeElapsedTextView.visibility = View.VISIBLE // O View.INVISIBLE
                            binding.packagingCardView.setCardBackgroundColor(
                                ContextCompat.getColor(binding.root.context, R.color.pending_item_background)
                            )
                            timeElapsedTextView.setTextColor(ContextCompat.getColor(binding.root.context, R.color.black))
                        }
                    }
                } else {
                    timeElapsedTextView.visibility = View.INVISIBLE
                    binding.packagingCardView.setCardBackgroundColor(
                        ContextCompat.getColor(binding.root.context, R.color.pending_item_background)
                    )
                }
            } else {
                timeElapsedTextView.visibility = View.INVISIBLE
                binding.packagingCardView.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.pending_item_background)
                )
            }

            binding.buttonMarkPackaged.setOnClickListener {
                listener.onMarkPackagedClicked(item)
            }
        }

        companion object {
            fun from(parent: ViewGroup): PackagingViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemPackagingBinding.inflate(layoutInflater, parent, false)
                return PackagingViewHolder(binding)
            }
        }
    }
}

class PackagingDiffCallback : DiffUtil.ItemCallback<PendingPackagingTask>() {
    override fun areItemsTheSame(oldItem: PendingPackagingTask, newItem: PendingPackagingTask): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: PendingPackagingTask, newItem: PendingPackagingTask): Boolean {
        return oldItem == newItem
    }
}