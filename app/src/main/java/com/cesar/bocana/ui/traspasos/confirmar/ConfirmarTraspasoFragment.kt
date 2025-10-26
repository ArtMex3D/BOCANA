package com.cesar.bocana.ui.traspasos.confirmar

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.TraspasoPlanificado
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.cesar.bocana.databinding.FragmentConfirmarTraspasoBinding
import com.cesar.bocana.ui.printing.PdfViewerFragment
import com.cesar.bocana.ui.traspasos.plan.TraspasoPdfGenerator
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ConfirmarTraspasoFragment : Fragment() {

    private var _binding: FragmentConfirmarTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfirmarTraspasoViewModel by viewModels()
    private lateinit var adapter: ConfirmarTraspasoAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfirmarTraspasoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ConfirmarTraspasoAdapter(
            onConfirmClick = { plan ->
                val fragment = AjusteFinalTraspasoFragment.newInstance(plan.id)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.traspasos_fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onCancelClick = { plan ->
                mostrarDialogoDeCancelacion(plan)
            },
            onPrintClick = { plan ->
                generarYVisualizarPdf(plan)
            }
        )
        binding.recyclerViewConfirmarTraspaso.adapter = adapter
        binding.recyclerViewConfirmarTraspaso.layoutManager = LinearLayoutManager(context)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarConfirmar.isVisible = state.isLoading
                binding.textViewEmpty.isVisible = !state.isLoading && state.planes.isEmpty()
                adapter.submitList(state.planes)

                state.userMessage?.let {
                    Snackbar.make(binding.root, it.message, Snackbar.LENGTH_LONG).show()
                    viewModel.onUserMessageShown()
                }
            }
        }
    }

    private fun generarYVisualizarPdf(plan: TraspasoPlanificado) {
        binding.progressBarConfirmar.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Obtener los detalles y productos asociados al plan
                val detallesSnapshot = Firebase.firestore.collection("traspasos_planificados")
                    .document(plan.id).collection("detalles").get().await()

                val sugerenciasParaPdf = detallesSnapshot.documents.mapNotNull { doc ->
                    val detalle = doc.toObject<com.cesar.bocana.data.model.DetalleTraspasoPlan>() ?: return@mapNotNull null
                    val productSnapshot = Firebase.firestore.collection("products").document(detalle.productId).get().await()
                    val product = productSnapshot.toObject<com.cesar.bocana.data.model.Product>() ?: return@mapNotNull null

                    TraspasoSugerenciaItem(
                        product = product,
                        sugerenciaKg = detalle.sugerenciaKg,
                        lotesParaTraspaso = detalle.lotesSugeridos,
                        impactoStockMatriz = 0.0, // No es relevante para la reimpresión
                        incluidoEnPdf = true, // Todos los detalles guardados se incluyen
                        cantidadEditadaUnidades = detalle.sugerenciaUnidades,
                        unidadDeEmpaqueEditada = detalle.unidadDeEmpaque
                    )
                }

                if (sugerenciasParaPdf.isEmpty()) {
                    Snackbar.make(binding.root, "Este plan no tiene productos para imprimir.", Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. Generar el PDF
                val pdfFile = TraspasoPdfGenerator.createTraspasoPdf(requireContext(), sugerenciasParaPdf, plan.fechaPlan!!)
                val pdfViewerFragment = PdfViewerFragment.newInstance(pdfFile.absolutePath)

                parentFragmentManager.beginTransaction()
                    .replace(R.id.traspasos_fragment_container, pdfViewerFragment)
                    .addToBackStack(null)
                    .commit()

            } catch (e: Exception) {
                Log.e("ConfirmarTraspaso", "Error al generar PDF", e)
                Snackbar.make(binding.root, "Error al generar el PDF: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                if(isAdded) binding.progressBarConfirmar.isVisible = false
            }
        }
    }


    private fun mostrarDialogoDeCancelacion(plan: TraspasoPlanificado) {
        AlertDialog.Builder(requireContext())
            .setTitle("Cancelar Plan de Traspaso")
            .setMessage("¿Estás seguro de que quieres cancelar este plan? Los lotes reservados serán liberados.")
            .setNegativeButton("No", null)
            .setPositiveButton("Sí, Cancelar") { _, _ ->
                viewModel.cancelarPlan(plan)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}