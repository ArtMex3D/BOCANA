package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.DialogEmpaqueBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

class EmpaqueDialogFragment : DialogFragment() {

    private var _binding: DialogEmpaqueBinding? = null
    private val binding get() = _binding!!

    private var packagingTask: PendingPackagingTask? = null
    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth

    companion object {
        const val TAG = "EmpaqueDialog"
        private const val ARG_TASK = "packaging_task_arg"

        fun newInstance(task: PendingPackagingTask): EmpaqueDialogFragment {
            return EmpaqueDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_TASK, task)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firestore = Firebase.firestore
        packagingTask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_TASK, PendingPackagingTask::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_TASK)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogEmpaqueBinding.inflate(LayoutInflater.from(context))
        val currentTask = packagingTask ?: run {
            Toast.makeText(context, "Error: Tarea de empaque no encontrada.", Toast.LENGTH_SHORT).show()
            return super.onCreateDialog(savedInstanceState)
        }

        setupUI(currentTask)
        setupListeners()

        val builder = AlertDialog.Builder(requireActivity()).setView(binding.root)
        val dialog = builder.create()
        dialog.setOnShowListener {
            binding.buttonDialogAceptar.setOnClickListener {
                validateAndPerformPackaging(currentTask)
            }
        }
        return dialog
    }

    private fun setupUI(task: PendingPackagingTask) {
        binding.textViewDialogTitle.text = "Empacar: ${task.productName}"
        binding.textViewTotalGranel.text = "Total a Granel Recibido: ${String.format(Locale.getDefault(), "%.2f", task.quantityReceived)} ${task.unit}"
    }

    private fun setupListeners() {
        binding.buttonDialogCancelar.setOnClickListener { dismiss() }

        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateTotalNeto() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.editTextCantidadUnidades.addTextChangedListener(textWatcher)
    }

    private fun updateTotalNeto() {
        val cantidad = binding.editTextCantidadUnidades.text.toString().toDoubleOrNull() ?: 0.0
        val totalGranel = packagingTask?.quantityReceived ?: 0.0

        if (cantidad > 0 && totalGranel > 0) {
            val promedio = totalGranel / cantidad
            binding.textViewPesoPromedioCalculado.text = String.format(Locale.getDefault(), "= %.2f Kg promedio por unidad", promedio)
            binding.textViewPesoPromedioCalculado.isVisible = true
        } else {
            binding.textViewPesoPromedioCalculado.isVisible = false
        }
    }


    private fun validateAndPerformPackaging(task: PendingPackagingTask) {
        val unidad = binding.editTextUnidadEmpaque.text.toString().trim()
        val cantidadUnidades = binding.editTextCantidadUnidades.text.toString().toDoubleOrNull()

        var isValid = true
        binding.textFieldLayoutUnidadEmpaque.error = null
        binding.textFieldLayoutCantidadUnidades.error = null

        if (unidad.isBlank()) {
            binding.textFieldLayoutUnidadEmpaque.error = "Define la unidad"
            isValid = false
        }
        if (cantidadUnidades == null || cantidadUnidades <= 0) {
            binding.textFieldLayoutCantidadUnidades.error = "Debe ser > 0"
            isValid = false
        }

        if (isValid) {
            performPackagingTransaction(task, unidad, cantidadUnidades!!)
        }
    }

    private fun performPackagingTransaction(task: PendingPackagingTask, unidad: String, cantidadUnidades: Double) {
        binding.buttonDialogAceptar.isEnabled = false
        binding.buttonDialogCancelar.isEnabled = false

        lifecycleScope.launch {
            try {
                val purchaseMovementId = task.purchaseMovementId ?: throw IllegalStateException("La tarea de empaque no tiene un ID de movimiento de compra asociado.")

                // Busca el lote a granel original usando el ID del movimiento de compra
                val lotQuery = firestore.collection("inventoryLots")
                    .whereEqualTo("movementIdIn", purchaseMovementId)
                    .limit(1)
                    .get().await()

                if (lotQuery.isEmpty) {
                    throw IllegalStateException("No se encontró el lote a granel original para esta tarea.")
                }
                val originalLotDoc = lotQuery.documents.first()
                val originalLotRef = originalLotDoc.reference

                firestore.runTransaction { transaction ->
                    val lotSnapshot = transaction.get(originalLotRef)
                    val lotToUpdate = lotSnapshot.toObject(StockLot::class.java)
                        ?: throw FirebaseFirestoreException("El lote a empacar ya no existe.", FirebaseFirestoreException.Code.ABORTED)

                    if (lotToUpdate.isPackaged) {
                        throw FirebaseFirestoreException("Este lote ya fue marcado como empacado.", FirebaseFirestoreException.Code.ABORTED)
                    }

                    // ***** INICIO DE LA NUEVA LÓGICA DE CÁLCULO *****
                    // Calcula el peso promedio basado en el total a granel y la cantidad de unidades.
                    val pesoPromedio = if (cantidadUnidades > 0) lotToUpdate.currentQuantity / cantidadUnidades else 0.0
                    // ***** FIN DE LA NUEVA LÓGICA DE CÁLCULO *****

                    // Prepara la actualización para el lote.
                    val updates = mapOf(
                        "isPackaged" to true, // <-- Marca el lote como empacado.
                        "unidadDeEmpaque" to unidad,
                        "pesoPorUnidad" to pesoPromedio, // <-- Guarda el peso promedio calculado.
                        "cantidadInicialUnidades" to cantidadUnidades
                    )
                    transaction.update(originalLotRef, updates)

                    // Elimina la tarea de la cola de pendientes.
                    val taskRef = firestore.collection("pendingPackaging").document(task.id)
                    transaction.delete(taskRef)
                }.await()

                if (isAdded) {
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), "Producto empacado y lote actualizado.", Snackbar.LENGTH_LONG).show()
                    dismiss()
                }

            } catch (e: Exception) {
                if (isAdded) {
                    Log.e(TAG, "Error en la transacción de empaque", e)
                    val errorMsg = e.message ?: "Ocurrió un error inesperado."
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), "Error: $errorMsg", Snackbar.LENGTH_LONG).show()
                    binding.buttonDialogAceptar.isEnabled = true
                    binding.buttonDialogCancelar.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

