package com.cesar.bocana.ui.traspasos.confirmar

import android.os.Bundle
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
import com.cesar.bocana.databinding.FragmentConfirmarTraspasoBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
                // Navegación a la pantalla de ajuste final
                val fragment = AjusteFinalTraspasoFragment.newInstance(plan.id)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.traspasos_fragment_container, fragment) // Navega dentro del contenedor de traspasos
                    .addToBackStack(null)
                    .commit()
            },
            onCancelClick = { plan ->
                // Mostrar diálogo de confirmación antes de cancelar
                mostrarDialogoDeCancelacion(plan)
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

    private fun mostrarDialogoDeCancelacion(plan: TraspasoPlanificado) {
        AlertDialog.Builder(requireContext())
            .setTitle("Cancelar Plan de Traspaso")
            .setMessage("¿Estás seguro de que quieres cancelar este plan? Esta acción no se puede deshacer.")
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
