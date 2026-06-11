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
            onEliminarClick = { plan ->
                mostrarDialogoDeEliminacion(plan)
            },
            onVerPdfClick = { plan ->
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
                // 🚀 OPTIMIZACIÓN EXTREMA: Descargamos todo el catálogo en 1 solo viaje
                val productsSnapshot = Firebase.firestore.collection("products").get().await()
                val todosLosProductos = productsSnapshot.toObjects(com.cesar.bocana.data.model.Product::class.java)

                val detallesSnapshot = Firebase.firestore.collection("traspasos_planificados")
                    .document(plan.id)
                    .collection("detalles")
                    .orderBy("orden") // 🛠️ LA CORRECCIÓN: Le ordenamos a Firebase que respete la posición exacta
                    .get().await()

                val sugerenciasParaPdf = detallesSnapshot.documents.mapNotNull { doc ->
                    val detalle = doc.toObject<com.cesar.bocana.data.model.DetalleTraspasoPlan>() ?: return@mapNotNull null

                    // 🐛 FIX: Ahora buscamos por productId, que siempre es "FILA_VACIA" exacto
                    if (detalle.productId == "FILA_VACIA") {
                        TraspasoSugerenciaItem(
                            product = com.cesar.bocana.data.model.Product(id = "FILA_VACIA", name = "Espacios en Blanco para Notas"),
                            sugerenciaKg = 0.0,
                            lotesParaTraspaso = emptyList(),
                            impactoStockMatriz = 0.0,
                            incluidoEnPdf = true,
                            cantidadEditadaUnidades = detalle.sugerenciaUnidades, // Aquí se rescatan las filas
                            unidadDeEmpaqueEditada = ""
                        )
                    } else {
                        // 🚀 Buscamos el producto en la memoria (Toma 0.001 segundos)
                        val product = todosLosProductos.find { it.id == detalle.productId } ?: return@mapNotNull null

                        TraspasoSugerenciaItem(
                            product = product,
                            sugerenciaKg = detalle.sugerenciaKg,
                            lotesParaTraspaso = detalle.lotesSugeridos,
                            impactoStockMatriz = 0.0,
                            incluidoEnPdf = true,
                            cantidadEditadaUnidades = detalle.sugerenciaUnidades,
                            unidadDeEmpaqueEditada = detalle.unidadDeEmpaque
                        )
                    }
                }

                if (sugerenciasParaPdf.isEmpty()) {
                    Snackbar.make(binding.root, "Este PDF está vacío.", Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                val pdfFile = TraspasoPdfGenerator.createTraspasoPdf(requireContext(), sugerenciasParaPdf, plan.fechaPlan!!)
                val pdfViewerFragment = PdfViewerFragment.newInstance(pdfFile.absolutePath)

                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, pdfViewerFragment)
                    .addToBackStack(null)
                    .commit()

            } catch (e: Exception) {
                Log.e("ConfirmarTraspaso", "Error al generar PDF", e)
                Snackbar.make(binding.root, "Error al abrir el PDF: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                if(isAdded) binding.progressBarConfirmar.isVisible = false
            }
        }
    }

    private fun mostrarDialogoDeEliminacion(plan: TraspasoPlanificado) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar Documento")
            .setMessage("¿Estás seguro de que quieres borrar este registro de PDF?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sí, Eliminar") { _, _ ->
                viewModel.eliminarPlan(plan)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}