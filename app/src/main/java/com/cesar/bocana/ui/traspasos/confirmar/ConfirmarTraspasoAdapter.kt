package com.cesar.bocana.ui.traspasos.confirmar

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.TraspasoPlanificado
import com.cesar.bocana.databinding.ItemTraspasoConfirmarBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ConfirmarTraspasoAdapter(
    private val onConfirmClick: (TraspasoPlanificado) -> Unit,
    private val onCancelClick: (TraspasoPlanificado) -> Unit,
    private val onPrintClick: (TraspasoPlanificado) -> Unit // Nuevo listener
) : ListAdapter<TraspasoPlanificado, ConfirmarTraspasoAdapter.ConfirmarViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm", Locale("es", "ES"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfirmarViewHolder {
        val binding = ItemTraspasoConfirmarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConfirmarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConfirmarViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConfirmarViewHolder(private val binding: ItemTraspasoConfirmarBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(plan: TraspasoPlanificado) {
            val fecha = plan.createdAt?.let { dateFormat.format(it) } ?: "Fecha no disponible"
            binding.textViewPlanDate.text = "Plan del: $fecha" // Título más informativo
            binding.textViewCreatedBy.text = "Creado por: ${plan.createdBy}"

            // Cargar productos dinámicamente
            binding.linearLayoutProducts.removeAllViews()
            binding.textViewProductsLabel.text = "Cargando productos..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val detallesSnapshot = Firebase.firestore.collection("traspasos_planificados").document(plan.id).collection("detalles").get().await()
                    val productos = detallesSnapshot.documents.mapNotNull { it.getString("productName") }
                    withContext(Dispatchers.Main) {
                        if (productos.isNotEmpty()) {
                            binding.textViewProductsLabel.text = "Productos a mover:"
                            productos.forEach { productName ->
                                val textView = TextView(binding.root.context).apply {
                                    text = "• $productName"
                                }
                                binding.linearLayoutProducts.addView(textView)
                            }
                        } else {
                            binding.textViewProductsLabel.text = "Sin productos en este plan."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.textViewProductsLabel.text = "Error al cargar productos."
                    }
                }
            }


            binding.buttonConfirm.setOnClickListener { onConfirmClick(plan) }
            binding.buttonCancel.setOnClickListener { onCancelClick(plan) }
            binding.buttonImprimir.setOnClickListener { onPrintClick(plan) } // Asignar nuevo listener
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TraspasoPlanificado>() {
        override fun areItemsTheSame(oldItem: TraspasoPlanificado, newItem: TraspasoPlanificado): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TraspasoPlanificado, newItem: TraspasoPlanificado): Boolean = oldItem == newItem
    }
}
