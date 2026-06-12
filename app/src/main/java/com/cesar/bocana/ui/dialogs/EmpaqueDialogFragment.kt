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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
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

        binding.radioGroupCalculationType.setOnCheckedChangeListener { _, checkedId ->
            val isRedondeo = checkedId == R.id.radioButtonRedondeo
            // El campo Peso Fijo solo se muestra en modo Semifijo
            binding.textFieldLayoutPesoFijo.isVisible = isRedondeo
            updateCalculoResultado()
        }

        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateCalculoResultado() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.editTextCantidadUnidades.addTextChangedListener(textWatcher)
        binding.editTextPesoFijo.addTextChangedListener(textWatcher)
        binding.editTextUnidadEmpaque.addTextChangedListener(textWatcher)
    }

    private fun updateCalculoResultado() {
        val cantidadUnidades = binding.editTextCantidadUnidades.text.toString().toIntOrNull() ?: 0
        val totalGranel = packagingTask?.quantityReceived ?: 0.0
        val unidadEmpaque = binding.editTextUnidadEmpaque.text.toString().trim().ifEmpty { "Unidad" }
        val isVariable = binding.radioButtonVariable.isChecked

        binding.textViewCalculoResultado.isVisible = false
        if (cantidadUnidades <= 0 || totalGranel <= 0) return

        if (isVariable) {
            // LÓGICA NUEVA: PROMEDIO VARIABLE
            val promedio = totalGranel / cantidadUnidades
            binding.textViewCalculoResultado.setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_700))
            binding.textViewCalculoResultado.text = String.format(Locale.getDefault(), "= %d %ss de %.2f Kg promedio c/u", cantidadUnidades, unidadEmpaque, promedio)
            binding.textViewCalculoResultado.isVisible = true
        } else {
            // LÓGICA VIEJA INTACTA: SEMIFIJO / REDONDEO
            val pesoFijo = binding.editTextPesoFijo.text.toString().toDoubleOrNull() ?: 0.0
            if (pesoFijo <= 0) return

            if (cantidadUnidades == 1) {
                binding.textViewCalculoResultado.setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_700))
                binding.textViewCalculoResultado.text = String.format(Locale.getDefault(), "= 1 %s de %.2f Kg", unidadEmpaque, totalGranel)
                binding.textViewCalculoResultado.isVisible = true
                return
            }

            val cajasNormales = cantidadUnidades - 1
            val totalEnCajasNormales = cajasNormales * pesoFijo
            val pesoUltimaCaja = totalGranel - totalEnCajasNormales

            if (pesoUltimaCaja <= 0) {
                binding.textViewCalculoResultado.text = "Incongruencia: El peso es mayor al disponible."
                binding.textViewCalculoResultado.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            } else {
                binding.textViewCalculoResultado.setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_700))
                binding.textViewCalculoResultado.text = String.format(Locale.getDefault(), "= %d %ss de %.2f Kg y 1 %s de %.2f Kg", cajasNormales, unidadEmpaque, pesoFijo, unidadEmpaque, pesoUltimaCaja)
            }
            binding.textViewCalculoResultado.isVisible = true
        }
    }

    private fun validateAndPerformPackaging(task: PendingPackagingTask) {
        val isVariable = binding.radioButtonVariable.isChecked
        val unidad = binding.editTextUnidadEmpaque.text.toString().trim()
        val cantidadUnidades = binding.editTextCantidadUnidades.text.toString().toIntOrNull()
        val pesoFijo = binding.editTextPesoFijo.text.toString().toDoubleOrNull()
        val totalGranel = task.quantityReceived
        var isValid = true

        binding.textFieldLayoutUnidadEmpaque.error = null
        binding.textFieldLayoutCantidadUnidades.error = null
        binding.textFieldLayoutPesoFijo.error = null

        if (unidad.isBlank()) {
            binding.textFieldLayoutUnidadEmpaque.error = "Define la unidad"
            isValid = false
        }
        if (cantidadUnidades == null || cantidadUnidades <= 0) {
            binding.textFieldLayoutCantidadUnidades.error = "Debe ser > 0"
            isValid = false
        }

        if (!isVariable) {
            if (pesoFijo == null || pesoFijo <= 0) {
                binding.textFieldLayoutPesoFijo.error = "Define un peso fijo > 0"
                isValid = false
            } else if (cantidadUnidades != null && cantidadUnidades > 1) {
                val totalEstimado = (cantidadUnidades - 1) * pesoFijo
                if (totalEstimado >= totalGranel) {
                    binding.textFieldLayoutPesoFijo.error = "Incongruencia: El total de las unidades fijas supera el stock a granel."
                    isValid = false
                }
            }
        }

        if (isValid) {
            performPackagingTransaction(task, unidad, cantidadUnidades!!, pesoFijo, isVariable)
        }
    }

    private fun performPackagingTransaction(task: PendingPackagingTask, unidad: String, cantidadUnidades: Int, pesoFijo: Double?, isVariable: Boolean) {
        binding.buttonDialogAceptar.isEnabled = false
        binding.buttonDialogCancelar.isEnabled = false

        lifecycleScope.launch {
            try {
                val purchaseMovementId = task.purchaseMovementId ?: throw IllegalStateException("La tarea no tiene ID de movimiento asociado.")

                val lotQuery = firestore.collection("inventoryLots")
                    .whereEqualTo("movementIdIn", purchaseMovementId)
                    .limit(1)
                    .get().await()

                if (lotQuery.isEmpty) throw IllegalStateException("No se encontró el lote a granel original.")

                val originalLotDoc = lotQuery.documents.first()
                val originalLotRef = originalLotDoc.reference

                firestore.runTransaction { transaction ->
                    val lotSnapshot = transaction.get(originalLotRef)
                    val lotToUpdate = lotSnapshot.toObject(StockLot::class.java)
                        ?: throw FirebaseFirestoreException("El lote a empacar ya no existe.", FirebaseFirestoreException.Code.ABORTED)

                    if (lotToUpdate.isPackaged) throw FirebaseFirestoreException("Este lote ya fue marcado como empacado.", FirebaseFirestoreException.Code.ABORTED)

                    if (isVariable) {
                        // LÓGICA NUEVA: VARIABLE (PROMEDIADOR)
                        val pesoPromedio = lotToUpdate.currentQuantity / cantidadUnidades
                        val updates = mapOf(
                            "isPackaged" to true,
                            "unidadDeEmpaque" to unidad,
                            "pesoPorUnidad" to pesoPromedio,
                            "cantidadInicialUnidades" to cantidadUnidades.toDouble()
                        )
                        transaction.update(originalLotRef, updates)
                    } else {
                        // LÓGICA VIEJA INTACTA: SEMIFIJO (REDONDEO)
                        transaction.update(originalLotRef, "isDepleted", true)

                        val totalKg = lotToUpdate.currentQuantity
                        var cajasNormales = 0
                        var pesoUltimaCaja = 0.0

                        if (cantidadUnidades > 1) {
                            cajasNormales = cantidadUnidades - 1
                            pesoUltimaCaja = totalKg - (cajasNormales * (pesoFijo ?: 0.0))
                        } else {
                            pesoUltimaCaja = totalKg
                        }

                        if (cajasNormales > 0 && pesoFijo != null) {
                            val newLotRefNormal = firestore.collection("inventoryLots").document()
                            val newLotNormal = lotToUpdate.copy(
                                id = newLotRefNormal.id, isPackaged = true, unidadDeEmpaque = unidad,
                                pesoPorUnidad = pesoFijo, initialQuantity = cajasNormales * pesoFijo,
                                currentQuantity = cajasNormales * pesoFijo, cantidadInicialUnidades = cajasNormales.toDouble()
                            )
                            transaction.set(newLotRefNormal, newLotNormal)
                        }

                        if (pesoUltimaCaja > 0.01) {
                            val newLotRefSobrante = firestore.collection("inventoryLots").document()
                            val newLotSobrante = lotToUpdate.copy(
                                id = newLotRefSobrante.id, isPackaged = true, unidadDeEmpaque = unidad,
                                pesoPorUnidad = pesoUltimaCaja, initialQuantity = pesoUltimaCaja,
                                currentQuantity = pesoUltimaCaja, cantidadInicialUnidades = 1.0
                            )
                            transaction.set(newLotRefSobrante, newLotSobrante)
                        }
                    }

                    val taskRef = firestore.collection("pendingPackaging").document(task.id)
                    transaction.delete(taskRef)
                }.await()

                if (isAdded) {
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), "Producto empacado correctamente.", Snackbar.LENGTH_LONG).show()
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